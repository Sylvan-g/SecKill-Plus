package com.xinguo.seckill.order.service;

import com.xinguo.seckill.common.constant.RedisKeyConstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * Redis 原子扣减库存（需求文档 10.1 Lua 脚本）：
 * 一次 Lua 内完成"用户去重检查 + 库存扣减 + 用户已购标记"，返回码：
 *   1  -> 成功
 *   -1 -> 库存不足（4001）
 *   -2 -> 用户重复购买（4002）
 *
 * 标记 TTL = 活动结束+1 天（R7：同一账号并发点击只生成 1 个订单；取消时 DEL 允许重抢 R5）。
 */
@Service
public class StockDeductLuaService {

    private static final Logger log = LoggerFactory.getLogger(StockDeductLuaService.class);

    /**
     * 需求文档 10.1 原样脚本：
     * KEYS[1]=库存key, KEYS[2]=用户购买标记key
     * ARGV[1]=用户ID, ARGV[2]=标记过期秒数
     */
    private static final String SCRIPT =
            "if redis.call('EXISTS', KEYS[2]) == 1 then\n"
                    + "    return -2\n"
                    + "end\n"
                    + "local stock = tonumber(redis.call('GET', KEYS[1]))\n"
                    + "if not stock or stock <= 0 then\n"
                    + "    return -1\n"
                    + "end\n"
                    + "redis.call('DECR', KEYS[1])\n"
                    + "redis.call('SET', KEYS[2], ARGV[1], 'EX', ARGV[2])\n"
                    + "return 1\n";

    /** Redis 3.2 无 EVAL_NUM 便捷键名可用，返回值为 Long 兼容 */
    private static final DefaultRedisScript<Long> SCRIPT_REDIS = new DefaultRedisScript<>(SCRIPT, Long.class);

    private final StringRedisTemplate redisTemplate;

    public StockDeductLuaService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 原子尝试扣减：1 成功 / -1 库存不足 / -2 重复购买
     *
     * @param activityId        活动 ID
     * @param userId            用户 ID
     * @param markerTtlSeconds  用户已购标记 TTL（秒），传活动剩余时长；测试可按需缩短
     */
    public long deductStock(Long activityId, Long userId, long markerTtlSeconds) {
        List<String> keys = Arrays.asList(
                RedisKeyConstant.stockKey(activityId),
                RedisKeyConstant.userKey(activityId, userId));
        Long result = redisTemplate.execute(SCRIPT_REDIS, keys,
                String.valueOf(userId), String.valueOf(markerTtlSeconds));
        return result == null ? -1 : result;
    }
}