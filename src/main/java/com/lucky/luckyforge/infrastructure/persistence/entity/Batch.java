package com.lucky.luckyforge.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生产批次实体（对应 lf_batch）。
 * <p>人工发起的生产单；styleId 可为空（新风格先跑提炼再回填）；含逻辑删除字段。
 */
@Data
@TableName("lf_batch")
public class Batch {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 风格 ID（逻辑外键，可空） */
    private Long styleId;

    /** 垂类 */
    private String vertical;

    /** 本次主题/意图描述 */
    private String theme;

    /** 目标出图数量 */
    private Integer targetCount;

    /** 批次状态：DRAFT/RUNNING/DONE/FAILED */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 逻辑删除时间，NULL 表示未删除 */
    private LocalDateTime deletedAt;
}