package com.xinguo.seckill.order.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.common.constant.RedisKeyConstant;
import com.xinguo.seckill.common.dto.OrderMessageDTO;
import com.xinguo.seckill.common.exception.BusinessException;
import com.xinguo.seckill.order.client.InternalClient;
import com.xinguo.seckill.order.client.InternalClient.InternalResult;
import com.xinguo.seckill.order.entity.SeckillLog;
import com.xinguo.seckill.order.entity.SeckillOrder;
import com.xinguo.seckill.order.mapper.SeckillLogMapper;
import com.xinguo.seckill.order.mapper.SeckillOrderMapper;
import com.xinguo.seckill.order.mq.OrderTimeoutProducer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderService 行为测试（3.2 落库 / 3.3 支付 / 3.4 取消）。
 * mapper 用 mock 桩（FOR UPDATE 查询与条件更新由真实 SQL/集成测试覆盖）。
 */
class OrderServiceTest {

    private SeckillOrderMapper orderMapper;
    private SeckillLogMapper logMapper;
    private InternalClient internalClient;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private OrderTimeoutProducer timeoutProducer;
    private OrderService service;

    private SeckillOrder order(String orderNo, Long user, int status) {
        SeckillOrder o = new SeckillOrder();
        o.setOrderNo(orderNo);
        o.setActivityId(1001L);
        o.setUserId(user);
        o.setGoodsId(2001L);
        o.setPrice(new BigDecimal("99.00"));
        o.setStatus(status);
        o.setCreatedAt(LocalDateTime.now());
        o.setPayDeadline(LocalDateTime.now().plusMinutes(30));
        return o;
    }

    /**
     * 反射替换 private 参数 payTimeoutMinutes（@Value 在单测中不注入），默认 30
     */
    private void setPayTimeout(OrderService svc, int mins) throws Exception {
        var f = OrderService.class.getDeclaredField("payTimeoutMinutes");
        f.setAccessible(true);
        f.setInt(svc, mins);
    }

