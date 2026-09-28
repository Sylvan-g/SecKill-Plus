package com.xinguo.seckill.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关配置（需求文档 4.2 端口表 / 8 节路由约定）：
 * - routes: 公网前缀 -> 目标服务 baseUrl（路径原样透传）
 * - cors: 跨域白名单（仅放行开发前端 origin）
 * - rest-timeout-ms: 上游转调超时
 * - rate-limit 见独立类 RateLimitProperties（seckill.ratelimit.*，需求 10.2 约定键）
 */
@Component
@ConfigurationProperties(prefix = "seckill.gateway")
public class GatewayProperties {

    private long restTimeoutMs = 5000;

    private List<Route> routes = new ArrayList<>();

    private Cors cors = new Cors();

    public long getRestTimeoutMs() {
        return restTimeoutMs;
    }

    public void setRestTimeoutMs(long restTimeoutMs) {
        this.restTimeoutMs = restTimeoutMs;
    }

    public List<Route> getRoutes() {
        return routes;
    }

    public void setRoutes(List<Route> routes) {
        this.routes = routes;
    }

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    /** 一条路由规则 */
    public static class Route {
        private String prefix;
        private String target;

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }
    }

    /** 跨域白名单配置 */
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:5173");

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }
}