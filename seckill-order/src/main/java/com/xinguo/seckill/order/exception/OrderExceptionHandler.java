package com.xinguo.seckill.order.exception;

import cn.dev33.satoken.exception.NotLoginException;
import com.xinguo.seckill.common.response.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * order 服务专属异常处理：把 Sa-Token 未登录异常统一映射为 HTTP 401 + {code:401}
 * （common 的 GlobalExceptionHandler 不感知 Sa-Token，故在本服务补充）。
 */
@RestControllerAdvice
public class OrderExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderExceptionHandler.class);

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<Void>> handleNotLogin(NotLoginException e) {
        log.debug("Not login: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.fail(401, "未登录或登录已失效"));
    }
}