package com.lucky.luckyforge.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lucky.luckyforge.infrastructure.persistence.entity.PromptLibraryItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 提示词库数据访问接口（对应 lf_prompt_library）。
 * <p>继承 BaseMapper 即得单表 CRUD；逻辑删除由 MyBatis-Plus 全局统一管理。
 */
@Mapper
public interface PromptLibraryItemMapper extends BaseMapper<PromptLibraryItem> {
}
