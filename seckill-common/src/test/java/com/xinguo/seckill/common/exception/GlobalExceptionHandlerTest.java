package com.xinguo.seckill.common.exception;

import com.xinguo.seckill.common.response.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * GlobalExceptionHandler 外部行为测试：业务码 -> HTTP 结构 {code, message, data}。
 * 直接调用 handler 方法验证返回结构，不依赖 Spring 容器。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionMapsToItsCode() {
        ResponseEntity<Result<Void>> resp = handler.handleBusinessException(new BusinessException(4001, "手慢了，库存不足"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(4001, resp.getBody().getCode());
        assertEquals("手慢了，库存不足", resp.getBody().getMessage());
        assertNull(resp.getBody().getData());
    }

    @Test
    void genericExceptionMapsTo5000() {
        ResponseEntity<Result<Void>> resp = handler.handleException(new RuntimeException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(5000, resp.getBody().getCode());
        assertEquals("系统内部错误", resp.getBody().getMessage());
        assertNull(resp.getBody().getData());
    }
}