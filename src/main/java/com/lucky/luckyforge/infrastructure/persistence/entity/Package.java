package com.lucky.luckyforge.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 素材包实体（对应 lf_package）。
 * <p>打包后的成品：Top N 高分图 + gpt-5.5 生成的标题/标签。
 * 含逻辑删除字段 deletedAt（MyBatis-Plus 全局逻辑删除自动生效）。
 *
 * <p>注意：类名 Package 与 java.lang.Package 重名，引用时需注意 import。
 * 本项目所有实体在 persistence.entity 包下，调用方按需 import，无歧义。
 */
@Data
@TableName("lf_package")
public class Package {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属批次（逻辑外键 -> batch.id） */
    private Long batchId;

    /** 垂类 */
    private String vertical;

    /** 素材包标题（gpt-5.5 生成） */
    private String title;

    /** 标签数组（JSON 字符串，如 ["渐变","极简"]） */
    private String tags;

    /** 素材包状态：DRAFT 待发布 / PUBLISHED 已发布 */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 逻辑删除时间，NULL 表示未删除（由 MyBatis-Plus 全局逻辑删除管理） */
    private LocalDateTime deletedAt;
}
