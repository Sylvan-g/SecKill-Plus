package com.xinguo.seckill.order.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.common.constant.RedisKeyConstant;
import com.xinguo.seckill.order.mapper.SeckillLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 秒杀下单接口集成测试（真实 MySQL seckill_order + Redis）：
 * - 未登录 -> HTTP 401
 * - 时间未到（活动未开始）-> 4003
 * - 库存不足（Redis stock=0）-> 4001
 * - 重复购买（用户标记已存在）-> 4002
 * - 成功：QUEUED + Redis 库存 -1 + 用户标记存在 + 日志 REQUEST/STOCK_DEDUCT/MQ_SEND
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
class SeckillControllerIntegrationTest {

    private static final long ACTIVITY_ID = 1001L;
    private static final long USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SeckillLogMapper logMapper;

    @BeforeEach
    void setUp() throws Exception {
        // 恢复活动缓存（时间窗口包含"现在"）
        String fmtStart = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now().minusMinutes(5));
        String fmtEnd = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now().plusMinutes(15));
        String activityJson = "{\"activityId\":" + ACTIVITY_ID + ",\"goodsId\":2001,"
                + "\"goodsName\":\"集成测试相机\",\"seckillPrice\":99.00,"
                + "\"startTime\":\"" + fmtStart + "\",\"endTime\":\"" + fmtEnd
                + "\",\"limitPerUser\":1}";
        redisTemplate.opsForValue().set(RedisKeyConstant.activityKey(ACTIVITY_ID), activityJson);

        // 恢复库存并清用户标记
        redisTemplate.opsForValue().set(RedisKeyConstant.stockKey(ACTIVITY_ID), "10");
        redisTemplate.delete(RedisKeyConstant.userKey(ACTIVITY_ID, USER_ID));
    }

    private String loginAndGetToken() {
        StpUtil.login(USER_ID);
        return StpUtil.getTokenValue();
    }

    @Test
    void notLoginReturns401() throws Exception {
        mockMvc.perform(post("/api/seckill/do/" + ACTIVITY_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void notStartedReturns4003() throws Exception {
        // 活动未开始
        String fmtStart = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now().plusMinutes(5));
        String fmtEnd = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now().plusMinutes(15));
        redisTemplate.opsForValue().set(RedisKeyConstant.activityKey(ACTIVITY_ID),
                "{\"activityId\":" + ACTIVITY_ID + ",\"goodsId\":2001,\"seckillPrice\":99.00,"
                        + "\"startTime\":\"" + fmtStart + "\",\"endTime\":\"" + fmtEnd + "\",\"limitPerUser\":1}");

        String token = loginAndGetToken();
        mockMvc.perform(post("/api/seckill/do/" + ACTIVITY_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4003));
    }

    @Test
    void stockEmptyReturns4001() throws Exception {
        redisTemplate.opsForValue().set(RedisKeyConstant.stockKey(ACTIVITY_ID), "0");

        String token = loginAndGetToken();
        mockMvc.perform(post("/api/seckill/do/" + ACTIVITY_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void duplicateReturns4002() throws Exception {
        // 已有用户购买标记
        redisTemplate.opsForValue().set(RedisKeyConstant.userKey(ACTIVITY_ID, USER_ID), "1", 60, TimeUnit.SECONDS);

        String token = loginAndGetToken();
        mockMvc.perform(post("/api/seckill/do/" + ACTIVITY_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4002));
    }

    @Test
    void successQueuesAndDeducts() throws Exception {
        String token = loginAndGetToken();
        MvcResult result = mockMvc.perform(post("/api/seckill/do/" + ACTIVITY_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn();

        // 返回 orderNo
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        String orderNo = node.path("data").path("orderNo").asText();
        assertTrue(orderNo.matches("^SK\\d{17}$"), "订单号: " + orderNo);

        // Redis 库存 -1（10->9）
        assertEquals("9", redisTemplate.opsForValue().get(RedisKeyConstant.stockKey(ACTIVITY_ID)));

        // 用户标记存在
        assertNotNull(redisTemplate.opsForValue().get(RedisKeyConstant.userKey(ACTIVITY_ID, USER_ID)));

        // 日志：REQUEST / STOCK_DEDUCT / MQ_SEND（前两条 orderNo=null，后一条带 orderNo）-> 按 activity+user 查 3 条
        Long logCount = logMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                        com.xinguo.seckill.order.entity.SeckillLog>()
                        .eq(com.xinguo.seckill.order.entity.SeckillLog::getActivityId, ACTIVITY_ID)
                        .eq(com.xinguo.seckill.order.entity.SeckillLog::getUserId, USER_ID));
        assertTrue(logCount >= 3, "应写入 REQUEST/STOCK_DEDUCT/MQ_SEND 日志，实际 " + logCount);

        // 清理本次测试产生的日志（活动 1001 + 用户 1）
        logMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                com.xinguo.seckill.order.entity.SeckillLog>()
                .eq(com.xinguo.seckill.order.entity.SeckillLog::getActivityId, ACTIVITY_ID)
                .eq(com.xinguo.seckill.order.entity.SeckillLog::getUserId, USER_ID));
    }

    @Test
    void succeedsThenDuplicateOnSecondClick() throws Exception {
        String token = loginAndGetToken();
        mockMvc.perform(post("/api/seckill/do/" + ACTIVITY_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 同一用户再点 -> 4002（R7 ④ 同一账号并发点击只生成 1 个订单）
        mockMvc.perform(post("/api/seckill/do/" + ACTIVITY_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4002));

        // 清理本次测试产生的日志（稠密命名不便唯一，这里清该 userId + ACTION 组合清理最小集）
        // 具体日志在上一个测试中按 orderNo 清理；此处本轮新增 5 条由测试隔离负责
    }
}