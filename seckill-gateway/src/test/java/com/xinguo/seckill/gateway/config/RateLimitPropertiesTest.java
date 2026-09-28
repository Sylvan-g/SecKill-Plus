package com.xinguo.seckill.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流配置（seckill.ratelimit.*）单测：需求 10.2 约定键默认值、capacity 跟随 qps、
 * 非法输入 fail-fast 校验。默认值 / getter 语义与 application.yml 主配置一致。
 */
class RateLimitPropertiesTest {

    private RateLimitProperties props;

    @BeforeEach
    void setUp() {
        props = new RateLimitProperties();
    }

    @Test
    void defaultsFollowRequirement() {
        assertTrue(props.isEnabled(), "默认开启限流");
        assertEquals(200, props.getQps(), "需求 seckill.ratelimit.qps=200");
        assertEquals(200, props.getRatePerSec(), "稳定速率跟随 qps");
        assertTrue(props.getScopedPaths().contains("/api/seckill/**"), "默认限流路径含秒杀写接口");
        assertTrue(props.getScopedPaths().contains("/api/order/pay/**"), "默认限流路径含支付写接口");
    }

    @Test
    void capacityDefaultsToQpsWhenNotConfigured() {
        // capacity 未显式配置 → 跟随 qps（无突发）
        assertEquals(200, props.getCapacity());
        props.setQps(5000);
        assertEquals(5000, props.getCapacity(), "capacity 缺省跟随 qps，压测调高 qps 时容量同步放大");
    }

    @Test
    void explicitCapacityOverridesQps() {
        props.setQps(200);
        props.setCapacity(1000);
        assertEquals(1000, props.getCapacity(), "显式 capacity 提供突发窗口");
        assertEquals(200, props.getRatePerSec(), "稳定速率不受 capacity 影响");
    }

    @Test
    void smallerCapacityClampsEffectiveRate() {
        // capacity<qps 时实际稳定速率被桶容量压到 capacity（令牌桶语义，容量即上限）（P2-3）
        props.setQps(200);
        props.setCapacity(100);
        assertEquals(100, props.getCapacity(), "显式 smaller capacity 生效");
        assertEquals(200, props.getRatePerSec(), "名义 qps 不变，实际速率受容量钳制");
    }

    @Test
    void zeroQpsFailsFast() {
        props.setQps(0);
        assertThrows(IllegalStateException.class, props::validate, "qps=0 必须启动失败");
    }

    @Test
    void fractionalQpsBelowOneFailsFast() {
        // 0<qps<1 时 Math.round 得 0，会让 Lua tokens=0<cost 全拒 4290（P2-1）
        props.setQps(0.3);
        assertThrows(IllegalStateException.class, props::validate, "qps<1 必须启动失败");
    }

    @Test
    void emptyScopedPathsFailsFast() {
        // 空 scoped-paths 使 anyMatch 恒 false，限流静默失效（P2-2）
        props.setScopedPaths(List.of());
        assertThrows(IllegalStateException.class, props::validate, "scoped-paths 为空必须启动失败");
    }

    @Test
    void negativeCapacityFailsFast() {
        props.setCapacity(-1);
        assertThrows(IllegalStateException.class, props::validate, "capacity 为负必须启动失败");
    }
}