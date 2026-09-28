package com.xinguo.seckill.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinguo.seckill.common.response.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.List;

/**
 * 网关过滤器公共工具：CORS 白名单头 + 统一 Result 兜底写回。
 * 供 RateLimitFilter（限流拒绝）与 ProxyFilter（转发失败兜底）复用，避免两处 CORS 逻辑漂移。
 */
final class CorsSupport {

    private static final String CORS_ALLOW_METHODS = "GET,POST,PUT,DELETE,OPTIONS";
    private static final String CORS_ALLOW_HEADERS = "Authorization,Content-Type,Accept";

    private CorsSupport() {
    }

    /** CORS：仅白名单 origin 镜像放行；未命中不加 CORS 头（浏览器自行拦截，防凭证随意透传） */
    static void apply(HttpServletRequest request, HttpServletResponse response, List<String> allowedOrigins) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isEmpty() || allowedOrigins == null) {
            return;
        }
        if (allowedOrigins.contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Methods", CORS_ALLOW_METHODS);
            response.setHeader("Access-Control-Allow-Headers", CORS_ALLOW_HEADERS);
        }
    }

    /** 写回统一 Result JSON（HTTP 状态可自定义；业务码遵循需求第 11 节） */
    static void writeJson(HttpServletResponse response, int httpStatus, int code, String message,
                          ObjectMapper objectMapper) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, message)));
    }
}