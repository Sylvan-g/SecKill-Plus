package com.xinguo.seckill.gateway.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分布式限流服务（需求 10.2 / 第 7 节 R7-③）：
 * Redis+Lua 令牌桶原子扣减，兼容 Redis 3.2（HSET/PEXPIRE），按"IP+接口"隔离。
 * Redis Key：seckill:ratelimit:{scope}:{key} = Hash{tokens,last_refill}，5 秒 PEXPIRE 滑动窗口。
 */
@Service
public class RateLimitLuaService {

    /**
     * 令牌桶 Lua（需求 10.2 语义，按 Redis 3.2 调整）：
     * 原脚本 HSET 一次写多字段（HSET k f1 v1 f2 v2）是 Redis 4.0+ 语法，3.2 会报
     * "Wrong number of args calling Redis command"；且 HGETALL 返回顺序无序，
     * 故改为 HGET 逐字段读、HSET 逐字段写，行为完全等价。
     */
    private static final String LUA = """
            local now = tonumber(ARGV[3])
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local cost = tonumber(ARGV[4])
            local tokRaw = redis.call('HGET', KEYS[1], 'tokens')
            local lastRaw = redis.call('HGET', KEYS[1], 'last_refill')
            local tokens
            local last
            if tokRaw == false then
                tokens = capacity
                last = now
            else
                tokens = tonumber(tokRaw)
                last = tonumber(lastRaw) or now
            end
            -- 按流逝时间补令牌
            local elapsed = math.max(0, now - last)
            tokens = math.min(capacity, tokens + elapsed * rate)
            if tokens < cost then
                redis.call('HSET', KEYS[1], 'tokens', tokens)
                redis.call('HSET', KEYS[1], 'last_refill', now)
                redis.call('PEXPIRE', KEYS[1], 5000)
                return 0
            end
            tokens = tokens - cost
            redis.call('HSET', KEYS[1], 'tokens', tokens)
            redis.call('HSET', KEYS[1], 'last_refill', now)
            redis.call('PEXPIRE', KEYS[1], 5000)
            return 1
            """;

    private static final DefaultRedisScript<Long> SCRIPT;

    static {
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setScriptText(LUA);
        SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;

    public RateLimitLuaService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 尝试获取 1 个令牌：成功返回 true，超限返回 false。
     *
     * @param key       限流桶 key（如 seckill:ratelimit:ip:127.0.0.1:/api/seckill/do/1）
     * @param capacity  桶容量
     * @param ratePerSec 每秒补充令牌数（内部换算为每毫秒速率）
     * @param nowMs     当前时间戳(ms)
     */
    public boolean tryAcquire(String key, int capacity, double ratePerSec, long nowMs) {
        double ratePerMs = ratePerSec / 1000.0;
        Long result = redis.execute(
                SCRIPT,
                List.of(key),
                String.valueOf(capacity),
                String.valueOf(ratePerMs),
                String.valueOf(nowMs),
                "1");
        return result != null && result == 1L;
    }
}