package com.xinguo.seckill.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.xinguo.seckill.common.constant.RedisKeyConstant;
import com.xinguo.seckill.common.dto.OrderMessageDTO;
import com.xinguo.seckill.common.exception.BusinessException;
import com.xinguo.seckill.order.client.InternalClient;
import com.xinguo.seckill.order.dto.OrderDTO.OrderStatusResult;
import com.xinguo.seckill.order.entity.SeckillLog;
import com.xinguo.seckill.order.entity.SeckillOrder;
import com.xinguo.seckill.order.mapper.SeckillLogMapper;
import com.xinguo.seckill.order.mapper.SeckillOrderMapper;
import com.xinguo.seckill.order.mq.OrderTimeoutProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 订单生命周期服务（需求文档 3.2 落库 / 3.3 支付 / 3.4 取消 / 8.4-8.5 查询）：
 * - processSeckillMessage：MQ 消息落库 + 扣真实库存 + 发 30 分钟延迟消息
 * - pay：补偿式两阶段支付（先扣钱包，再本地置已支付，失败补偿退款）
 * - cancel：超时(2)/主动(3)取消，回滚 Redis 库存 + goods DB 库存 + 删用户标记
 * - listMy / getStatus / logs：订单与秒杀日志查询
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** 状态：0-待支付 1-已支付 2-已取消(超时) 3-已取消(用户主动)（3.1） */
    public static final int ST_WAIT_PAY = 0;
    public static final int ST_PAID = 1;
    public static final int ST_TIMEOUT_CANCELLED = 2;
    public static final int ST_USER_CANCELLED = 3;

    private final SeckillOrderMapper orderMapper;
    private final SeckillLogMapper logMapper;
    private final InternalClient internalClient;
    private final StringRedisTemplate redisTemplate;
    private final OrderTimeoutProducer timeoutProducer;

    @Value("${seckill.order.pay-timeout-minutes:30}")
    private int payTimeoutMinutes;

    public OrderService(SeckillOrderMapper orderMapper,
                        SeckillLogMapper logMapper,
                        InternalClient internalClient,
                        StringRedisTemplate redisTemplate,
                        OrderTimeoutProducer timeoutProducer) {
        this.orderMapper = orderMapper;
        this.logMapper = logMapper;
        this.internalClient = internalClient;
        this.redisTemplate = redisTemplate;
        this.timeoutProducer = timeoutProducer;
    }

    /** MQ 消息落库（3.2）：插入 status=0 订单 -> 扣真实库存 -> 发 30 分钟延迟消息 */
    @Transactional(rollbackFor = Exception.class)
    public void processSeckillMessage(OrderMessageDTO msg) {
        // 幂等：order_no 唯一索引，重复消息插入抛异常 -> 忽略（3.2）
        SeckillOrder exists = orderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getOrderNo, msg.getOrderNo()));
        if (exists != null) {
            log.info("[order] duplicate msg ignored orderNo={}", msg.getOrderNo());
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        SeckillOrder order = new SeckillOrder();
        order.setOrderNo(msg.getOrderNo());
        order.setActivityId(msg.getActivityId());
        order.setUserId(msg.getUserId());
        order.setGoodsId(msg.getGoodsId());
        order.setGoodsName(""); // 落库前可回填，此处留空不阻断（详情接口会展示活动名）
        order.setPrice(msg.getPrice());
        order.setStatus(ST_WAIT_PAY);
        order.setCreatedAt(now);
        order.setPayDeadline(now.plusMinutes(payTimeoutMinutes));
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // uk_user_active / order_no 唯一键冲突：同用户同活动已有非取消态订单（或 orderNo 已存在）。
            // 该消息为重复或失效消息，视为已处理吞掉，避免 consumer RECONSUME_LATER 无限重试
            // 造成日志风暴与 MQ 积压（T18 压测暴露 activity=1001 死循环）。
            log.info("[order] duplicate uk ignored orderNo={} activity={} user={}",
                    msg.getOrderNo(), msg.getActivityId(), msg.getUserId());
            return;
        }
        writeLog(msg.getOrderNo(), msg.getActivityId(), msg.getUserId(), "ORDER_CREATE", "insert order");

        // 扣真实库存（goods，乐观锁；失败重试在 goods 服务内部，本处单次调用成功与否判定）
        InternalClient.InternalResult r = internalClient.deductStock(msg.getGoodsId(), 1);
        if (r.code != 0) {
            // 库存扣减失败：订单置 3（取消），告警（3.2）
            orderMapper.update(null, new LambdaUpdateWrapper<SeckillOrder>()
                    .eq(SeckillOrder::getOrderNo, msg.getOrderNo())
                    .eq(SeckillOrder::getStatus, ST_WAIT_PAY)
                    .set(SeckillOrder::getStatus, ST_USER_CANCELLED));
            writeLog(msg.getOrderNo(), msg.getActivityId(), msg.getUserId(), "ORDER_CANCEL",
                    "stock deduct failed(" + r.code + "), order cancelled");
            log.error("[order] stock deduct failed orderNo={} goodsId={} code={}", msg.getOrderNo(), msg.getGoodsId(), r.code);
            return;
        }

        // 发延迟消息（超时取消触发，3.4）；失败落待补标记，scheduler 扫描兜底（双机制）
        if (!timeoutProducer.sendDelayed(msg.getOrderNo(), payTimeoutMinutes)) {
            writeLog(msg.getOrderNo(), msg.getActivityId(), msg.getUserId(), "ORDER_TIMEOUT_SEND_FAIL",
                    "delayed msg send failed, rely on scheduler scan");
            log.warn("[order] delayed msg send failed, rely on scheduler orderNo={}", msg.getOrderNo());
        }
    }

    /**
     * 补偿式两阶段支付（8.8 / 3.3）：本地事务，锁行 -> 调 user 扣款 -> 置 status=1 -> 失败补偿退款。
     */
    @Transactional(rollbackFor = Exception.class)
    public PayResult pay(String orderNo, Long userId) {
        // 锁行（防与取消/另一支付并发）
        SeckillOrder order = orderMapper.selectByOrderNoForUpdate(orderNo);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(4005, "订单不存在或无权操作");
        }
        if (order.getStatus() != ST_WAIT_PAY) {
            throw new BusinessException(4006, "订单已支付或已取消，请勿重复支付");
        }
        if (order.getPayDeadline() != null && order.getPayDeadline().isBefore(LocalDateTime.now())) {
            throw new BusinessException(4007, "订单已超过支付期限，已自动取消");
        }

        // 阶段一：调 user 条件扣款（写流水）
        InternalClient.InternalResult deduct = internalClient.deductWallet(userId, order.getPrice(), orderNo);
        if (deduct.code != 0) {
            // 区分业务失败(4004 余额不足)与系统失败(内部服务不可用等)，避免错误码误导（review P1-4）
            if (deduct.code == 4004) {
                throw new BusinessException(4004, "钱包余额不足，请先充值");
            }
            throw new BusinessException(5000, "钱包服务暂不可用，请稍后重试(" + deduct.code + ")");
        }
        BigDecimal balanceAfter = deduce(deduct.data);

        // 阶段二：本地置已支付（条件更新防并发重复支付）
        int rows;
        try {
            rows = orderMapper.update(null, new LambdaUpdateWrapper<SeckillOrder>()
                    .eq(SeckillOrder::getOrderNo, orderNo)
                    .eq(SeckillOrder::getStatus, ST_WAIT_PAY)
                    .set(SeckillOrder::getStatus, ST_PAID)
                    .set(SeckillOrder::getPayChannel, 1)
                    .set(SeckillOrder::getPaidAt, LocalDateTime.now()));
        } catch (Exception e) {
            // 本地更新抛异常会回滚事务，但阶段一扣款已提交不会回滚 -> 主动补偿退款（review P1-3）
            log.error("[order] local pay update failed, compensating refund orderNo={}", orderNo, e);
            try {
                internalClient.refundWallet(userId, order.getPrice(), orderNo);
            } catch (Exception ex) {
                log.error("[order] compensating refund failed orderNo={}", orderNo, ex);
            }
            throw e;
        }
        if (rows == 0) {
            // 已被并发置非 0 -> 补偿退款（3.3）
            internalClient.refundWallet(userId, order.getPrice(), orderNo);
            throw new BusinessException(4006, "订单已支付或已取消，请勿重复支付");
        }
        writeLog(orderNo, order.getActivityId(), userId, "WALLET_PAY", "paid success");
        return new PayResult(orderNo, ST_PAID, order.getPrice(), balanceAfter);
    }

    /** 取消订单（超时 2 / 主动 3 共用，3.4）：条件更新防重复 -> 回滚 Redis -> 回补 goods DB 库存 -> 删用户标记 */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String orderNo, Long userId, int targetStatus) {
        SeckillOrder order = orderMapper.selectByOrderNoForUpdate(orderNo);
        if (order == null) {
            throw new BusinessException(4005, "订单不存在或无权操作");
        }
        if (userId != null && !order.getUserId().equals(userId)) {
            throw new BusinessException(4005, "订单不存在或无权操作");
        }
        // 条件更新：仅待支付可取消
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<SeckillOrder>()
                .eq(SeckillOrder::getOrderNo, orderNo)
                .eq(SeckillOrder::getStatus, ST_WAIT_PAY)
                .set(SeckillOrder::getStatus, targetStatus));
        if (rows == 0) {
            throw new BusinessException(4006, "订单已支付或已取消，请勿重复操作");
        }

        // 回滚 Redis 库存 + 删除用户标记（允许重抢 R5）
        redisTemplate.opsForValue().increment(RedisKeyConstant.stockKey(order.getActivityId()));
        redisTemplate.delete(RedisKeyConstant.userKey(order.getActivityId(), order.getUserId()));

        // 回补 goods DB 库存（D8；失败记告警）
        InternalClient.InternalResult r = internalClient.rollbackStock(order.getGoodsId(), 1);
        if (r.code != 0) {
            log.error("[order] goods rollback failed orderNo={} goodsId={} code={}", orderNo, order.getGoodsId(), r.code);
        }

        String action = targetStatus == ST_TIMEOUT_CANCELLED ? "ORDER_TIMEOUT" : "ORDER_CANCEL";
        writeLog(orderNo, order.getActivityId(), order.getUserId(), action, "cancelled status=" + targetStatus);
    }

    /**
     * 延迟消息触发前置校验：仅当订单存在且已过支付期限才允许超时取消。
     * 目的：延迟档位就近映射可能早于精确期限触发，防止误取消仍在支付窗口内的订单（review P2-5 加固）。
     */
    public boolean deadlinePassed(String orderNo) {
        SeckillOrder o = orderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getOrderNo, orderNo));
        return o != null && o.getPayDeadline() != null
                && o.getPayDeadline().isBefore(LocalDateTime.now());
    }

    /**
     * 待补标记补偿（T10）：查询"延迟消息发送失败(ORDER_TIMEOUT_SEND_FAIL)"、仍待支付、未过期支付期限、
     * 且尚未成功重发过(排除 ORDER_TIMEOUT_RESEND)的订单号。
     * 已过支付期限的订单由超时扫描兜底(timeout-list)接管，此处只返回未款期者，避免重复取消；
     * 排除 RESEND 标记避免 scheduler 每轮对同一订单无限重发（review P1-1）。
     */
    public List<String> queryTimeoutSendFailOrders() {
        List<Object> marked = logMapper.selectObjs(new QueryWrapper<SeckillLog>()
                .select("DISTINCT order_no")
                .eq("action", "ORDER_TIMEOUT_SEND_FAIL")
                .isNotNull("order_no"));
        if (marked.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> orderNos = new HashSet<>();
        for (Object o : marked) {
            String no = stringOrEmpty(o);
            if (!no.isEmpty()) {
                orderNos.add(no);
            }
        }
        // 已成功重发过(RESEND)的订单从补偿列表中移除
        List<Object> resended = logMapper.selectObjs(new QueryWrapper<SeckillLog>()
                .select("DISTINCT order_no")
                .eq("action", "ORDER_TIMEOUT_RESEND")
                .isNotNull("order_no"));
        for (Object o : resended) {
            String no = stringOrEmpty(o);
            if (!no.isEmpty()) {
                orderNos.remove(no);
            }
        }
        if (orderNos.isEmpty()) {
            return Collections.emptyList();
        }
        return orderMapper.selectList(new LambdaQueryWrapper<SeckillOrder>()
                        .in(SeckillOrder::getOrderNo, orderNos)
                        .eq(SeckillOrder::getStatus, ST_WAIT_PAY)
                        .gt(SeckillOrder::getPayDeadline, LocalDateTime.now()))
                .stream().map(SeckillOrder::getOrderNo).toList();
    }

    private String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /**
     * 待补标记补偿（T10）：对延迟消息发送失败的订单重新发送延迟消息（scheduler 触发）。
     * 仅待支付订单允许重发；订单不存在/已非待支付抛 4005/4006，发送失败返回 false（由调用方报 5000），
     * 区分"业务拒绝"与"系统失败"两种成因（review P2-3）。
     */
    public boolean resendDelayed(String orderNo) {
        SeckillOrder o = orderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getOrderNo, orderNo));
        if (o == null) {
            throw new BusinessException(4005, "订单不存在");
        }
        if (o.getStatus() != ST_WAIT_PAY) {
            throw new BusinessException(4006, "订单已非待支付状态");
        }
        boolean ok = timeoutProducer.sendDelayed(orderNo, payTimeoutMinutes);
        if (ok) {
            writeLog(orderNo, o.getActivityId(), o.getUserId(), "ORDER_TIMEOUT_RESEND", "delayed msg resent");
        } else {
            writeLog(orderNo, o.getActivityId(), o.getUserId(), "ORDER_TIMEOUT_SEND_FAIL", "resend failed, rely on scan");
        }
        return ok;
    }

    /** 8.4 查询订单状态（轮询）：DB 有行按状态映射；无行但 Redis 有用户标记 -> QUEUED（R3） */
    public OrderStatusResult queryStatus(String orderNo, Long userId) {
        SeckillOrder order = orderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getOrderNo, orderNo));
        if (order == null) {
            return queryQueuedOrFail(orderNo, userId);
        }
        if (!order.getUserId().equals(userId)) {
            return new OrderStatusResult(orderNo, "UNKNOWN", null, null);
        }
        return new OrderStatusResult(orderNo, statusText(order.getStatus()), order.getPrice(), order.getPayDeadline());
    }

    /** 订单未落库（MQ 处理中）：经 MQ_SEND 日志定位活动与用户，校验 Redis 用户标记判定 QUEUED */
    private OrderStatusResult queryQueuedOrFail(String orderNo, Long userId) {
        SeckillLog mqLog = logMapper.selectOne(new LambdaQueryWrapper<SeckillLog>()
                .eq(SeckillLog::getOrderNo, orderNo)
                .eq(SeckillLog::getAction, "MQ_SEND")
                .last("LIMIT 1"));
        if (mqLog == null || mqLog.getUserId() == null || !mqLog.getUserId().equals(userId)) {
            // 非本人订单，避免信息泄露统一返回 UNKNOWN
            return new OrderStatusResult(orderNo, "UNKNOWN", null, null);
        }
        // Redis 用户标记存在 -> 排队中（Lua 已扣未落库）
        if (Boolean.TRUE.equals(redisTemplate.hasKey(
                RedisKeyConstant.userKey(mqLog.getActivityId(), mqLog.getUserId())))) {
            return new OrderStatusResult(orderNo, "QUEUED", null, null);
        }
        return new OrderStatusResult(orderNo, "UNKNOWN", null, null);
    }

    private String statusText(int status) {
        switch (status) {
            case ST_WAIT_PAY:
                return "WAIT_PAY";
            case ST_PAID:
                return "PAID";
            case ST_TIMEOUT_CANCELLED:
            case ST_USER_CANCELLED:
                return "CANCELLED";
            default:
                return "UNKNOWN";
        }
    }

    /** 8.5 我的订单列表（分页，status 可空） */
    public List<SeckillOrder> listMy(Long userId, Integer status, long page, long size) {
        LambdaQueryWrapper<SeckillOrder> qw = new LambdaQueryWrapper<SeckillOrder>()
                .eq(SeckillOrder::getUserId, userId)
                .orderByDesc(SeckillOrder::getCreatedAt);
        if (status != null) {
            qw.eq(SeckillOrder::getStatus, status);
        }
        return orderMapper.selectPage(new Page<>(page, size), qw).getRecords();
    }

    private BigDecimal deduce(JsonNode data) {
        if (data == null || data.isNull() || data.isMissingNode()) {
            return BigDecimal.ZERO;
        }
        return data.decimalValue();
    }

    private void writeLog(String orderNo, Long activityId, Long userId, String action, String detail) {
        SeckillLog row = new SeckillLog();
        row.setOrderNo(orderNo);
        row.setActivityId(activityId);
        row.setUserId(userId);
        row.setAction(action);
        row.setDetail(detail);
        try {
            logMapper.insert(row);
        } catch (Exception e) {
            log.warn("[order] write log failed action={}", action, e);
        }
    }

    /** 8.8 支付返回体 */
    public static class PayResult {
        public String orderNo;
        public int status;
        public BigDecimal paidAmount;
        public BigDecimal balanceAfter;

        public PayResult(String orderNo, int status, BigDecimal paidAmount, BigDecimal balanceAfter) {
            this.orderNo = orderNo;
            this.status = status;
            this.paidAmount = paidAmount;
            this.balanceAfter = balanceAfter;
        }
    }
}