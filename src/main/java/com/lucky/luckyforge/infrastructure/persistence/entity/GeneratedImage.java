package com.lucky.luckyforge.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生成图实体（对应 lf_generated_image）。
 * <p>出图产出的原始图（打分前）；图片存 MinIO，表里只存 objectKey。
 */
@Data
@TableName("lf_generated_image")
public class GeneratedImage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属提示词（逻辑外键） */
    private Long promptId;

    /** MinIO 对象路径 */
    private String objectKey;

    /** 图宽 px */
    private Integer width;

    /** 图高 px */
    private Integer height;

    private LocalDateTime createdAt;
}