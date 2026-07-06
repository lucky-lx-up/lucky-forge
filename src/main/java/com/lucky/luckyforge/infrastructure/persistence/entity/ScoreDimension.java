package com.lucky.luckyforge.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 打分维度明细实体（对应 lf_score_dimension）。
 * <p>一张图的打分记录下挂多条维度分；维度可扩展（(score_id, name) 唯一）。
 * 首版固定 4 维度：composition 构图 / color 色彩 / clarity 清晰度 / relevance 主题契合度。
 */
@Data
@TableName("lf_score_dimension")
public class ScoreDimension {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属打分记录（逻辑外键 -> score.id） */
    private Long scoreId;

    /** 维度名：composition / color / clarity / relevance ... */
    private String name;

    /** 该维度得分（0-100，DECIMAL(5,2)） */
    private BigDecimal value;

    /** 创建时间（数据库自动） */
    private LocalDateTime createdAt;
}
