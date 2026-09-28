package com.xinguo.seckill.gateway.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分布式限流配置（需求 10.2 / 7.3 / 13 节约定键 seckill.ratelimit）：
 * - qps：单 IP 稳定速率（rate-per-sec 每毫秒内由 qps/1000 换算），需求默认 200；压测时可经环境变量
 *   SECKILL_RATELIMIT_QPS 覆盖调高（如 5000）。阈值语义为整数级（>=1），避免小数取整出 0 导致全拒。
 * - capacity：令牌桶容量（突发上限）。缺省 0 = 跟随 qps（即无突发，严格稳定速率）；
 *   显式 >0 时 capacity>qps 提供突发窗口；**capacity<qps 时实际稳定速率被压缩为 capacity**（桶容量即上限）。
 * - scoped-paths：命中才限流（默认 /api/seckill/**、/api/order/pay/** 热点写，读接口不限）。
 * 校验（fail-fast，启动即失败）：qps>=1；capacity 非负；scoped-paths 非空（防限流静默失效）。
 */
@Component
@ConfigurationProperties(prefix = "seckill.ratelimit")
public class RateLimitProperties {

    private boolean enabled = true;
    private double qps = 200;
    /** 令牌桶容量（整数语义）；0 = 未显式配置，取值跟随 qps */
    private int capacity;
    private List<String> scopedPaths = List.of("/api/seckill/**", "/api/order/pay/**");

    @PostConstruct
    public void validate() {
        if (qps < 1) {
            // qps<1 时 Math.round 可能得 0，会让 Lua 令牌桶 tokens=0<cost 全拒 4290（P2-1）
            throw new IllegalStateException("seckill.ratelimit.qps 必须 >= 1（稳定速率语义为整数级），当前: " + qps);
        }
        if (capacity < 0) {
            throw new IllegalStateException("seckill.ratelimit.capacity 不允许为负，当前: " + capacity);
        }
        if (scopedPaths == null || scopedPaths.isEmpty()) {
            // 空 scoped-paths 会使 anyMatch 恒 false，限流静默失效（P2-2）
            throw new IllegalStateException("seckill.ratelimit.scoped-paths 不能为空，否则限流不会生效");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 单 IP 稳定速率阈值（需求 seckill.ratelimit.qps，每秒令牌数） */
    public double getQps() {
        return qps;
    }

    public void setQps(double qps) {
        this.qps = qps;
    }

    /** 令牌桶容量：未显式配置时跟随 qps（无突发），显式 >0 时按配置；最低 1（防取整为 0 全拒） */
    public int getCapacity() {
        return Math.max(1, capacity > 0 ? capacity : (int) Math.round(qps));
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    /** 供限流器使用：稳定速率（== qps，每秒令牌数） */
    public double getRatePerSec() {
        return qps;
    }

    public List<String> getScopedPaths() {
        return scopedPaths;
    }

    public void setScopedPaths(List<String> scopedPaths) {
        this.scopedPaths = scopedPaths;
    }
}