    /**
     * 离线单测中 MyBatis-Plus 的 Lambda 解析依赖 TableInfo 缓存，
     * 这里显式初始化实体表信息，否则 update/select 构造 Lambda 时抛
     * "can not find lambda cache for this entity"。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SeckillOrder.class);
        TableInfoHelper.initTableInfo(assistant, SeckillLog.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        orderMapper = mock(SeckillOrderMapper.class);
        logMapper = mock(SeckillLogMapper.class);
        internalClient = mock(InternalClient.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        timeoutProducer = mock(OrderTimeoutProducer.class);
        service = new OrderService(orderMapper, logMapper, internalClient, redisTemplate, timeoutProducer);
        setPayTimeout(service, 30);
        when(logMapper.insert(any())).thenReturn(1);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(redisTemplate.delete(anyString())).thenReturn(true);
        when(logMapper.selectOne(any())).thenReturn(null);
    }

    // ---------- 3.2 落库 ----------

    @Test
    void processMessageDupIgnored() {
        when(orderMapper.selectOne(any())).thenReturn(order("SK1", 1L, OrderService.ST_WAIT_PAY));

        OrderMessageDTO msg = new OrderMessageDTO();
        msg.setOrderNo("SK1");
        msg.setActivityId(1001L);
        msg.setUserId(1L);
        msg.setGoodsId(2001L);
        msg.setPrice(new BigDecimal("99.00"));
        service.processSeckillMessage(msg);

        verify(orderMapper, never()).insert(any());
        verify(internalClient, never()).deductStock(any(), anyInt());
        verify(timeoutProducer, never()).sendDelayed(anyString(), anyInt());
    }

    @Test
    void processMessageUkConflictTreatedAsHandled() {
        // uk_user_active 唯一键冲突（同用户同活动已有非取消态订单，如 Redis 标记丢失后的重发消息）：
        // 应吞掉而非抛异常，避免 consumer RECONSUME_LATER 无限重试（T18 压测暴露 '1-1001' 死循环）
        OrderMessageDTO msg = new OrderMessageDTO();
        msg.setOrderNo("SK1");
        msg.setActivityId(1001L);
        msg.setUserId(1L);
        msg.setGoodsId(2001L);
        msg.setPrice(new BigDecimal("99.00"));
        when(orderMapper.insert(any())).thenThrow(new DuplicateKeyException("dup uk"));

        service.processSeckillMessage(msg); // 不应抛出

        verify(orderMapper, times(1)).insert(any());
        verify(internalClient, never()).deductStock(any(), anyInt());
        verify(timeoutProducer, never()).sendDelayed(anyString(), anyInt());
    }

    @Test
    void processMessageInsertsDeductsAndDelays() throws Exception {
        OrderMessageDTO msg = new OrderMessageDTO();
        msg.setOrderNo("SK1");
        msg.setActivityId(1001L);
        msg.setUserId(1L);
        msg.setGoodsId(2001L);
        msg.setPrice(new BigDecimal("99.00"));
        when(orderMapper.insert(any())).thenReturn(1);
        when(internalClient.deductStock(2001L, 1)).thenReturn(new InternalResult(0, "ok", null));
        when(timeoutProducer.sendDelayed("SK1", 30)).thenReturn(true);

        service.processSeckillMessage(msg);

        verify(orderMapper, times(1)).insert(any());
        verify(internalClient, times(1)).deductStock(2001L, 1);
        verify(timeoutProducer, times(1)).sendDelayed("SK1", 30);
    }

    @Test
    void processMessageDelayedSendFailLogsMarker() {
        // 延迟消息发送失败：不抛出，落 ORDER_TIMEOUT_SEND_FAIL 待补标记，靠 scheduler 兜底（review P1-1）
        OrderMessageDTO msg = new OrderMessageDTO();
        msg.setOrderNo("SK1");
        msg.setActivityId(1001L);
        msg.setUserId(1L);
        msg.setGoodsId(2001L);
        msg.setPrice(new BigDecimal("99.00"));
        when(orderMapper.insert(any())).thenReturn(1);
        when(internalClient.deductStock(2001L, 1)).thenReturn(new InternalResult(0, "ok", null));
        when(timeoutProducer.sendDelayed("SK1", 30)).thenReturn(false);

        service.processSeckillMessage(msg);

        // ORDER_CREATE + ORDER_TIMEOUT_SEND_FAIL 两条日志
        verify(logMapper, times(2)).insert(any());
        verify(timeoutProducer, times(1)).sendDelayed("SK1", 30);
    }

    @Test
    void processMessageStockFailCancelsOrder() {
        OrderMessageDTO msg = new OrderMessageDTO();
        msg.setOrderNo("SK1");
        msg.setActivityId(1001L);
        msg.setUserId(1L);
        msg.setGoodsId(2001L);
        msg.setPrice(new BigDecimal("99.00"));
        when(internalClient.deductStock(2001L, 1)).thenReturn(new InternalResult(4001, "不足", null));

        service.processSeckillMessage(msg);

        // ORDER_CREATE + ORDER_CANCEL 两条日志
        verify(logMapper, times(2)).insert(any());
        verify(orderMapper, times(1)).update(eq(null), any(LambdaUpdateWrapper.class));
        verify(timeoutProducer, never()).sendDelayed(anyString(), anyInt());
    }

    // ---------- 3.3 支付 ----------

    @Test
    void paySuccess() {
        when(orderMapper.selectByOrderNoForUpdate("SK1")).thenReturn(order("SK1", 1L, OrderService.ST_WAIT_PAY));
        when(internalClient.deductWallet(1L, new BigDecimal("99.00"), "SK1"))
                .thenReturn(new InternalResult(0, "ok", new ObjectMapper().valueToTree(new BigDecimal("1.00"))));
        when(orderMapper.update(eq(null), any(LambdaUpdateWrapper.class))).thenReturn(1);

        OrderService.PayResult r = service.pay("SK1", 1L);

        assertEquals(OrderService.ST_PAID, r.status);
        verify(internalClient, never()).refundWallet(any(), any(), anyString());
    }

    @Test
    void payNotOwnerThrows4005() {
        when(orderMapper.selectByOrderNoForUpdate("SK1")).thenReturn(order("SK1", 2L, OrderService.ST_WAIT_PAY));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.pay("SK1", 1L));
        assertEquals(4005, ex.getCode());
    }

    @Test
    void payAlreadyPaidThrows4006() {
        when(orderMapper.selectByOrderNoForUpdate("SK1")).thenReturn(order("SK1", 1L, OrderService.ST_PAID));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.pay("SK1", 1L));
        assertEquals(4006, ex.getCode());
    }

    @Test
    void payInsufficientWalletThrows4004() {
        when(orderMapper.selectByOrderNoForUpdate("SK1")).thenReturn(order("SK1", 1L, OrderService.ST_WAIT_PAY));
        when(internalClient.deductWallet(1L, new BigDecimal("99.00"), "SK1"))
                .thenReturn(new InternalResult(4004, "余额不足", null));

        assertThrows(BusinessException.class, () -> service.pay("SK1", 1L));
        verify(internalClient, never()).refundWallet(any(), any(), anyString());
    }

    @Test
    void payWalletDownThrows5000() {
        // 内部服务不可用被 InternalClient 兜底为 5000，应透传 5000 而非误报 4004（review P1-4）
        when(orderMapper.selectByOrderNoForUpdate("SK1")).thenReturn(order("SK1", 1L, OrderService.ST_WAIT_PAY));
        when(internalClient.deductWallet(1L, new BigDecimal("99.00"), "SK1"))
                .thenReturn(new InternalResult(5000, "内部服务调用失败", null));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.pay("SK1", 1L));
        assertEquals(5000, ex.getCode());
        verify(internalClient, never()).refundWallet(any(), any(), anyString());
    }

    @Test
    void payLocalUpdateFailRollsBackRefund() {
        when(orderMapper.selectByOrderNoForUpdate("SK1")).thenReturn(order("SK1", 1L, OrderService.ST_WAIT_PAY));
        when(internalClient.deductWallet(1L, new BigDecimal("99.00"), "SK1"))
                .thenReturn(new InternalResult(0, "ok", new ObjectMapper().valueToTree(new BigDecimal("1.00"))));
        when(orderMapper.update(eq(null), any(LambdaUpdateWrapper.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.pay("SK1", 1L));
        assertEquals(4006, ex.getCode());
        verify(internalClient, times(1)).refundWallet(1L, new BigDecimal("99.00"), "SK1");
    }

    @Test
    void payLocalUpdateExceptionCompensatesRefund() {
        // 本地 update 抛异常（DB 抖动）：事务回滚但外部扣款已提交 -> 必须补偿退款（review P1-3）
        when(orderMapper.selectByOrderNoForUpdate("SK1")).thenReturn(order("SK1", 1L, OrderService.ST_WAIT_PAY));
        when(internalClient.deductWallet(1L, new BigDecimal("99.00"), "SK1"))
                .thenReturn(new InternalResult(0, "ok", new ObjectMapper().valueToTree(new BigDecimal("1.00"))));
        when(orderMapper.update(eq(null), any(LambdaUpdateWrapper.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> service.pay("SK1", 1L));
        verify(internalClient, times(1)).refundWallet(1L, new BigDecimal("99.00"), "SK1");
    }

    // ---------- 3.4 取消 ----------

    @Test
    void cancelUserActive() {
        SeckillOrder o = order("SK1", 1L, OrderService.ST_WAIT_PAY);
        when(orderMapper.selectByOrderNoForUpdate("SK1")).thenReturn(o);
        when(orderMapper.update(eq(null), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(internalClient.rollbackStock(2001L, 1)).thenReturn(new InternalResult(0, "ok", null));

        service.cancel("SK1", 1L, OrderService.ST_USER_CANCELLED);

        verify(redisTemplate).opsForValue();
        verify(internalClient, times(1)).rollbackStock(2001L, 1);
    }

    @Test
    void cancelAlreadyPaidThrows4006() {
        when(orderMapper.selectByOrderNoForUpdate("SK1")).thenReturn(order("SK1", 1L, OrderService.ST_PAID));
        assertThrows(BusinessException.class, () -> service.cancel("SK1", 1L, OrderService.ST_USER_CANCELLED));
    }

    @Test
    void cancelByTimeoutFromScheduler() {
        SeckillOrder o = order("SK1", 1L, OrderService.ST_WAIT_PAY);
        when(orderMapper.selectByOrderNoForUpdate("SK1")).thenReturn(o);
        when(orderMapper.update(eq(null), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(internalClient.rollbackStock(2001L, 1)).thenReturn(new InternalResult(0, "ok", null));
        service.cancel("SK1", null, OrderService.ST_TIMEOUT_CANCELLED);
        verify(internalClient, times(1)).rollbackStock(2001L, 1);
    }

    // ---------- T10 待补标记补偿 ----------

    @Test
    void queryTimeoutSendFailOrdersReturnsWaitPayUnexpiredOnly() {
        // 仅"延迟消息发送失败"标记、且未重发过(RESEND 排除)、仍待支付、未过期的订单应被返回
        // selectObjs 调用两次：先取 SEND_FAIL 名单 {SK1,SK2}，再取 RESEND 名单 {SK1} -> 剩 SK2（review P1-1）
        when(logMapper.selectObjs(any())).thenReturn(List.of("SK1", "SK2")).thenReturn(List.of("SK1"));
        when(orderMapper.selectList(any())).thenReturn(List.of(order("SK2", 1L, OrderService.ST_WAIT_PAY)));

        List<String> nos = service.queryTimeoutSendFailOrders();

        assertEquals(List.of("SK2"), nos);
    }

    @Test
    void resendDelayedSuccessOnWaitPay() {
        when(orderMapper.selectOne(any())).thenReturn(order("SK1", 1L, OrderService.ST_WAIT_PAY));
        when(timeoutProducer.sendDelayed("SK1", 30)).thenReturn(true);

        assertTrue(service.resendDelayed("SK1"));

        verify(timeoutProducer, times(1)).sendDelayed("SK1", 30);
        verify(logMapper, times(1)).insert(any()); // ORDER_TIMEOUT_RESEND 一条
    }

    @Test
    void resendDelayedRejectsPaidOrder() {
        when(orderMapper.selectOne(any())).thenReturn(order("SK1", 1L, OrderService.ST_PAID));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.resendDelayed("SK1"));
        assertEquals(4006, ex.getCode());

        verify(timeoutProducer, never()).sendDelayed(anyString(), anyInt());
        verify(logMapper, never()).insert(any());
    }

    @Test
    void resendDelayedThrows4005WhenMissing() {
        when(orderMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.resendDelayed("SK1"));
        assertEquals(4005, ex.getCode());

        verify(timeoutProducer, never()).sendDelayed(anyString(), anyInt());
    }
}