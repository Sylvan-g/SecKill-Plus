package com.xinguo.seckill.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.common.constant.RedisKeyConstant;
import com.xinguo.seckill.common.dto.OrderMessageDTO;
import com.xinguo.seckill.common.exception.BusinessException;
import com.xinguo.seckill.common.util.OrderNoGenerator;
import com.xinguo.seckill.order.client.InternalClient;
import com.xinguo.seckill.order.dto.SeckillDTO.ActivityInfo;
import com.xinguo.seckill.order.dto.SeckillDTO.SeckillResult;
import com.xinguo.seckill.order.entity.SeckillLog;
import com.xinguo.seckill.order.mapper.SeckillLogMapper;
import com.xinguo.seckill.order.mq.OrderProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 秒杀下单服务（需求文档 8.3 / 3.2）：
 * 时间门禁(从 Redis 活动缓存，未命中回填自 goods) -> Lua 原子扣减+用户标记(10.1) -> 生成订单号 -> 写日志
 * (REQUEST/STOCK_DEDUCT) -> 发 MQ -> 返回 QUEUED。
 *
 * 注意：本服务返回 QUEUED，落库由 MQ 消费者异步完成（R3）。MQ 不可用时订单不会落库，
 * 仅剩 Redis 标记与日志——此约束记录在案，后续消费/补偿机制兜底。
 */
