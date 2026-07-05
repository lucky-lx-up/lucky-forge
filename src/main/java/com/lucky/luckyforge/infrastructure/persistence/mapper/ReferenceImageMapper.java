package com.lucky.luckyforge.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lucky.luckyforge.infrastructure.persistence.entity.ReferenceImage;
import org.apache.ibatis.annotations.Mapper;

/**
 * ReferenceImage 数据访问接口（对应 lf_referenceimage）。
 * <p>继承 BaseMapper 即得单表 CRUD；联表/聚合查询按需在后续模块追加自定义方法。
 */
@Mapper
public interface ReferenceImageMapper extends BaseMapper<ReferenceImage> {
}