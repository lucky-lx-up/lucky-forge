package com.lucky.luckyforge.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 风格库实体（对应 lf_style）。
 * <p>gpt-5.5 从参考图提炼出的可复用风格特征；含逻辑删除字段 deletedAt。
 */
@Data
@TableName("lf_style")
public class Style {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 风格名称 */
    private String name;

    /** 垂类：WALLPAPER / AVATAR / POSTER */
    private String vertical;

    /** 风格的自然语言描述 */
    private String description;

    /** 结构化风格特征（JSON 字符串） */
    private String styleJson;

    /** 创建时间（数据库自动） */
    private LocalDateTime createdAt;

    /** 更新时间（数据库自动） */
    private LocalDateTime updatedAt;

    /** 逻辑删除时间，NULL 表示未删除（由 MyBatis-Plus 全局逻辑删除管理） */
    private LocalDateTime deletedAt;
}