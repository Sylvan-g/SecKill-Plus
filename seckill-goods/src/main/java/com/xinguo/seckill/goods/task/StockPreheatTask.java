package com.xinguo.seckill.goods.task;

import com.xinguo.seckill.common.constant.RedisKeyConstant;
import com.xinguo.seckill.goods.entity.GoodsStock;
import com.xinguo.seckill.goods.entity.SeckillActivity;
import com.xinguo.seckill.goods.mapper.GoodsStockMapper;
import com.xinguo.seckill.goods.mapper.SeckillActivityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Redis 库存重建任务（需求文档 4.6 / R8）：启动 + 每 60 秒以 DB 兜底层
 * t_goods_stock.total_stock 为准重建 seckill:stock:{activityId}。
 *
 * 纪律：
 * 1) 绝不用活动表 activity.stock —— 活动进行中 Redis 重启时 activity.stock 仍为
 *    原始总量，用它重建会把已扣库存重置、直接超卖（R8）。必须用 t_goods_stock 的真实剩余值。
 * 2) 只在 Redis 库存键缺失时写入（setIfAbsent）——T7 引入 Lua 原子扣减后 Redis 库存是
 *    实时值；周期任务若无条件覆盖会把已扣剩余量重置回 DB 值，同样超卖。setIfAbsent 使
 *    本任务只在 Redis 丢失（如重启）时兜底重建，日常运行不动已扣减的实时库存。
 */
@Component
public class StockPreheatTask {

    private static final Logger log = LoggerFactory.getLogger(StockPreheatTask.class);

    private final SeckillActivityMapper activityMapper;
    private final GoodsStockMapper goodsStockMapper;
    private final StringRedisTemplate redisTemplate;

    public StockPreheatTask(SeckillActivityMapper activityMapper,
                            GoodsStockMapper goodsStockMapper,
                            StringRedisTemplate redisTemplate) {
        this.activityMapper = activityMapper;
        this.goodsStockMapper = goodsStockMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 启动后 15 秒执行一次，之后每 60 秒重建；只对已开始且未下架的活动重建。
     * 未开始的活动不预热（避免把活动 stock 提前放大）；已结束后可清理或保留均无影响。
     */
    @Scheduled(initialDelay = 15_000, fixedDelay = 60_000)
    public void preheat() {
        List<SeckillActivity> activities =
                activityMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
        int count = 0;
        for (SeckillActivity activity : activities) {
            if (activity.getEndTime() == null || activity.getStatus() == null
                    || activity.getStatus() == 3) {
                continue; // 已下架或数据异常，跳过
            }
            GoodsStock goodsStock = goodsStockMapper.selectById(activity.getGoodsId());
            if (goodsStock == null || goodsStock.getTotalStock() == null) {
                continue;
            }
            // setIfAbsent：仅 Redis 库存键缺失（丢失/重启）时重建，绝不覆盖实时已扣减值
            // TTL = 活动结束 + 1 天（文档 7 节约定），活动结束后库存 key 自动过期，避免长期驻留
            LocalDateTime end = activity.getEndTime();
            Duration ttl = (end != null && end.isAfter(LocalDateTime.now()))
                    ? Duration.between(LocalDateTime.now(), end).plusDays(1)
                    : Duration.ofDays(1);
            redisTemplate.opsForValue().setIfAbsent(
                    RedisKeyConstant.stockKey(activity.getId()),
                    String.valueOf(goodsStock.getTotalStock()),
                    ttl);
            count++;
        }
        if (count > 0) {
            log.info("[preheat] rebuilt {} activity stock from DB total_stock", count);
        }
    }
}