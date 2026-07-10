package com.lucky.luckyforge.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提示词库条目实体（对应 lf_prompt_library）。
 * <p>从工作台出图验证后人工归档的好提示词，挂风格、可跨批次复用。
 * <p>tags 字段为 JSON 字符串（JSON 数组，如 {@code ["夜景","高对比"]}），由应用层用 ObjectMapper 解析。
 */
@Data
@TableName("lf_prompt_library")
public class PromptLibraryItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属风格（逻辑外键 -> lf_style.id） */
    private Long styleId;

    /** 提示词正文（送 gpt-image-2 的 prompt） */
    private String content;

    /** 垂类（归档时继承自风格，冗余存储便于检索/出图） */
    private String vertical;

    /** 用户备注（如"适合夜景"） */
    private String note;

    /** 用户标签 JSON 数组字符串（如 {@code ["夜景","高对比"]}） */
    private String tags;

    /** 来源提示词 ID（逻辑外键 -> lf_prompt.id；归档追溯用，手动录入则为空） */
    private Long sourcePromptId;

    /** 累计被工作台引用出图次数（统计用） */
    private Integer usageCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 逻辑删除时间，NULL 表示未删除 */
    private LocalDateTime deletedAt;
}
