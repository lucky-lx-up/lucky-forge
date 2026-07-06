package com.lucky.luckyforge.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 素材包与生成图关联实体（对应 lf_package_image）。
 * <p>package 与 generated_image 的多对多中间表；首版一个 package 关联多张图（1:N 退化使用）。
 * sort_order 决定包内排序（0 为封面）。
 */
@Data
@TableName("lf_package_image")
public class PackageImage {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属素材包（逻辑外键 -> package.id） */
    private Long packageId;

    /** 入选生成图（逻辑外键 -> generated_image.id） */
    private Long generatedImageId;

    /** 包内排序（0 为封面/首图，递增） */
    private Integer sortOrder;

    private LocalDateTime createdAt;
}
