package com.xinguo.seckill.goods.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.goods.entity.GoodsStock;
import com.xinguo.seckill.goods.entity.SeckillActivity;
import com.xinguo.seckill.goods.mapper.GoodsStockMapper;
import com.xinguo.seckill.goods.mapper.SeckillActivityMapper;
import com.xinguo.seckill.goods.task.ActivityStatusTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 库存与状态维护集成测试（真实 MySQL seckill_goods + Redis）：
 * - 8.14 POST /internal/stock/deduct：乐观锁扣减（version 递增、余额足够才成功）
 * - 8.14 POST /internal/stock/rollback：回补
 * - 3.6 ActivityStatusTask：0->1（已到 start_time）、1->2（已过 end_time）
 * 依赖 seed 活动数据（goodsId=2001 total_stock=100, version=0）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
class StockAndStatusIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GoodsStockMapper goodsStockMapper;

    @Autowired
    private SeckillActivityMapper activityMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ActivityStatusTask activityStatusTask;

    @Autowired
    private com.xinguo.seckill.goods.service.StockService stockService;

    private static final long DEDUCT_GOODS_ID = 95001L;

    @Test
    void stockDeductAndRollback() throws Exception {
        // 预置独立库存记录，避免污染 seed（goodsId=95001）
        GoodsStock stock = new GoodsStock();
        stock.setGoodsId(DEDUCT_GOODS_ID);
        stock.setTotalStock(100);
        stock.setVersion(0);
        goodsStockMapper.insert(stock);

        // ---- 1. deduct 成功：total_stock=99, version=1 ----
        mockMvc.perform(post("/internal/stock/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsId\":" + DEDUCT_GOODS_ID + ",\"count\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        GoodsStock after = goodsStockMapper.selectById(DEDUCT_GOODS_ID);
        assertEquals(99, after.getTotalStock());
        assertEquals(1, after.getVersion(), "乐观锁 version 应递增");

        // ---- 2. 再扣 1：total_stock=98 ----
        mockMvc.perform(post("/internal/stock/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsId\":" + DEDUCT_GOODS_ID + ",\"count\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(98, goodsStockMapper.selectById(DEDUCT_GOODS_ID).getTotalStock());

        // ---- 3. 库存不足（count > 剩余）-> 4001 ----
        mockMvc.perform(post("/internal/stock/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsId\":" + DEDUCT_GOODS_ID + ",\"count\":99999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
        assertEquals(98, goodsStockMapper.selectById(DEDUCT_GOODS_ID).getTotalStock(),
                "失败扣减不应改动库存");

        // ---- 4. rollback 回补 2：total_stock=100 ----
        mockMvc.perform(post("/internal/stock/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsId\":" + DEDUCT_GOODS_ID + ",\"count\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        GoodsStock rolled = goodsStockMapper.selectById(DEDUCT_GOODS_ID);
        assertEquals(100, rolled.getTotalStock());
        assertEquals(3, rolled.getVersion(), "回补后 version 应继续递增（累计 3）");

        goodsStockMapper.deleteById(DEDUCT_GOODS_ID);
    }

    @Test
    void activityStatusTransition() {
        // ---- 构造 4 个活动：未开始 / 进行中(已到 start) / 已结束 / 过期未开始 ----
        LocalDateTime now = LocalDateTime.now();
        long base = System.currentTimeMillis() % 100_000;

        SeckillActivity notStarted = activity(base + 1, now.plusMinutes(5), now.plusMinutes(10), 0);
        SeckillActivity running = activity(base + 2, now.minusMinutes(5), now.plusMinutes(10), 0);
        SeckillActivity ended = activity(base + 3, now.minusMinutes(20), now.minusMinutes(10), 1);
        // 过期未开始：start/end 都已在过去，但 status 仍为 0（如跨窗口重启场景）
        SeckillActivity skippedToEnd = activity(base + 4, now.minusMinutes(20), now.minusMinutes(10), 0);
        activityMapper.insert(notStarted);
        activityMapper.insert(running);
        activityMapper.insert(ended);
        activityMapper.insert(skippedToEnd);

        activityStatusTask.refresh();

        // 未开始：仍 0（未到 start_time）
        assertEquals(0, activityMapper.selectById(base + 1).getStatus());
        // 进行中：0 -> 1（已到 start_time 且未结束）
        assertEquals(1, activityMapper.selectById(base + 2).getStatus());
        // 已结束：1 -> 2（已过 end_time）
        assertEquals(2, activityMapper.selectById(base + 3).getStatus());
        // 过期未开始：0 -> 2（跨窗口直接落终点，防状态卡 0）
        assertEquals(2, activityMapper.selectById(base + 4).getStatus());

        cleanupActivities(base);
    }

    @Test
    void concurrentDeductNeverOversells() throws Exception {
        // 独立库存：200，8 线程各扣 50 → 理论最多 4 成功，绝不超卖
        long goodsId = 95002L;
        GoodsStock stock = new GoodsStock();
        stock.setGoodsId(goodsId);
        stock.setTotalStock(200);
        stock.setVersion(0);
        goodsStockMapper.insert(stock);

        int threads = 8;
        int countPerRequest = 50;
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicInteger okCount = new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    try {
                        stockService.deduct(goodsId, countPerRequest);
                        okCount.incrementAndGet();
                    } catch (com.xinguo.seckill.common.exception.BusinessException ignored) {
                        // 4001 库存不足/冲突，属预期
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        ready.await(5, java.util.concurrent.TimeUnit.SECONDS);
        go.countDown();
        done.await(10, java.util.concurrent.TimeUnit.SECONDS);

        GoodsStock after = goodsStockMapper.selectById(goodsId);
        int expectedOk = 200 / countPerRequest; // 4
        assertEquals(expectedOk, okCount.get(), "只应有 4 个线程成功扣减");
        assertEquals(0, after.getTotalStock(), "库存应正好扣完为 0，绝不超卖");
        assertEquals(4, after.getVersion(), "乐观锁递增 4 次");
        goodsStockMapper.deleteById(goodsId);
    }

    private SeckillActivity activity(long id, LocalDateTime start, LocalDateTime end, int status) {
        SeckillActivity a = new SeckillActivity();
        a.setId(id);
        a.setGoodsId(id);
        a.setGoodsName("状态测试-" + id);
        a.setOriginalPrice(BigDecimal.valueOf(100));
        a.setSeckillPrice(BigDecimal.valueOf(50));
        a.setStock(10);
        a.setLimitPerUser(1);
        a.setStartTime(start);
        a.setEndTime(end);
        a.setStatus(status);
        return a;
    }

    private void cleanupActivities(long base) {
        activityMapper.delete(new LambdaQueryWrapper<SeckillActivity>()
                .in(SeckillActivity::getId, base + 1, base + 2, base + 3, base + 4));
        for (long i = base + 1; i <= base + 4; i++) {
            redisTemplate.delete("seckill:stock:" + i);
            redisTemplate.delete("seckill:activity:" + i);
        }
    }
}