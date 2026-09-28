package com.xinguo.seckill.goods.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xinguo.seckill.common.constant.RedisKeyConstant;
import com.xinguo.seckill.common.exception.BusinessException;
import com.xinguo.seckill.goods.dto.ActivityDTO.ActivityDetail;
import com.xinguo.seckill.goods.dto.ActivityDTO.ActivityListResult;
import com.xinguo.seckill.goods.dto.ActivityDTO.CreateActivityRequest;
import com.xinguo.seckill.goods.entity.GoodsStock;
import com.xinguo.seckill.goods.entity.SeckillActivity;
import com.xinguo.seckill.goods.mapper.GoodsStockMapper;
import com.xinguo.seckill.goods.mapper.SeckillActivityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ActivityService 外部行为测试（mock mapper + redis）：
 * 1) 列表：按状态过滤、分页、stock 取 Redis 实时值
 * 2) 列表回退：Redis 未预热 -> 回退 DB stock（8.1 注释）
 * 3) 详情：缓存未命中 -> 查 DB 并回填缓存
 * 4) 详情：缓存命中直接返回，不用再查 DB
 * 5) 创建：落库 + 初始化真实库存 + 立即预热（8.14）
 * 6) 参数校验：非法参数抛 BusinessException
 */
class ActivityServiceTest {

    private SeckillActivityMapper activityMapper;
    private GoodsStockMapper goodsStockMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ActivityService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        activityMapper = mock(SeckillActivityMapper.class);
        goodsStockMapper = mock(GoodsStockMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // ActivityDetail 含 LocalDateTime，必须注册 JSR310 module（Boot 自动装配已带，测试手动补）
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new ActivityService(activityMapper, goodsStockMapper, redisTemplate, mapper);
    }

    private SeckillActivity activity(Long id, Integer status, Integer stock) {
        SeckillActivity a = new SeckillActivity();
        a.setId(id);
        a.setGoodsId(2001L);
        a.setGoodsName("无线蓝牙耳机（降噪款）");
        a.setOriginalPrice(BigDecimal.valueOf(299.00));
        a.setSeckillPrice(BigDecimal.valueOf(99.00));
        a.setStock(stock);
        a.setLimitPerUser(1);
        a.setStartTime(LocalDateTime.now().minusMinutes(1));
        a.setEndTime(LocalDateTime.now().plusMinutes(9));
        a.setStatus(status);
        return a;
    }

    @Test
    void listFiltersStatusAndFillsRealTimeStockFromRedis() {
        Page<SeckillActivity> pg = new Page<>(1, 10);
        pg.setRecords(Collections.singletonList(activity(1001L, 1, 100)));
        pg.setTotal(1);
        when(activityMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(pg);
        when(valueOps.get(RedisKeyConstant.stockKey(1001L))).thenReturn("37");

        ActivityListResult result = service.list(1, 1, 10);

        assertEquals(1, result.list.size());
        assertEquals(1001L, result.list.get(0).activityId);
        assertEquals(1, result.list.get(0).status);
        assertEquals(37, result.list.get(0).stock, "stock 应取 Redis 实时库存");
        assertEquals(1, result.total);
        assertNotNull(result.serverTime);
    }

    @Test
    void listFallsBackToDbStockWhenRedisNotPreheated() {
        Page<SeckillActivity> pg = new Page<>(1, 10);
        pg.setRecords(Collections.singletonList(activity(1001L, 1, 100)));
        pg.setTotal(1);
        when(activityMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(pg);
        when(valueOps.get(RedisKeyConstant.stockKey(1001L))).thenReturn(null); // 未预热

        ActivityListResult result = service.list(null, 1, 10);

        assertEquals(100, result.list.get(0).stock, "未预热应回退 DB 活动表 stock");
    }

    @Test
    void detailMissesCacheReadsDbAndBackfillsCache() throws Exception {
        when(valueOps.get(RedisKeyConstant.activityKey(1001L))).thenReturn(null);
        when(activityMapper.selectById(1001L)).thenReturn(activity(1001L, 1, 100));
        when(valueOps.get(RedisKeyConstant.stockKey(1001L))).thenReturn("88");

        ActivityDetail detail = service.detail(1001L);

        assertNotNull(detail);
        assertEquals(1001L, detail.activityId);
        assertEquals(88, detail.stock, "详情 stock 应取 Redis 实时值");
        assertEquals(2001L, detail.goodsId);
        // 回填缓存：写入 seckill:activity:1001（随机 TTL）
        verify(valueOps).set(eq(RedisKeyConstant.activityKey(1001L)), anyString(), any(java.time.Duration.class));
    }

    @Test
    void detailHitsCacheSkipsDb() throws Exception {
        String cacheJson = "{\"activityId\":1001,\"goodsId\":2001,\"goodsName\":\"x\","
                + "\"originalPrice\":299.00,\"seckillPrice\":99.00,\"stock\":100,"
                + "\"limitPerUser\":1,\"startTime\":\"2026-09-25T12:00:00\","
                + "\"endTime\":\"2026-09-25T12:10:00\",\"serverTime\":\"2026-09-25T12:00:00\",\"status\":1}";
        when(valueOps.get(RedisKeyConstant.activityKey(1001L))).thenReturn(cacheJson);
        when(valueOps.get(RedisKeyConstant.stockKey(1001L))).thenReturn("55");

        ActivityDetail detail = service.detail(1001L);

        assertEquals(1001L, detail.activityId);
        assertEquals(55, detail.stock, "缓存命中但 stock 也应为 Redis 实时值");
        verify(activityMapper, never()).selectById(1001L);
    }

    @Test
    void createInsertsActivityStockAndPreheats() {
        when(activityMapper.insert(any(SeckillActivity.class))).thenAnswer(inv -> {
            SeckillActivity a = inv.getArgument(0);
            a.setId(7777L); // 模拟自增回填
            return 1;
        });
        when(goodsStockMapper.insert(any(GoodsStock.class))).thenReturn(1);

        CreateActivityRequest req = new CreateActivityRequest();
        req.goodsId = 8888L;
        req.goodsName = "测试商品";
        req.originalPrice = BigDecimal.valueOf(199.00);
        req.seckillPrice = BigDecimal.valueOf(59.00);
        req.stock = 100;
        req.limitPerUser = 1;
        req.startTime = LocalDateTime.now().plusMinutes(1);
        req.endTime = LocalDateTime.now().plusMinutes(11);

        Long id = service.create(req);

        assertEquals(7777L, id);
        verify(goodsStockMapper).insert(any(GoodsStock.class));
        verify(valueOps).set(eq(RedisKeyConstant.stockKey(7777L)), eq("100"), any(java.time.Duration.class));
    }

    @Test
    void createRejectsInvalidPrice() {
        when(activityMapper.insert(any(SeckillActivity.class))).thenReturn(1);

        CreateActivityRequest req = new CreateActivityRequest();
        req.goodsId = 8888L;
        req.goodsName = "测试商品";
        req.originalPrice = BigDecimal.valueOf(59.00);
        req.seckillPrice = BigDecimal.valueOf(99.00); // 秒杀价 > 原价，非法
        req.stock = 100;
        req.startTime = LocalDateTime.now().plusMinutes(1);
        req.endTime = LocalDateTime.now().plusMinutes(11);

        assertThrows(BusinessException.class, () -> service.create(req));
        verify(goodsStockMapper, never()).insert(any(GoodsStock.class));
    }
}