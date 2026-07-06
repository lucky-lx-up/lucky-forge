package com.lucky.luckyforge.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lucky.luckyforge.infrastructure.persistence.entity.PackageImage;
import org.apache.ibatis.annotations.Mapper;

/**
 * PackageImage 数据访问接口（对应 lf_package_image）。
 */
@Mapper
public interface PackageImageMapper extends BaseMapper<PackageImage> {
}
