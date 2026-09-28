package com.xinguo.seckill.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.common.constant.RedisKeyConstant;
import com.xinguo.seckill.common.dto.OrderMessageDTO;
import com.xinguo.seckill.common.exception.BusinessException;
import com.xinguo.seckill.order.client.InternalClient;
import com.xinguo.seckill.order.dto.SeckillDTO.SeckillResult;
import com.xinguo.seckill.order.mapper.SeckillLogMapper;
import com.xinguo.seckill.order.mq.OrderProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * SeckillOrderService 行为测试（mock redis/生产者）：
 * 1) 时间门禁：缓存缺失 -> 4003
 * 2) 未开始/已结束 -> 4003
 * 3) Lua 返回 -1（库存不足）-> 4001
 * 4) Lua 返回 -2（重复购买）-> 4002
 * 5) 成功：生成 QUEUED + 发 MQ + 写 REQUEST/STOCK_DEDUCT/MQ_SEND 日志
 */
class SeckillOrderServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private StockDeductLuaService luaService;
    private OrderProducer orderProducer;
    private SeckillLogMapper logMapper;
    private InternalClient internalClient;
    private SeckillOrderService service;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        luaService = mock(StockDeductLuaService.class);
        orderProducer = mock(OrderProducer.class);
        logMapper = mock(SeckillLogMapper.class);
        internalClient = mock(InternalClient.class);
        // create 会写 3 条日志，统一 mock 成功
        when(logMapper.insert(any())).thenReturn(1);
        service = new SeckillOrderService(redisTemplate, MAPPER, luaService, orderProducer, logMapper, internalClient);
    }

    /** 构造未开始（start=+5min, end=+15min）的活动缓存 JSON */
    private String activityJson(LocalDateTime start, LocalDateTime end) {
        String fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(start);
        String fmtEnd = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(end);
        return "{\"activityId\":1001,\"goodsId\":2001,\"goodsName\":\"测试商品\","
                + "\"seckillPrice\":99.00,\"startTime\":\"" + fmt
                + "\",\"endTime\":\"" + fmtEnd + "\",\"limitPerUser\":1}";
    }

    private LocalDateTime now() {
        return LocalDateTime.now();
    }

    @Test
    void cacheMissingAndGoodsUnreachableThrows4003() {
        when(valueOps.get(RedisKeyConstant.activityKey(1001L))).thenReturn(null);
        when(internalClient.getActivity(1001L))
                .thenReturn(new InternalClient.InternalResult(5000, "goods down", null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.seckill(1001L, 1L));
        assertEquals(4003, ex.getCode());
    }

    @Test
    void cacheMissingRefillsFromGoodsThenSucceeds() throws Exception {
        // 活动缓存过期（null），但 goods 内部接口可回填（需求 3.2 修复：压测暴露的误报 4003）
        when(valueOps.get(RedisKeyConstant.activityKey(1001L)))
                .thenReturn(null, activityJson(now().minusMinutes(5), now().plusMinutes(15)));
        when(internalClient.getActivity(1001L)).thenReturn(new InternalClient.InternalResult(
                0, "success",
                MAPPER.readTree(activityJson(now().minusMinutes(5), now().plusMinutes(15)))));
        when(luaService.deductStock(eq(1001L), eq(1L), anyLong())).thenReturn(1L);
        when(orderProducer.send(any(OrderMessageDTO.class))).thenReturn(true);

        SeckillResult result = service.seckill(1001L, 1L);

        assertEquals("QUEUED", result.status);
        // 已拉取 goods 并回填缓存（set(K,V,Duration) 回填）
        verify(valueOps, times(1)).set(eq(RedisKeyConstant.activityKey(1001L)), anyString(), any());
    }

    @Test
    void notStartedThrows4003() {
        // 已缓存但时间未到
        when(valueOps.get(RedisKeyConstant.activityKey(1001L)))
                .thenReturn(activityJson(now().plusMinutes(5), now().plusMinutes(15)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.seckill(1001L, 1L));
        assertEquals(4003, ex.getCode());
        verify(luaService, times(0)).deductStock(eq(1001L), eq(1L), anyLong());
    }

    @Test
    void stockEmptyThrows4001() {
        when(valueOps.get(RedisKeyConstant.activityKey(1001L)))
                .thenReturn(activityJson(now().minusMinutes(5), now().plusMinutes(15)));
        when(luaService.deductStock(eq(1001L), eq(1L), anyLong())).thenReturn(-1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.seckill(1001L, 1L));
        assertEquals(4001, ex.getCode());
    }

    @Test
    void duplicateThrows4002() {
        when(valueOps.get(RedisKeyConstant.activityKey(1001L)))
                .thenReturn(activityJson(now().minusMinutes(5), now().plusMinutes(15)));
        when(luaService.deductStock(eq(1001L), eq(1L), anyLong())).thenReturn(-2L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.seckill(1001L, 1L));
        assertEquals(4002, ex.getCode());
    }

    @Test
    void successReturnsQueuedAndSendsMq() {
        when(valueOps.get(RedisKeyConstant.activityKey(1001L)))
                .thenReturn(activityJson(now().minusMinutes(5), now().plusMinutes(15)));
        when(luaService.deductStock(eq(1001L), eq(1L), anyLong())).thenReturn(1L);
        when(orderProducer.send(any(OrderMessageDTO.class))).thenReturn(true);

        SeckillResult result = service.seckill(1001L, 1L);

        assertNotNull(result.orderNo);
        assertEquals(true, result.orderNo.matches("^SK\\d{17}$"), "订单号格式应为 ^SK\\d{17}$");
        assertEquals("QUEUED", result.status);

        // 校验 MQ 消息字段
        ArgumentCaptor<OrderMessageDTO> captor = ArgumentCaptor.forClass(OrderMessageDTO.class);
        verify(orderProducer, times(1)).send(captor.capture());
        OrderMessageDTO msg = captor.getValue();
        assertEquals(result.orderNo, msg.getOrderNo());
        assertEquals(1001L, msg.getActivityId());
        assertEquals(1L, msg.getUserId());
        assertEquals(2001L, msg.getGoodsId());
        assertEquals(0, msg.getPrice().compareTo(BigDecimal.valueOf(99.00)));

        // 日志：REQUEST + STOCK_DEDUCT + MQ_SEND
        verify(logMapper, times(3)).insert(any());
    }

    @Test
    void wrongLuaResultThrows5000() {
        when(valueOps.get(RedisKeyConstant.activityKey(1001L)))
                .thenReturn(activityJson(now().minusMinutes(5), now().plusMinutes(15)));
        when(luaService.deductStock(eq(1001L), eq(1L), anyLong())).thenReturn(99L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.seckill(1001L, 1L));
        assertEquals(5000, ex.getCode());
    }

    @Test
    void mqSendFailureRollsBackRedisAndThrows5000() {
        when(valueOps.get(RedisKeyConstant.activityKey(1001L)))
                .thenReturn(activityJson(now().minusMinutes(5), now().plusMinutes(15)));
        when(luaService.deductStock(eq(1001L), eq(1L), anyLong())).thenReturn(1L);
        // MQ 发送失败
        when(orderProducer.send(any(OrderMessageDTO.class))).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.seckill(1001L, 1L));
        assertEquals(5000, ex.getCode(), "MQ 失败应抛 5000，不返回 QUEUED");

        // 回滚 Redis：库存 +1 回补、用户标记删除（防"Lua 已扣未落库"泄漏）
        verify(valueOps).increment(RedisKeyConstant.stockKey(1001L));
        verify(redisTemplate).delete(RedisKeyConstant.userKey(1001L, 1L));
    }

    @Test
    void mqProducerDownReturnsFalseAndServiceRollsBack() {
        // producer 未启动场景：send 返回 false（由 mock 模拟），同走回滚
        when(valueOps.get(RedisKeyConstant.activityKey(1001L)))
                .thenReturn(activityJson(now().minusMinutes(5), now().plusMinutes(15)));
        when(luaService.deductStock(eq(1001L), eq(1L), anyLong())).thenReturn(1L);
        when(orderProducer.send(any(OrderMessageDTO.class))).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.seckill(1001L, 1L));
        assertEquals(5000, ex.getCode());
        verify(valueOps).increment(RedisKeyConstant.stockKey(1001L));
        verify(redisTemplate).delete(RedisKeyConstant.userKey(1001L, 1L));
    }
}