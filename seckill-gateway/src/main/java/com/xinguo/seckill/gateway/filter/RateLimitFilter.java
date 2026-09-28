package com.xinguo.seckill.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.gateway.config.GatewayProperties;
import com.xinguo.seckill.gateway.config.RateLimitProperties;
import com.xinguo.seckill.gateway.ratelimit.RateLimitLuaService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 网关分布式限流（需求 7.3 R7-③ / 10.2 / 8.3 4290）：
 * 只对 scoped-paths（默认 /api/seckill/**、/api/order/pay/** 热点写）按 IP+接口 做 Redis+Lua 令牌桶限流；
 * 放行（未命中限流路径 or 令牌充足）则进入下一过滤器；令牌耗尽返回 HTTP 200 + code 4290。
 * 读接口（列表/详情）与常规接口不做限流（读写分离，需求 10.2 注释约定）。
 * 边界处理：
 * - OPTIONS 预检不做限流（浏览器跨域先发预检，不应消耗令牌额度）。
 * - 限流 key 对路径中的纯数字段做归一化（/api/seckill/do/{id}），避免按活动/订单 ID 拆桶绕过（单 IP 每秒 200 为整接口额度）。
 * - Redis 不可达时 fail-closed：返回 HTTP 200 + code 5000（与 ProxyFilter 兜底口径一致），不静默放行。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String REJECT_MESSAGE = "当前排队人数过多，请稍后重试";
    private static final String FALLBACK_MESSAGE = "系统繁忙，请稍后重试";
    private static final String KEY_PREFIX = "seckill:ratelimit:ip:";

    private final GatewayProperties properties;
    private final RateLimitProperties rateLimitProperties;
    private final RateLimitLuaService rateLimitLuaService;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public RateLimitFilter(GatewayProperties properties, RateLimitProperties rateLimitProperties,
                           RateLimitLuaService rateLimitLuaService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.rateLimitProperties = rateLimitProperties;
        this.rateLimitLuaService = rateLimitLuaService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!rateLimitProperties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // OPTIONS 预检：浏览器跨域真实请求前必发，不做限流也不消耗令牌（同 ProxyFilter 短路口径）
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();
        boolean scoped = rateLimitProperties.getScopedPaths().stream()
                .anyMatch(p -> pathMatcher.match(p, uri));
        if (!scoped) {
            filterChain.doFilter(request, response);
            return;
        }

        // scope=IP（直连无前向代理，remoteAddr 即真实客户端）；key=接口路径（纯数字 ID 段归一化，防按 ID 拆桶绕过）
        String ip = request.getRemoteAddr();
        String key = KEY_PREFIX + ip + ":" + normalizeUriForKey(uri);
        boolean allowed;
        try {
            allowed = rateLimitLuaService.tryAcquire(
                    key,
                    rateLimitProperties.getCapacity(),
                    rateLimitProperties.getRatePerSec(),
                    System.currentTimeMillis());
        } catch (DataAccessException e) {
            // Redis 不可达：fail-closed，兜底 5000（不静默放行，避免限流失效时被刷爆）
            log.warn("[ratelimit] redis unavailable ip={} uri={}", ip, uri, e);
            CorsSupport.apply(request, response, properties.getCors().getAllowedOrigins());
            CorsSupport.writeJson(response, HttpServletResponse.SC_OK, 5000, FALLBACK_MESSAGE, objectMapper);
            return;
        }
        if (!allowed) {
            CorsSupport.apply(request, response, properties.getCors().getAllowedOrigins());
            if (log.isDebugEnabled()) {
                log.debug("[ratelimit] rejected ip={} uri={}", ip, uri);
            }
            CorsSupport.writeJson(response, HttpServletResponse.SC_OK, 4290, REJECT_MESSAGE, objectMapper);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 归一化限流 key 中的路径：将形如 /api/seckill/do/69199 的纯数字段替换为 {id}，
     * 使同一接口不同资源 ID 共享一个令牌桶（单 IP 每秒 200 针对整个热点接口，而非每个 ID 独立 200）。
     */
    static String normalizeUriForKey(String uri) {
        List<String> segments = new ArrayList<>();
        for (String segment : uri.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            segments.add(segment.matches("\\d+") ? "{id}" : segment);
        }
        return segments.isEmpty() ? "/" : "/" + String.join("/", segments);
    }
}