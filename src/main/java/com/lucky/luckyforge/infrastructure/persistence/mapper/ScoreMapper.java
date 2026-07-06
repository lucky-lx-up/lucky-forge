package com.lucky.luckyforge.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lucky.luckyforge.infrastructure.persistence.entity.Score;
import org.apache.ibatis.annotations.Mapper;

/**
 * Score 数据访问接口（对应 lf_score）。
 */
@Mapper
public interface ScoreMapper extends BaseMapper<Score> {
}
