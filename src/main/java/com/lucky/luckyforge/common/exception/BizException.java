package com.lucky.luckyforge.common.exception;

/**
 * 通用业务异常基类。
 * <p>供后续流水线模块在业务规则不满足时抛出，首版仅作预留。
 */
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }

    public BizException(String message, Throwable cause) {
        super(message, cause);
    }
}