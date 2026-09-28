package com.xinguo.seckill.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分布式限流 Lua 集成测试（真实 Redis，需求 10.2）：
 * 时间通过 tryAcquire 的 nowMs 参数注入，无 sleep，测试确定性强。
 * 注意：断言强依赖 gateway 上下文能启动（顺带验证 data-redis 自动装配）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitLuaServiceTest {

    @Autowired
    private RateLimitLuaService rateLimitLuaService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void allowsExactlyCapacityThenRejects() {
        String key = "seckill:ratelimit:ip:it:" + UUID.randomUUID();
        try {
            long now = System.currentTimeMillis();
            // rate=0：不随时间补令牌，验证容量边界
            assertTrue(rateLimitLuaService.tryAcquire(key, 3, 0, now));
            assertTrue(rateLimitLuaService.tryAcquire(key, 3, 0, now));
            assertTrue(rateLimitLuaService.tryAcquire(key, 3, 0, now));
            assertFalse(rateLimitLuaService.tryAcquire(key, 3, 0, now), "第 4 次应被拒");
            assertFalse(rateLimitLuaService.tryAcquire(key, 3, 0, now + 1000), "rate=0 永不补满");
        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    void refillsTokensAsTimePasses() {
        String key = "seckill:ratelimit:ip:it:" + UUID.randomUUID();
        try {
            long t0 = System.currentTimeMillis();
            assertTrue(rateLimitLuaService.tryAcquire(key, 1, 200, t0), "首请求占用令牌");
            assertFalse(rateLimitLuaService.tryAcquire(key, 1, 200, t0), "桶空即拒");
            // 200/s 经过 1000ms 充分回补
            assertTrue(rateLimitLuaService.tryAcquire(key, 1, 200, t0 + 1000), "随时间补令牌后放行");
        } finally {
            redisTemplate.delete(key);
        }
    }
}