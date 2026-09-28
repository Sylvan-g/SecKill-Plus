package com.xinguo.seckill.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.common.response.Result;
import com.xinguo.seckill.gateway.config.GatewayProperties;
import com.xinguo.seckill.gateway.config.GatewayProperties.Route;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网关路由过滤器单测：重点覆盖
 * - CORS 白名单/预检短路（review：避免任意 Origin 透传、OPTIONS 不打洞到上游）
 * - /internal/** 封闭（review：精确前缀，防 /internalX 误伤）
 * - 透传与兜底（review：only 白名单头透传、上游异常统一 200+5000 不泄漏）
 */
class ProxyFilterTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    private GatewayProperties props;
    private RestTemplate restTemplate;
    private ObjectMapper om;
    private ProxyFilter filter;

    @BeforeEach
    void setUp() {
        props = new GatewayProperties();
        Route goods = new Route();
        goods.setPrefix("/api/goods/");
        goods.setTarget("http://gw:8200");
        props.setRoutes(List.of(goods));
        props.getCors().setAllowedOrigins(List.of(ALLOWED_ORIGIN));
        restTemplate = mock(RestTemplate.class);
        om = new ObjectMapper();
        filter = new ProxyFilter(props, restTemplate, om);
    }

    private MockHttpServletResponse exec(String method, String uri, String origin) throws Exception {
        return exec(method, uri, origin, null, null);
    }

    private MockHttpServletResponse exec(String method, String uri, String origin, String headerName, String headerValue)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        if (origin != null) {
            req.addHeader("Origin", origin);
        }
        if (headerName != null && headerValue != null) {
            req.addHeader(headerName, headerValue);
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, (r, s) -> {
        });
        return resp;
    }

    // ---------- CORS（重点①） ----------

    @Test
    void optionsPreflightAcceptedWithoutUpstreamCall() throws Exception {
        MockHttpServletResponse resp = exec(HttpMethod.OPTIONS.name(), "/api/goods/activity/list", ALLOWED_ORIGIN);

        assertEquals(HttpStatus.OK.value(), resp.getStatus());
        assertEquals(ALLOWED_ORIGIN, resp.getHeader("Access-Control-Allow-Origin"));
        verify(restTemplate, never()).exchange(any(RequestEntity.class), eq(byte[].class));
    }

    @Test
    void nonWhitelistedOriginGetsNoCorsHeaders() throws Exception {
        MockHttpServletResponse resp = exec(HttpMethod.OPTIONS.name(), "/api/goods/activity/list", "http://evil.com");

        assertEquals(HttpStatus.OK.value(), resp.getStatus());
        assertNull(resp.getHeader("Access-Control-Allow-Origin"), "非白名单 origin 不得获得 CORS 放行头");
    }

    // ---------- /internal 封闭（重点②） ----------

    @Test
    void internalPathRejected403() throws Exception {
        MockHttpServletResponse resp = exec(HttpMethod.GET.name(), "/internal/order/timeout-list", null);

        assertEquals(HttpStatus.FORBIDDEN.value(), resp.getStatus());
        Result<?> r = om.readValue(resp.getContentAsByteArray(), Result.class);
        assertEquals(403, r.getCode());
        verify(restTemplate, never()).exchange(any(RequestEntity.class), eq(byte[].class));
    }

    @Test
    void internalLikePathNotMistaken() throws Exception {
        // /internals 不是内部接口前缀，也未路由 -> 404（证明 /internal 判定是精确前缀，非 startsWith 整体）
        MockHttpServletResponse resp = exec(HttpMethod.GET.name(), "/internals/x", null);

        assertEquals(HttpStatus.NOT_FOUND.value(), resp.getStatus());
    }

    // ---------- 路由透传（重点③） ----------

    @Test
    void routesToTargetWithQueryAndBody() throws Exception {
        when(restTemplate.exchange(any(RequestEntity.class), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>("{\"code\":0}".getBytes(StandardCharsets.UTF_8), HttpStatus.OK));

        MockHttpServletResponse resp = exec(HttpMethod.POST.name(), "/api/goods/activity/list?status=1", null,
                "Authorization", "Bearer tok-123");

        ArgumentCaptor<RequestEntity<byte[]>> captor = ArgumentCaptor.forClass(RequestEntity.class);
        verify(restTemplate).exchange(captor.capture(), eq(byte[].class));
        RequestEntity<byte[]> sent = captor.getValue();
        assertEquals("http://gw:8200/api/goods/activity/list?status=1", sent.getUrl().toString());
        // Authorization 透传，Host 等 hop-by-hop 不携带
        assertEquals("Bearer tok-123", sent.getHeaders().getFirst("Authorization"));
        assertNull(sent.getHeaders().getFirst("Host"));
    }

    @Test
    void upstream500StatusAndBodyPassedThrough() throws Exception {
        byte[] body = "{\"code\":5000,\"message\":\"系统内部错误\"}".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<byte[]> respEntity = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        when(restTemplate.exchange(any(RequestEntity.class), eq(byte[].class))).thenReturn(respEntity);

        MockHttpServletResponse resp = exec(HttpMethod.GET.name(), "/api/goods/activity/list", null);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), resp.getStatus());
        assertEquals("{\"code\":5000,\"message\":\"系统内部错误\"}", resp.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void upstreamUnreachableFallsBackTo200AndCode5000() throws Exception {
        when(restTemplate.exchange(any(RequestEntity.class), eq(byte[].class)))
                .thenThrow(new RestClientException("Connection refused: localhost:8300"));

        MockHttpServletResponse resp = exec(HttpMethod.GET.name(), "/api/goods/activity/list", null);

        assertEquals(HttpStatus.OK.value(), resp.getStatus());
        Result<?> r = om.readValue(resp.getContentAsByteArray(), Result.class);
        assertEquals(5000, r.getCode());
        // 兜底文案模糊化，不泄漏内部 URL/异常细节
        assertTrue(!String.valueOf(r.getMessage()).contains("localhost"),
                "响应不得泄漏内部连接地址: " + r.getMessage());
    }

    @Test
    void unknownRouteReturns404() throws Exception {
        MockHttpServletResponse resp = exec(HttpMethod.GET.name(), "/api/nonexist/1", null);

        assertEquals(HttpStatus.NOT_FOUND.value(), resp.getStatus());
        Result<?> r = om.readValue(resp.getContentAsByteArray(), Result.class);
        assertEquals(404, r.getCode());
    }
}