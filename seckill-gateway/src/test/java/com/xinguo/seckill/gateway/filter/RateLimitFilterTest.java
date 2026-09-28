package com.xinguo.seckill.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.common.response.Result;
import com.xinguo.seckill.gateway.config.GatewayProperties;
import com.xinguo.seckill.gateway.config.RateLimitProperties;
import com.xinguo.seckill.gateway.ratelimit.RateLimitLuaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网关限流过滤器单测（mock Lua 服务）：
 * - 只对 scoped-paths 做限流（读接口/常规接口放行且不触 Lua）
 * - 令牌充足放行 / 令牌耗尽返回 200+4290
 * - 限流 key 按 IP+接口 构造
 */
class RateLimitFilterTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    private GatewayProperties props;
    private RateLimitProperties rateLimit;
    private RateLimitLuaService rateLimitLuaService;
    private ObjectMapper om;
    private RateLimitFilter filter;

    private final AtomicBoolean chained = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        props = new GatewayProperties();
        rateLimit = new RateLimitProperties();
        rateLimit.setScopedPaths(List.of("/api/seckill/**", "/api/order/pay/**"));
        props.getCors().setAllowedOrigins(List.of(ALLOWED_ORIGIN));
        rateLimitLuaService = mock(RateLimitLuaService.class);
        om = new ObjectMapper();
        filter = new RateLimitFilter(props, rateLimit, rateLimitLuaService, om);
    }

    private MockHttpServletResponse exec(String uri, String origin) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        if (origin != null) {
            req.addHeader("Origin", origin);
        }
        chained.set(false);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, (r, s) -> chained.set(true));
        return resp;
    }

    @Test
    void nonScopedReadPathSkipsLuaAndChains() throws Exception {
        MockHttpServletResponse resp = exec("/api/goods/activity/list?status=1", null);

        verify(rateLimitLuaService, never()).tryAcquire(anyString(), anyInt(), anyDouble(), anyLong());
        assertTrue(chained.get(), "非限流路径应放行到下游过滤器");
    }

    @Test
    void tokenGrantedChainsToNextFilter() throws Exception {
        when(rateLimitLuaService.tryAcquire(anyString(), anyInt(), anyDouble(), anyLong())).thenReturn(true);

        MockHttpServletResponse resp = exec("/api/seckill/do/1", null);

        assertTrue(chained.get());
    }

    @Test
    void bucketEmptyReturns4290WithCorsHeaders() throws Exception {
        when(rateLimitLuaService.tryAcquire(anyString(), anyInt(), anyDouble(), anyLong())).thenReturn(false);

        MockHttpServletResponse resp = exec("/api/seckill/do/1", ALLOWED_ORIGIN);

        assertEquals(200, resp.getStatus());
        Result<?> r = om.readValue(resp.getContentAsByteArray(), Result.class);
        assertEquals(4290, r.getCode());
        assertEquals(ALLOWED_ORIGIN, resp.getHeader("Access-Control-Allow-Origin"), "限流拒绝也应携带 CORS 头");
        assertFalse(chained.get(), "限流拒绝不得转发上游");
    }

    @Test
    void keyUsesIpAndInterfacePath() throws Exception {
        when(rateLimitLuaService.tryAcquire(anyString(), anyInt(), anyDouble(), anyLong())).thenReturn(true);

        exec("/api/order/pay/SK123", null);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimitLuaService).tryAcquire(key.capture(), anyInt(), anyDouble(), anyLong());
        assertEquals("seckill:ratelimit:ip:127.0.0.1:/api/order/pay/SK123", key.getValue());
    }

    @Test
    void numericIdPathSegmentNormalizedToPlaceholder() throws Exception {
        when(rateLimitLuaService.tryAcquire(anyString(), anyInt(), anyDouble(), anyLong())).thenReturn(true);

        exec("/api/seckill/do/69199", null);
        exec("/api/seckill/do/69201", null);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimitLuaService, org.mockito.Mockito.times(2))
                .tryAcquire(key.capture(), anyInt(), anyDouble(), anyLong());
        // 不同活动 ID 必须归一化到同一令牌桶，防按 ID 拆桶绕过
        assertEquals("seckill:ratelimit:ip:127.0.0.1:/api/seckill/do/{id}", key.getAllValues().get(0));
        assertEquals("seckill:ratelimit:ip:127.0.0.1:/api/seckill/do/{id}", key.getAllValues().get(1));
    }

    @Test
    void optionsPreflightBypassesRateLimit() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/seckill/do/69199");
        chained.set(false);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, (r, s) -> chained.set(true));

        verify(rateLimitLuaService, never()).tryAcquire(anyString(), anyInt(), anyDouble(), anyLong());
        assertTrue(chained.get(), "OPTIONS 预检应放行且不消耗令牌");
    }

    @Test
    void redisUnavailableFallsBackTo5000() throws Exception {
        doThrow(new RedisConnectionFailureException("simulated down"))
                .when(rateLimitLuaService).tryAcquire(anyString(), anyInt(), anyDouble(), anyLong());

        MockHttpServletResponse resp = exec("/api/seckill/do/69199", ALLOWED_ORIGIN);

        assertEquals(200, resp.getStatus(), "Redis 不可达应 fail-closed 返回 200+5000");
        Result<?> r = om.readValue(resp.getContentAsByteArray(), Result.class);
        assertEquals(5000, r.getCode());
        assertFalse(chained.get(), "Redis 异常不得放行到下游");
    }

    @Test
    void disabledSkipsAllChecks() throws Exception {
        rateLimit.setEnabled(false);

        exec("/api/seckill/do/1", null);

        verify(rateLimitLuaService, never()).tryAcquire(anyString(), anyInt(), anyDouble(), anyLong());
    }
}