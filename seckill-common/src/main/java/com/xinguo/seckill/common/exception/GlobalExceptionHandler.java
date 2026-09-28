package com.xinguo.seckill.common.exception;

import com.xinguo.seckill.common.response.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理（放在 common 库中，由各微服务 Spring 容器扫描注册）。
 * 注意：各服务启动类需 @SpringBootApplication(scanBasePackages = "com.xinguo.seckill")
 * 才能扫描到本类，否则不生效。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常 -> HTTP 200 + 业务码（前端按 code 分支展示）
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        return ResponseEntity.ok(Result.fail(e.getCode(), e.getMessage()));
    }

    /**
     * 兜底异常 -> HTTP 500 + code 5000
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Result.fail(5000, "系统内部错误"));
    }
}