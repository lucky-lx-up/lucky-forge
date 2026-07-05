package com.lucky.luckyforge.infrastructure.persistence.enums;

/**
 * 运行状态（对应 lf_run.status 注释枚举）。
 * <p>PENDING 待执行 / RUNNING 执行中 / SUCCESS 成功 / FAILED 失败。
 */
public enum RunStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED;

    public String value() {
        return name();
    }
}