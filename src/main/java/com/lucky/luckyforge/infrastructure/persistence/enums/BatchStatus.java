package com.lucky.luckyforge.infrastructure.persistence.enums;

/**
 * 批次状态（对应 lf_batch.status 注释枚举）。
 * <p>DRAFT 草稿 / RUNNING 生产中 / DONE 完成 / FAILED 失败。
 * 值与建表脚本注释一一对应，避免裸字符串拼写错误。
 */
public enum BatchStatus {
    DRAFT,
    RUNNING,
    DONE,
    FAILED;

    /** 数据库存储值（枚举名） */
    public String value() {
        return name();
    }
}