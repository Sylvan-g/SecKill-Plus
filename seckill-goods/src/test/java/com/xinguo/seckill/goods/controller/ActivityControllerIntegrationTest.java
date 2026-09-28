package com.xinguo.seckill.goods.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.goods.entity.GoodsStock;
import com.xinguo.seckill.goods.entity.SeckillActivity;
import com.xinguo.seckill.goods.mapper.GoodsStockMapper;
import com.xinguo.seckill.goods.mapper.SeckillActivityMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Activity 接口集成测试（真实 MySQL seckill_goods + Redis）：
 * - 8.1 /api/goods/activity/list：列表分页、status 过滤、serverTime
 * - 8.2 /api/goods/activity/{id}：详情（含 goodsId/stock）
 * - 8.14 POST /internal/activity/create：落库 + 真实库存初始化 + Redis 预热
 * 依赖 init-db.ps1 预置的 1001-1005 活动。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActivityControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SeckillActivityMapper activityMapper;

    @Autowired
    private GoodsStockMapper goodsStockMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 创建活动用的唯一 goodsId，避免与 seed 冲突 */
    private static final long NEW_GOODS_ID = 90000L;

    @Test
    void activityListAndDetail() throws Exception {
        // ---- 列表：全量 ----
        MvcResult listResult = mockMvc.perform(get("/api/goods/activity/list")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.serverTime").exists())
                .andReturn();
        JsonNode list = objectMapper.readTree(listResult.getResponse().getContentAsString()).path("data");
        int total = list.path("total").asInt();
        assertTrue(total >= 5, "种子数据应有 5 个活动，实际 " + total);

        // ---- 列表：按 status 过滤（对照 DB 实时 count，不依赖 seed 初始 status） ----
        long status0Count = activityMapper.selectCount(
                new LambdaQueryWrapper<SeckillActivity>().eq(SeckillActivity::getStatus, 0));
        mockMvc.perform(get("/api/goods/activity/list")
                        .param("status", "0").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(status0Count));

        // ---- 详情 1001：seed 预置活动 ----
        mockMvc.perform(get("/api/goods/activity/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.activityId").value(1001))
                .andExpect(jsonPath("$.data.goodsId").exists())
                .andExpect(jsonPath("$.data.goodsName").exists())
                .andExpect(jsonPath("$.data.seckillPrice").exists())
                .andExpect(jsonPath("$.data.stock").exists())
                .andExpect(jsonPath("$.data.serverTime").exists());

        // ---- 内部取详情：GET /internal/activity/1001（8.14） ----
        mockMvc.perform(get("/internal/activity/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.activityId").value(1001));
    }

    @Test
    void internalCreateActivityInsertsAndPreheats() throws Exception {
        // ---- 8.14 创建活动 ----
        String body = "{\"goodsId\":" + NEW_GOODS_ID + ",\"goodsName\":\"集成测试新品\","
                + "\"originalPrice\":199.00,\"seckillPrice\":59.00,\"stock\":50,"
                + "\"limitPerUser\":2,\"startTime\":\"2026-09-25T12:00:00\","
                + "\"endTime\":\"2026-09-25T12:10:00\"}";
        MvcResult createResult = mockMvc.perform(post("/internal/activity/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isNumber())
                .andReturn();
        long newId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").asLong();

        // ---- DB：活动落库 + 真实库存表初始化 ----
        SeckillActivity activity = activityMapper.selectById(newId);
        assertNotNull(activity, "活动应落库");
        assertEquals(NEW_GOODS_ID, activity.getGoodsId());
        GoodsStock stock = goodsStockMapper.selectById(NEW_GOODS_ID);
        assertNotNull(stock, "真实库存应初始化");
        assertEquals(50, stock.getTotalStock());
        assertEquals(0, stock.getVersion());

        // ---- Redis：立即预热一次，key = seckill:stock:{newId} = 50 ----
        String stockValue = redisTemplate.opsForValue()
                .get("seckill:stock:" + newId);
        assertEquals("50", stockValue, "新活动应预热真实库存 50");

        // ---- 列表能看到新活动 ----
        mockMvc.perform(get("/api/goods/activity/list").param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        cleanupNewActivity(newId);
    }

    private void cleanupNewActivity(long activityId) {
        redisTemplate.delete("seckill:stock:" + activityId);
        redisTemplate.delete("seckill:activity:" + activityId);
        activityMapper.delete(new LambdaQueryWrapper<SeckillActivity>().eq(SeckillActivity::getId, activityId));
        goodsStockMapper.deleteById(NEW_GOODS_ID);
        Assertions.assertNull(activityMapper.selectById(activityId), "清理失败：活动应已删除");
    }
}