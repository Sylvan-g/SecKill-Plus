package com.xinguo.seckill.goods.task;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.xinguo.seckill.goods.entity.GoodsStock;
import com.xinguo.seckill.goods.entity.SeckillActivity;
import com.xinguo.seckill.goods.mapper.GoodsStockMapper;
import com.xinguo.seckill.goods.mapper.SeckillActivityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StockPreheatTask 行为测试：预热只应在 Redis 库存键缺失时重建（setIfAbsent），
 * 绝不覆盖 T7 Lua 已扣减的实时库存值（R8 / review P1 修复护栏）。
 */
class StockPreheatTaskTest {

    private SeckillActivityMapper activityMapper;
    private GoodsStockMapper goodsStockMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private StockPreheatTask task;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        activityMapper = mock(SeckillActivityMapper.class);
        goodsStockMapper = mock(GoodsStockMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        task = new StockPreheatTask(activityMapper, goodsStockMapper, redisTemplate);

        SeckillActivity activity = new SeckillActivity();
        activity.setId(1001L);
        activity.setGoodsId(2001L);
        activity.setStatus(1);
        activity.setEndTime(java.time.LocalDateTime.now().plusMinutes(10));
        when(activityMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(activity));

        GoodsStock stock = new GoodsStock();
        stock.setGoodsId(2001L);
        stock.setTotalStock(37); // DB 真实剩余值
        when(goodsStockMapper.selectById(2001L)).thenReturn(stock);
    }

    @Test
    void preheatUsesSetIfAbsentNotOverwrite() {
        task.preheat();

        // 必须用 setIfAbsent（键缺失才重建），绝不能无条件 set 覆盖实时库存；并带 TTL（7 节）便于过期回收
        verify(valueOps, times(1)).setIfAbsent(eq("seckill:stock:1001"), eq("37"), any(java.time.Duration.class));
        verify(valueOps, never()).set(eq("seckill:stock:1001"), anyString());
    }

    @Test
    void preheatSkipsShelfDownActivity() {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(1002L);
        activity.setGoodsId(2002L);
        activity.setStatus(3); // 已下架，不预热
        when(activityMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(activity));

        task.preheat();

        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(java.time.Duration.class));
    }
}