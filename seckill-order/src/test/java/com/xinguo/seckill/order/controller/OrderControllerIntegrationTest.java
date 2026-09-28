package com.xinguo.seckill.order.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinguo.seckill.common.constant.RedisKeyConstant;
import com.xinguo.seckill.order.client.InternalClient;
import com.xinguo.seckill.order.entity.SeckillLog;
import com.xinguo.seckill.order.entity.SeckillOrder;
import com.xinguo.seckill.order.mapper.SeckillLogMapper;
import com.xinguo.seckill.order.mapper.SeckillOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单支付/取消/查询集成测试（真实 MySQL seckill_order + Redis，Mock 掉跨服务 InternalClient）：
 * - 未登录 -> 401
 * - 支付：成功（两阶段扣款+置已支付）、非本人 4005、重复支付 4006、过期 4007、钱包不足 4004
 * - 主动取消：回滚 Redis 库存 + 删用户标记 + 回补 goods 库存 + ORDER_CANCEL 日志；已支付 4006
 * - 状态查询：WAIT_PAY / PAID / CANCELLED / QUEUED（Redis 标记兜底） / UNKNOWN
 * - 列表：只返回本人订单
 * - 内部接口：timeout-list 扫描 + timeout-cancel 超时取消（status=2）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
class OrderControllerIntegrationTest {

    private static final long USER_ID = 11L;
    private static final long OTHER_USER_ID = 22L;
    private static final long ACTIVITY_ID = 1001L;
    private static final long ACTIVITY_ID_2 = 1002L;
    private static final long GOODS_ID = 2001L;
    private static final BigDecimal PRICE = new BigDecimal("99.00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SeckillOrderMapper orderMapper;

    @Autowired
    private SeckillLogMapper logMapper;

    /** Mock 掉 order 对 user/goods 的内部 HTTP 调用：集成测试不依赖两个服务在跑 */
    @MockBean
    private InternalClient internalClient;

    private long seq;

    @BeforeEach
    void setUp() {
        seq = System.currentTimeMillis();
        // 默认全部内部调用成功；data 为扣款后余额 50.00
        when(internalClient.deductStock(anyLong(), anyInt())).thenReturn(ok(null));
        when(internalClient.rollbackStock(anyLong(), anyInt())).thenReturn(ok(null));
        when(internalClient.deductWallet(anyLong(), any(), anyString())).thenReturn(ok(balanceNode()));
        when(internalClient.refundWallet(anyLong(), any(), anyString())).thenReturn(ok(null));
    }

    @AfterEach
    void tearDown() {
        orderMapper.delete(new LambdaQueryWrapper<SeckillOrder>()
                .in(SeckillOrder::getUserId, USER_ID, OTHER_USER_ID));
        logMapper.delete(new LambdaQueryWrapper<SeckillLog>()
                .in(SeckillLog::getUserId, USER_ID, OTHER_USER_ID));
        redisTemplate.delete(RedisKeyConstant.userKey(ACTIVITY_ID, USER_ID));
        redisTemplate.delete(RedisKeyConstant.userKey(ACTIVITY_ID_2, USER_ID));
        redisTemplate.delete(RedisKeyConstant.userKey(ACTIVITY_ID, OTHER_USER_ID));
    }

    private String nextOrderNo() {
        return "SK" + (seq++);
    }

    private InternalClient.InternalResult ok(JsonNode data) {
        return new InternalClient.InternalResult(0, "ok", data);
    }

    private JsonNode balanceNode() {
        return objectMapper.getNodeFactory().numberNode(new BigDecimal("50.00"));
    }

    private SeckillOrder insertOrder(String orderNo, long userId, long activityId, int status, LocalDateTime deadline) {
        SeckillOrder o = new SeckillOrder();
        o.setOrderNo(orderNo);
        o.setActivityId(activityId);
        o.setUserId(userId);
        o.setGoodsId(GOODS_ID);
        o.setGoodsName("集成测试商品");
        o.setPrice(PRICE);
        o.setStatus(status);
        o.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        o.setPayDeadline(deadline);
        orderMapper.insert(o);
        return o;
    }

    private String login(long userId) {
        StpUtil.login(userId);
        return StpUtil.getTokenValue();
    }

    // ---------- 鉴权 ----------

    @Test
    void notLoginReturns401() throws Exception {
        mockMvc.perform(get("/api/order/list"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    // ---------- 支付 ----------

    @Test
    void paySuccessSetsPaidAndDeductsWallet() throws Exception {
        String orderNo = nextOrderNo();
        insertOrder(orderNo, USER_ID, ACTIVITY_ID, 0, LocalDateTime.now().plusMinutes(30));
        String token = login(USER_ID);

        mockMvc.perform(post("/api/order/pay/" + orderNo).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.orderNo").value(orderNo))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.paidAmount").value(99.00))
                .andExpect(jsonPath("$.data.balanceAfter").value(50.00));

        // 本地订单已置已支付
        SeckillOrder saved = orderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getOrderNo, orderNo));
        assertEquals(1, saved.getStatus());
        assertEquals(1, saved.getPayChannel());
        assertNotNull(saved.getPaidAt());

        // 钱包扣款(阶段一)恰好一次；余额解析来自返回 data
        verify(internalClient).deductWallet(eq(USER_ID), eq(PRICE), eq(orderNo));
        verify(internalClient, never()).refundWallet(anyLong(), any(), anyString());

        // WALLET_PAY 日志
        Long payLog = logMapper.selectCount(new LambdaQueryWrapper<SeckillLog>()
                .eq(SeckillLog::getOrderNo, orderNo).eq(SeckillLog::getAction, "WALLET_PAY"));
        assertEquals(1L, payLog);
    }

    @Test
    void payNotOwnerReturns4005() throws Exception {
        String orderNo = nextOrderNo();
        insertOrder(orderNo, OTHER_USER_ID, ACTIVITY_ID, 0, LocalDateTime.now().plusMinutes(30));
        String token = login(USER_ID);

        mockMvc.perform(post("/api/order/pay/" + orderNo).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4005));
        verify(internalClient, never()).deductWallet(anyLong(), any(), anyString());
    }

