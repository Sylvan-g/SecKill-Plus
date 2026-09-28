package com.xinguo.seckill.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.gateway.config.GatewayProperties;
import com.xinguo.seckill.gateway.config.GatewayProperties.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;

/**
 * 网关路由转发过滤器（需求文档 8 节）：
 * 1. CORS：白名单 origin 镜像放行（复用 CorsSupport），OPTIONS 预检直接返回（不转发上游）
 * 2. 内部接口封闭：/internal/** 一律 403（Q4 落地）
 * 3. 路由透传：按请求前缀转发到目标服务（方法/body/白名单头透传，路径与 query 原样保留）
 * 4. 兜底：上游网络异常/超时 -> HTTP 200 + code 5000（与全站"HTTP 200+业务码"响应体约定一致）
 * 顺序：本过滤器在 RateLimitFilter（HighestPrecedence）之后执行。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ProxyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ProxyFilter.class);

    /** 只透传这些请求头，其余（含 Host/Connection/Content-Length/Accept-Encoding 等 hop-by-hop）丢弃 */
    private static final Set<String> FORWARD_HEADERS =
            Set.of("Authorization", "Content-Type", "Accept", "Accept-Language", "Cookie");

    private final GatewayProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ProxyFilter(GatewayProperties properties, RestTemplate gatewayRestTemplate, ObjectMapper objectMapper) {
        this.properties = properties;
        this.restTemplate = gatewayRestTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 1. CORS 预检短路（浏览器预检不打洞到上游）
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            CorsSupport.apply(request, response, properties.getCors().getAllowedOrigins());
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        String uri = request.getRequestURI();

        // 2. 内部接口封闭（Q4）：精确前缀匹配，防 /internalX 误伤
        if (uri.equals("/internal") || uri.startsWith("/internal/")) {
            CorsSupport.apply(request, response, properties.getCors().getAllowedOrigins());
            CorsSupport.writeJson(response, HttpServletResponse.SC_FORBIDDEN, 403, "禁止访问内部接口", objectMapper);
            return;
        }

        CorsSupport.apply(request, response, properties.getCors().getAllowedOrigins());

        // 3. 路由匹配（配置顺序即优先级）
        Route route = properties.getRoutes().stream()
                .filter(r -> uri.startsWith(r.getPrefix()))
                .findFirst()
                .orElse(null);
        if (route == null) {
            CorsSupport.writeJson(response, HttpServletResponse.SC_NOT_FOUND, 404, "路径不存在", objectMapper);
            return;
        }

        // 3.1 拼目标 URL：base + 原路径 + 原 query
        String target = route.getTarget() + uri;
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            target += "?" + query;
        }

        // 3.2 透传 body 与方法（本系统请求体量小，直接读内存）
        byte[] body = request.getInputStream().readAllBytes();
        HttpHeaders headers = forwardHeaders(request);
        try {
            RequestEntity<byte[]> entity = new RequestEntity<>(
                    body, headers, HttpMethod.valueOf(request.getMethod()), URI.create(target));
            ResponseEntity<byte[]> resp = restTemplate.exchange(entity, byte[].class);
            response.setStatus(resp.getStatusCode().value());
            if (resp.getHeaders().getContentType() != null) {
                response.setContentType(resp.getHeaders().getContentType().toString());
            }
            byte[] respBody = resp.getBody() == null ? new byte[0] : resp.getBody();
            response.getOutputStream().write(respBody);
        } catch (RestClientException | IllegalArgumentException e) {
            // 上游不可达/超时/URI 非法：对外统一业务码 5000，不泄漏内部细节（与 T11 P1-2 口径一致）
            log.warn("[gateway] upstream call failed uri={} target={}", uri, target, e);
            CorsSupport.writeJson(response, HttpServletResponse.SC_OK, 5000, "系统繁忙，请稍后重试", objectMapper);
        }
    }

    private HttpHeaders forwardHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        for (String name : FORWARD_HEADERS) {
            String value = request.getHeader(name);
            if (value != null && !value.isEmpty()) {
                headers.set(name, value);
            }
        }
        return headers;
    }
}