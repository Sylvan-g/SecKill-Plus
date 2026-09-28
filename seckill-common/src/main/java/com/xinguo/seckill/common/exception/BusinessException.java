package com.xinguo.seckill.common.exception;

/**
 * 业务异常：携带业务错误码（见需求文档错误码表 4001-4009/4290 等）。
 * 由 GlobalExceptionHandler 统一转换为 Result 返回。
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Integer code;

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}