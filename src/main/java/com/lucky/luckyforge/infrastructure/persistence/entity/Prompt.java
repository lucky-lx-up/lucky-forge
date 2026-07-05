package com.lucky.luckyforge.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 出图提示词实体（对应 lf_prompt）。
 * <p>PromptBuilder 生成，挂运行；一个 run 通常多条。
 */
@Data
@TableName("lf_prompt")
public class Prompt {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属运行（逻辑外键） */
    private Long runId;

    /** 本 run 内序号 */
    private Integer seq;

    /** 提示词正文 */
    private String content;

    private LocalDateTime createdAt;
}