@Service
public class SeckillOrderService {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderService.class);

    /** 回填缓存 TTL 与 goods 详情缓存同区间（600~900s 随机防雪崩） */
    private static final long CACHE_TTL_MIN_SEC = 600;
    private static final long CACHE_TTL_MAX_SEC = 900;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final StockDeductLuaService luaService;
    private final OrderProducer orderProducer;
    private final SeckillLogMapper logMapper;
    private final InternalClient internalClient;

    public SeckillOrderService(StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper,
                               StockDeductLuaService luaService,
                               OrderProducer orderProducer,
                               SeckillLogMapper logMapper,
                               InternalClient internalClient) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.luaService = luaService;
        this.orderProducer = orderProducer;
        this.logMapper = logMapper;
        this.internalClient = internalClient;
    }

    /** 8.3 秒杀下单；按文档错误码：4001 库存不足 / 4002 重复购买 / 4003 未开始或已结束 */
    public SeckillResult seckill(Long activityId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        logInfo(null, activityId, userId, "REQUEST", "seckill do activity=" + activityId);

        // ① 时间门禁：从 Redis 活动缓存读取起止时间（R1），未命中回填自 goods 内部接口（需求 3.2）
        ActivityInfo activity = readActivity(activityId);
        if (activity == null || activity.startTime == null || activity.endTime == null) {
            throw new BusinessException(4003, "活动未开始或已结束");
        }
        if (now.isBefore(activity.startTime) || !now.isBefore(activity.endTime)) {
            throw new BusinessException(4003, "活动未开始或已结束");
        }

        // ② Lua 原子扣减 Redis 库存 + 用户标记（10.1）
        //    标记 TTL = 距活动结束剩余秒数 + 1 天（7 节 TTL 约定）
        long ttlSeconds = Duration.between(now, activity.endTime).getSeconds() + 86400L;
        long luaResult = luaService.deductStock(activityId, userId, ttlSeconds);
        if (luaResult == -1) {
            throw new BusinessException(4001, "手慢了，库存不足");
        }
        if (luaResult == -2) {
            throw new BusinessException(4002, "您已购买过该商品");
        }
        // luaResult 理论上仅 1/-1/-2，其余视为失败
        if (luaResult != 1) {
            throw new BusinessException(5000, "库存扣减异常");
        }
        logInfo(null, activityId, userId, "STOCK_DEDUCT", "lua ok");

        // ③ 生成订单号 + 发 MQ（落库异步）
        String orderNo = OrderNoGenerator.generate();
        OrderMessageDTO msg = new OrderMessageDTO();
        msg.setOrderNo(orderNo);
        msg.setActivityId(activityId);
        msg.setUserId(userId);
        msg.setGoodsId(activity.goodsId);
        msg.setPrice(activity.seckillPrice);
        boolean sent = orderProducer.send(msg);
        if (!sent) {
            // 回滚 Redis：库存 +1 回补、删除用户标记，释放库存与用户（防"Lua 已扣 MQ 未达"泄漏）
            rollbackStock(activityId, userId, orderNo);
            throw new BusinessException(5000, "排队失败，请稍后重试");
        }
        logInfo(orderNo, activityId, userId, "MQ_SEND", "topic=seckill-order-topic");

        log.info("[seckill] orderNo={} queued activity={} user={}", orderNo, activityId, userId);
        return new SeckillResult(orderNo, "QUEUED");
    }

    /** MQ 发送失败后的 Redis 回滚：库存回补 + 删除用户标记，保证一致性（review P1） */
    private void rollbackStock(Long activityId, Long userId, String orderNo) {
        try {
            redisTemplate.opsForValue().increment(RedisKeyConstant.stockKey(activityId));
            redisTemplate.delete(RedisKeyConstant.userKey(activityId, userId));
            logInfo(orderNo, activityId, userId, "ORDER_CANCEL", "mq send failed, rolled back redis");
            log.warn("[seckill] mq send failed, rolled back orderNo={} activity={} user={}", orderNo, activityId, userId);
        } catch (Exception e) {
            log.error("[seckill] rollback redis failed orderNo={}", orderNo, e);
        }
    }

    /**
     * 读取 Redis 活动缓存并反序列化为 ActivityInfo。
     * 缓存未命中时调 goods 内部接口 /internal/activity/{id} 回填缓存（需求 3.2，
     * 修复压测暴露的"活动进行中却因缓存过期误报 4003"）；goods 也不可识别则返回 null（视为不可下单）。
     */
    private ActivityInfo readActivity(Long activityId) {
        String cacheJson = redisTemplate.opsForValue().get(RedisKeyConstant.activityKey(activityId));
        if (cacheJson == null) {
            cacheJson = refillFromGoods(activityId);
            if (cacheJson == null) {
                log.warn("[seckill] activity cache missing and goods unreachable activity={}", activityId);
                return null;
            }
        }
        try {
            JsonNode node = objectMapper.readTree(cacheJson);
            ActivityInfo info = new ActivityInfo();
            info.activityId = node.path("activityId").asLong();
            info.goodsId = node.path("goodsId").asLong(0);
            info.goodsName = node.path("goodsName").asText();
            info.seckillPrice = node.path("seckillPrice").decimalValue();
            info.startTime = parseTime(node.path("startTime").asText());
            info.endTime = parseTime(node.path("endTime").asText());
            info.limitPerUser = node.path("limitPerUser").asInt(1);
            return info;
        } catch (Exception e) {
            log.warn("[seckill] parse activity cache failed activity={}", activityId, e);
            return null;
        }
    }

    /**
     * 活动缓存缺失时从 goods 内部接口拉取详情并回填 Redis（TTL 与 goods 详情缓存同区间随机）。
     * 返回 goods 序列化的活动 JSON；调用失败/活动不存在返回 null。
     */
    private String refillFromGoods(Long activityId) {
        try {
            InternalClient.InternalResult res = internalClient.getActivity(activityId);
            if (res.code != 0 || res.data == null || !res.data.isObject()) {
                log.warn("[seckill] goods activity unavailable activity={} code={}", activityId, res.code);
                return null;
            }
            String json = objectMapper.writeValueAsString(res.data);
            long ttl = ThreadLocalRandom.current().nextLong(CACHE_TTL_MIN_SEC, CACHE_TTL_MAX_SEC + 1);
            redisTemplate.opsForValue().set(RedisKeyConstant.activityKey(activityId), json, Duration.ofSeconds(ttl));
            return json;
        } catch (Exception e) {
            log.warn("[seckill] refill activity cache from goods failed activity={}", activityId, e);
            return null;
        }
    }

    private LocalDateTime parseTime(String text) {
        return text == null || text.isEmpty() ? null : LocalDateTime.parse(text);
    }

    private void logInfo(String orderNo, Long activityId, Long userId, String action, String detail) {
        SeckillLog logRow = new SeckillLog();
        logRow.setOrderNo(orderNo);
        logRow.setActivityId(activityId);
        logRow.setUserId(userId);
        logRow.setAction(action);
        logRow.setDetail(detail);
        try {
            logMapper.insert(logRow);
        } catch (Exception e) {
            log.warn("[seckill] write log failed action={} detail={}", action, detail, e);
        }
    }
}