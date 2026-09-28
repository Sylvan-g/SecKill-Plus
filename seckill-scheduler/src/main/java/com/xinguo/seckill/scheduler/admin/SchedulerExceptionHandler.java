package com.xinguo.seckill.scheduler.admin;

import com.xinguo.seckill.common.exception.BusinessException;
import com.xinguo.seckill.common.response.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * scheduler 统一异常处理（scheduler 为纯编排，无 DB/Redis/MQ，仅需将业务错误码转回统一 Result）。
 * 内部服务返回的业务码(code!=0)由 AdminService 抛 BusinessException 透传；
 * 不可预期异常兜底 5000。
 */
@RestControllerAdvice
public class SchedulerExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SchedulerExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e) {
        log.error("[scheduler] unexpected error", e);
        return Result.fail(5000, "系统繁忙，请稍后重试");
    }
}