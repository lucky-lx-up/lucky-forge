-- =============================================================================
-- LuckyForge 增量脚本 V2：提示词库（lf_prompt_library）
-- 设计背景：
--   流水线每次都走"风格分析 → 提示词生成 → 出图"，提示词不稳定。
--   把验证过的好提示词沉淀成可复用资产，挂风格、跨批次复用，效果更稳定。
-- 复用机制：
--   提示词库是独立表，不动 lf_prompt；工作台出图时把库提示词作为新 prompt
--   写入 lf_prompt，复用现有 ImageGenerator / ImageScorer（零改动）。
-- 设计约定（沿用 V1）：
--   1. 不建外键，靠应用层维护关系（style_id -> lf_style.id）。
--   2. tags 用 JSON 数组（与 lf_style.style_json / lf_package.tags 一致）。
--   3. 逻辑删除用 deleted_at（NULL=未删除），由 MyBatis-Plus 全局统一管理。
-- =============================================================================
SET NAMES utf8mb4;
USE `lucky_forge`;

-- -----------------------------------------------------------------------------
-- 12. prompt_library 提示词库：从工作台出图验证后人工归档的好提示词，挂风格、可复用
--     业务事实：一条库提示词挂一个风格；工作台勾选若干条直接出图（跳过风格分析和提示词生成）；
--              vertical 冗余存储（归档时继承自风格），便于检索和出图时直接用。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `lf_prompt_library` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键',
    `style_id`         BIGINT       NOT NULL                            COMMENT '所属风格（逻辑外键 -> lf_style.id）',
    `content`          TEXT         NOT NULL                            COMMENT '提示词正文（送 gpt-image-2 的 prompt）',
    `vertical`         VARCHAR(32)  NOT NULL DEFAULT 'WALLPAPER'        COMMENT '垂类（归档时继承自风格，冗余存储便于检索/出图）',
    `note`             VARCHAR(500) NULL                                COMMENT '用户备注（如"适合夜景""色彩饱和度高"）',
    `tags`             JSON         NULL                                COMMENT '用户标签数组（如 ["夜景","高对比"]）',
    `source_prompt_id` BIGINT       NULL                                COMMENT '来源提示词 ID（逻辑外键 -> lf_prompt.id；归档追溯用，手动录入则为空）',
    `usage_count`      INT          NOT NULL DEFAULT 0                  COMMENT '累计被工作台引用出图次数（统计用）',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                             COMMENT '创建时间',
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    `deleted_at`       DATETIME     NULL                                COMMENT '逻辑删除时间，NULL 表示未删除',
    PRIMARY KEY (`id`),
    KEY `idx_promptlib_style` (`style_id`),
    KEY `idx_promptlib_vertical` (`vertical`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提示词库（按风格沉淀的已验证可复用提示词）';
