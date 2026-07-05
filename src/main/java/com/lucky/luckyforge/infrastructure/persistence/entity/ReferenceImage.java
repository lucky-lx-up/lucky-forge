package com.lucky.luckyforge.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 参考图实体（对应 lf_reference_image）。
 * <p>人工投喂的输入图；图片存 MinIO，表里只存 objectKey 指针。
 */
@Data
@TableName("lf_reference_image")
public class ReferenceImage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属批次（逻辑外键） */
    private Long batchId;

    /** MinIO 对象路径 */
    private String objectKey;

    /** 来源：MANUAL 人工投喂 / CRAWLER 自动采集（预留） */
    private String source;

    /** 来源附加信息（JSON 字符串，采集 URL/时间等） */
    private String sourceMeta;

    private LocalDateTime createdAt;
}