package com.lucky.luckyforge.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lucky.luckyforge.infrastructure.persistence.entity.GeneratedImage;
import org.apache.ibatis.annotations.Mapper;

/**
 * GeneratedImage 数据访问接口（对应 lf_generatedimage）。
 * <p>继承 BaseMapper 即得单表 CRUD；联表/聚合查询按需在后续模块追加自定义方法。
 */
@Mapper
public interface GeneratedImageMapper extends BaseMapper<GeneratedImage> {
}