    @Test
    void payAlreadyPaidReturns4006() throws Exception {
        String orderNo = nextOrderNo();
        insertOrder(orderNo, USER_ID, ACTIVITY_ID, 1, LocalDateTime.now().plusMinutes(30));
        String token = login(USER_ID);

        mockMvc.perform(post("/api/order/pay/" + orderNo).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4006));
        verify(internalClient, never()).deductWallet(anyLong(), any(), anyString());
    }

    @Test
    void payExpiredReturns4007() throws Exception {
        String orderNo = nextOrderNo();
        insertOrder(orderNo, USER_ID, ACTIVITY_ID, 0, LocalDateTime.now().minusMinutes(1));
        String token = login(USER_ID);

        mockMvc.perform(post("/api/order/pay/" + orderNo).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4007));
        verify(internalClient, never()).deductWallet(anyLong(), any(), anyString());
    }

    @Test
    void payWalletInsufficientReturns4004AndStaysWaitPay() throws Exception {
        String orderNo = nextOrderNo();
        insertOrder(orderNo, USER_ID, ACTIVITY_ID, 0, LocalDateTime.now().plusMinutes(30));
        // 阶段一扣款失败（余额不足）
        when(internalClient.deductWallet(anyLong(), any(), anyString()))
                .thenReturn(new InternalClient.InternalResult(4004, "insufficient", null));
        String token = login(USER_ID);

        mockMvc.perform(post("/api/order/pay/" + orderNo).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4004));

        // 本地订单仍待支付，不落已支付
        SeckillOrder saved = orderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getOrderNo, orderNo));
        assertEquals(0, saved.getStatus());
        verify(internalClient, never()).refundWallet(anyLong(), any(), anyString());
    }

    // ---------- 主动取消 ----------

    @Test
    void cancelByUserRollsBackRedisAndGoods() throws Exception {
        String orderNo = nextOrderNo();
        insertOrder(orderNo, USER_ID, ACTIVITY_ID, 0, LocalDateTime.now().plusMinutes(30));
        redisTemplate.opsForValue().set(RedisKeyConstant.stockKey(ACTIVITY_ID), "10");
        redisTemplate.opsForValue().set(RedisKeyConstant.userKey(ACTIVITY_ID, USER_ID), "1");
        String token = login(USER_ID);

        mockMvc.perform(post("/api/order/cancel/" + orderNo).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 本地状态 3 + 回滚 Redis 库存(10->11) + 删用户标记 + 回补 goods 库存 + ORDER_CANCEL 日志
        SeckillOrder saved = orderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getOrderNo, orderNo));
        assertEquals(3, saved.getStatus());
        assertEquals("11", redisTemplate.opsForValue().get(RedisKeyConstant.stockKey(ACTIVITY_ID)));
        assertNull(redisTemplate.opsForValue().get(RedisKeyConstant.userKey(ACTIVITY_ID, USER_ID)));
        verify(internalClient).rollbackStock(eq(GOODS_ID), eq(1));
        Long cancelLog = logMapper.selectCount(new LambdaQueryWrapper<SeckillLog>()
                .eq(SeckillLog::getOrderNo, orderNo).eq(SeckillLog::getAction, "ORDER_CANCEL"));
        assertEquals(1L, cancelLog);
    }

    @Test
    void cancelPaidReturns4006() throws Exception {
        String orderNo = nextOrderNo();
        insertOrder(orderNo, USER_ID, ACTIVITY_ID, 1, LocalDateTime.now().plusMinutes(30));
        String token = login(USER_ID);

        mockMvc.perform(post("/api/order/cancel/" + orderNo).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4006));
        verify(internalClient, never()).rollbackStock(anyLong(), anyInt());
    }

    // ---------- 状态查询 ----------

    @Test
    void statusMapsDbRowsToWaitPayPaidCancelled() throws Exception {
        String waitNo = nextOrderNo();
        String paidNo = nextOrderNo();
        String cancelledNo = nextOrderNo();
        insertOrder(waitNo, USER_ID, ACTIVITY_ID, 0, LocalDateTime.now().plusMinutes(30));
        insertOrder(paidNo, USER_ID, ACTIVITY_ID_2, 1, LocalDateTime.now().plusMinutes(30));
        insertOrder(cancelledNo, USER_ID, 1003L, 3, LocalDateTime.now().plusMinutes(30));
        String token = login(USER_ID);

        mockMvc.perform(get("/api/order/status/" + waitNo).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("WAIT_PAY"))
                .andExpect(jsonPath("$.data.price").value(99.00));
        mockMvc.perform(get("/api/order/status/" + paidNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.status").value("PAID"));
        mockMvc.perform(get("/api/order/status/" + cancelledNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    void statusQueuedFallbackFromRedisMark() throws Exception {
        // 无订单行：模拟 MQ 落库前（Lua 已扣），有 MQ_SEND 日志 + Redis 用户标记 -> QUEUED
        String orderNo = nextOrderNo();
        SeckillLog mqLog = new SeckillLog();
        mqLog.setOrderNo(orderNo);
        mqLog.setActivityId(ACTIVITY_ID);
        mqLog.setUserId(USER_ID);
        mqLog.setAction("MQ_SEND");
        mqLog.setDetail("queued");
        mqLog.setCreatedAt(LocalDateTime.now());
        logMapper.insert(mqLog);
        redisTemplate.opsForValue().set(RedisKeyConstant.userKey(ACTIVITY_ID, USER_ID), "1");
        String token = login(USER_ID);

        mockMvc.perform(get("/api/order/status/" + orderNo).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    void statusReturnsUnknownForNoTrace() throws Exception {
        String orderNo = nextOrderNo();
        String token = login(USER_ID);

        // 无订单、无 MQ_SEND 日志 -> UNKNOWN（不泄露信息）
        mockMvc.perform(get("/api/order/status/" + orderNo).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"));
    }

    // ---------- 我的订单列表 ----------

    @Test
    void listMineOnlyAndFiltersByStatus() throws Exception {
        String mineWait = nextOrderNo();
        String mineCancelled = nextOrderNo();
        String otherWait = nextOrderNo();
        insertOrder(mineWait, USER_ID, ACTIVITY_ID, 0, LocalDateTime.now().plusMinutes(30));
        insertOrder(mineCancelled, USER_ID, ACTIVITY_ID_2, 3, LocalDateTime.now().plusMinutes(30));
        insertOrder(otherWait, OTHER_USER_ID, ACTIVITY_ID, 0, LocalDateTime.now().plusMinutes(30));
        String token = login(USER_ID);

        // 全部：只含本人两笔，不含他人订单
        MvcResult all = mockMvc.perform(get("/api/order/list").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String body = all.getResponse().getContentAsString();
        assertTrue(body.contains(mineWait) && body.contains(mineCancelled), "应含本人订单: " + body);
        assertFalse(body.contains(otherWait), "不应含他人订单: " + body);

        // status=0 过滤：只剩待支付一笔
        mockMvc.perform(get("/api/order/list").param("status", "0").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].orderNo").value(mineWait));
    }

    // ---------- 内部超时扫描 + 超时取消（scheduler 兜底） ----------

    @Test
    void internalTimeoutListAndCancelWorks() throws Exception {
        String overdue = nextOrderNo();
        String active = nextOrderNo();
        insertOrder(overdue, USER_ID, ACTIVITY_ID, 0, LocalDateTime.now().minusMinutes(1)); // 已过期
        insertOrder(active, USER_ID, ACTIVITY_ID_2, 0, LocalDateTime.now().plusMinutes(30)); // 未过期
        redisTemplate.opsForValue().set(RedisKeyConstant.stockKey(ACTIVITY_ID), "10");
        redisTemplate.opsForValue().set(RedisKeyConstant.userKey(ACTIVITY_ID, USER_ID), "1");

        // 扫描：应只含过期订单
        MvcResult scan = mockMvc.perform(get("/internal/order/timeout-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String scanBody = scan.getResponse().getContentAsString();
        assertTrue(scanBody.contains(overdue), "应包含过期订单: " + scanBody);
        assertFalse(scanBody.contains(active), "不应包含未过期订单: " + scanBody);

        // 超时取消 -> status=2 + 回滚 Redis + ORDER_TIMEOUT 日志
        mockMvc.perform(post("/internal/order/timeout-cancel")
                        .contentType("application/json")
                        .content("{\"orderNo\":\"" + overdue + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        SeckillOrder saved = orderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getOrderNo, overdue));
        assertEquals(2, saved.getStatus());
        assertEquals("11", redisTemplate.opsForValue().get(RedisKeyConstant.stockKey(ACTIVITY_ID)));
        assertNull(redisTemplate.opsForValue().get(RedisKeyConstant.userKey(ACTIVITY_ID, USER_ID)));
        verify(internalClient).rollbackStock(eq(GOODS_ID), eq(1));
        Long timeoutLog = logMapper.selectCount(new LambdaQueryWrapper<SeckillLog>()
                .eq(SeckillLog::getOrderNo, overdue).eq(SeckillLog::getAction, "ORDER_TIMEOUT"));
        assertEquals(1L, timeoutLog);
    }
}