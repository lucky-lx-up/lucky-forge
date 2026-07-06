package com.lucky.luckyforge.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 打分记录实体（对应 lf_score）。
 * <p>gpt-5.5 对单张生成图的整体打分；与 lf_generated_image 1:1（uk_score_genimg）。
 * total 为 0-100 的总分，维度分明细挂 lf_score_dimension。
 */
@Data
@TableName("lf_score")
public class Score {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 被评生成图（逻辑外键 -> generated_image.id，唯一） */
    private Long generatedImageId;

    /** 总分（0-100，DECIMAL(5,2)） */
    private BigDecimal total;

    /** gpt-5.5 给出的整体评语 */
    private String remark;

    /** 创建时间（数据库自动） */
    private LocalDateTime createdAt;
}
