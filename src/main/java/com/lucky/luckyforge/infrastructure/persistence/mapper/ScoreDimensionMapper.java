package com.lucky.luckyforge.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lucky.luckyforge.infrastructure.persistence.entity.ScoreDimension;
import org.apache.ibatis.annotations.Mapper;

/**
 * ScoreDimension 数据访问接口（对应 lf_score_dimension）。
 */
@Mapper
public interface ScoreDimensionMapper extends BaseMapper<ScoreDimension> {
}
