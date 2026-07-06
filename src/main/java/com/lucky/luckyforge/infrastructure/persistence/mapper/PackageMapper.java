package com.lucky.luckyforge.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lucky.luckyforge.infrastructure.persistence.entity.Package;
import org.apache.ibatis.annotations.Mapper;

/**
 * Package 数据访问接口（对应 lf_package）。
 * <p>注意：import com.lucky.luckyforge.infrastructure.persistence.entity.Package。
 */
@Mapper
public interface PackageMapper extends BaseMapper<Package> {
}
