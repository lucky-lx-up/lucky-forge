package com.lucky.luckyforge.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流水线运行记录实体（对应 lf_run）。
 * <p>承载单次执行的状态、当前步骤、耗时与错误信息；无逻辑删除（执行事实数据）。
 */
@Data
@TableName("lf_run")
public class Run {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属批次（逻辑外键） */
    private Long batchId;

    /** 运行状态：PENDING/RUNNING/SUCCESS/FAILED */
    private String status;

    /** 当前所处流水线步骤：STYLE/PROMPT/GENERATE/SCORE/PACKAGE */
    private String currentStep;

    /** 失败时的错误信息 */
    private String error;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}