-- =============================================================================
-- LuckyForge 初始化建表脚本（首版，MySQL 8.0+，utf8mb4）
-- 设计约定：
--   1. 不建外键约束，表间关系靠应用层维护（按业务事实关联）。
--   2. 所有业务表带 vertical 垂类字段（首版默认 WALLPAPER，预留头像/海报等扩展）。
--   3. 图片二进制不入库，表里只存 object_key 指向 MinIO 对象路径。
--   4. 主键统一 BIGINT 自增；时间字段统一 DATETIME，应用层写入 UTC。
--   5. 状态字段用 VARCHAR + 注释枚举值，不用 ENUM（便于无 ALTER 加状态）。
--   6. 逻辑删除统一用 deleted_at（NULL=未删除），不做物理删除。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 建库（首版）：库 lucky_forge，utf8mb4 / utf8mb4_0900_ai_ci
-- 注：本脚本交由用户手动执行，不通过 bash 直插数据（遵循项目约定）。
-- -----------------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS `lucky_forge`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `lucky_forge`;


-- -----------------------------------------------------------------------------
-- 1. style 风格库：gpt-5.5 从参考图提炼出的风格特征，可跨批次复用
--    业务事实：一次提炼、多次引用，是提示词生成与打分汇总的锚点
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `lf_style` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键',
    `name`         VARCHAR(128) NOT NULL                            COMMENT '风格名称（人工或 gpt-5.5 命名）',
    `vertical`     VARCHAR(32)  NOT NULL DEFAULT 'WALLPAPER'        COMMENT '垂类：WALLPAPER 壁纸 / AVATAR 头像 / POSTER 海报',
    `description`  TEXT         NULL                                COMMENT '风格的自然语言描述（色调/构图/主题/氛围）',
    `style_json`   JSON         NULL                                COMMENT '结构化风格特征（色调/构图/主题/氛围等键值），供提示词模板引用',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                             COMMENT '创建时间',
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    `deleted_at`   DATETIME     NULL                                COMMENT '逻辑删除时间，NULL 表示未删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_style_name_vertical` (`name`, `vertical`),
    KEY `idx_style_vertical` (`vertical`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风格库（可复用，gpt-5.5 提炼的风格特征）';

-- -----------------------------------------------------------------------------
-- 2. batch 生产批次：人工发起的一次生产单（业务意图）
--    业务事实：承载本次主题、目标数量、所用风格；一个 batch 可多次运行（run）
--    注意：style_id 允许为空（新风格先跑提炼再回填；直接复用老风格时创建即带）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `lf_batch` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键',
    `style_id`      BIGINT       NULL                                COMMENT '风格 ID（逻辑外键 -> style.id；新风格先跑提炼再回填）',
    `vertical`      VARCHAR(32)  NOT NULL DEFAULT 'WALLPAPER'        COMMENT '垂类',
    `theme`         VARCHAR(255) NULL                                COMMENT '本次主题/意图描述（如 --theme 入参）',
    `target_count`  INT          NOT NULL DEFAULT 8                  COMMENT '目标出图数量（--count 入参）',
    `status`        VARCHAR(32)  NOT NULL DEFAULT 'DRAFT'            COMMENT '批次状态：DRAFT 草稿 / RUNNING 生产中 / DONE 完成 / FAILED 失败',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                             COMMENT '创建时间',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    `deleted_at`    DATETIME     NULL                                COMMENT '逻辑删除时间，NULL 表示未删除',
    PRIMARY KEY (`id`),
    KEY `idx_batch_style` (`style_id`),
    KEY `idx_batch_status` (`status`),
    KEY `idx_batch_vertical` (`vertical`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产批次（人工发起的生产单，承载主题与意图）';

-- -----------------------------------------------------------------------------
-- 3. run 运行记录：流水线的单次执行记录
--    业务事实：一个 batch 可因重跑对应多条 run；承载执行状态、耗时、错误信息
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `lf_run` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键',
    `batch_id`     BIGINT       NOT NULL                            COMMENT '所属批次（逻辑外键 -> batch.id）',
    `status`       VARCHAR(32)  NOT NULL DEFAULT 'PENDING'          COMMENT '运行状态：PENDING 待执行 / RUNNING 执行中 / SUCCESS 成功 / FAILED 失败',
    `current_step` VARCHAR(32)  NULL                                COMMENT '当前所处流水线步骤：STYLE / PROMPT / GENERATE / SCORE / PACKAGE',
    `error`        TEXT         NULL                                COMMENT '失败时的错误信息（便于排查重跑）',
    `started_at`   DATETIME     NULL                                COMMENT '开始执行时间',
    `finished_at`  DATETIME     NULL                                COMMENT '结束时间（成功或失败）',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                             COMMENT '创建时间',
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_run_batch` (`batch_id`),
    KEY `idx_run_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流水线运行记录（承载执行状态、步骤、耗时、错误）';

-- -----------------------------------------------------------------------------
-- 4. reference_image 参考图：人工投喂的输入图
--    业务事实：挂在 batch 下；图片存 MinIO，表里只留 object_key
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `lf_reference_image` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键',
    `batch_id`     BIGINT       NOT NULL                            COMMENT '所属批次（逻辑外键 -> batch.id）',
    `object_key`   VARCHAR(512) NOT NULL                            COMMENT 'MinIO 对象路径（参考图原图）',
    `source`       VARCHAR(32)  NOT NULL DEFAULT 'MANUAL'           COMMENT '来源：MANUAL 人工投喂 / CRAWLER 自动采集（预留）',
    `source_meta`  JSON         NULL                                COMMENT '来源附加信息（采集 URL/时间等），人工投喂时为空',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                             COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_refimg_batch` (`batch_id`),
    KEY `idx_refimg_source` (`source`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='参考图（人工投喂或预留采集的输入图，图片存 MinIO）';

-- -----------------------------------------------------------------------------
-- 5. prompt 出图提示词：PromptBuilder 生成的提示词条目
--    业务事实：挂在 run 下；一个 run 通常多条；是出图的直接输入
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `lf_prompt` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键',
    `run_id`       BIGINT       NOT NULL                            COMMENT '所属运行（逻辑外键 -> run.id）',
    `seq`          INT          NOT NULL DEFAULT 1                  COMMENT '本 run 内序号（便于排序）',
    `content`      TEXT         NOT NULL                            COMMENT '提示词正文（送 gpt-image-2 的 prompt）',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                             COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_prompt_run_seq` (`run_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出图提示词（PromptBuilder 生成，挂运行）';

-- -----------------------------------------------------------------------------
-- 6. generated_image 生成图：ImageGenerator 产出的原始图（打分前）
--    业务事实：挂在 prompt 下；虚拟线程并发产出；图片存 MinIO
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `lf_generated_image` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键',
    `prompt_id`    BIGINT       NOT NULL                            COMMENT '所属提示词（逻辑外键 -> prompt.id）',
    `object_key`   VARCHAR(512) NOT NULL                            COMMENT 'MinIO 对象路径（生成图原图）',
    `width`        INT          NULL                                COMMENT '图宽 px（壁纸场景区分横竖屏用）',
    `height`       INT          NULL                                COMMENT '图高 px',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                             COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_genimg_prompt` (`prompt_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生成图（出图产出，打分前，图片存 MinIO）';

-- -----------------------------------------------------------------------------
-- 7. score 打分记录：gpt-5.5 对单张生成图的整体打分
--    业务事实：一张生成图一条打分记录（1:1）；维度分挂在 score_dimension
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `lf_score` (
    `id`                  BIGINT   NOT NULL AUTO_INCREMENT          COMMENT '主键',
    `generated_image_id`  BIGINT   NOT NULL                         COMMENT '被评生成图（逻辑外键 -> generated_image.id）',
    `total`               DECIMAL(5,2) NULL                         COMMENT '总分（0-100，维度加权或 gpt-5.5 直出）',
    `remark`              TEXT     NULL                             COMMENT 'gpt-5.5 给出的整体评语',
    `created_at`          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP                         COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_score_genimg` (`generated_image_id`)             COMMENT '一张生成图仅一条打分（1:1）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打分记录（gpt-5.5 对生成图的整体评分，与生成图 1:1）';

-- -----------------------------------------------------------------------------
-- 8. score_dimension 维度分：打分维度明细（构图/色彩/清晰度/主题契合度等）
--    业务事实：一张图的打分记录下挂多条维度分；维度可扩展，将来加维度不改表
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `lf_score_dimension` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键',
    `score_id`     BIGINT       NOT NULL                            COMMENT '所属打分记录（逻辑外键 -> score.id）',
    `name`         VARCHAR(64)  NOT NULL                            COMMENT '维度名：composition 构图 / color 色彩 / clarity 清晰度 / relevance 主题契合度 ...',
    `value`        DECIMAL(5,2) NOT NULL                            COMMENT '该维度得分（0-100）',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                             COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_scoredim_score_name` (`score_id`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打分维度明细（维度可扩展，挂打分记录）';

-- -----------------------------------------------------------------------------
-- 9. package 素材包：打包后的成品（图 + 标题 + 标签），人工点发布的对象
--    业务事实：一个 batch 产出若干素材包；标题/标签由 gpt-5.5 生成
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `lf_package` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键',
    `batch_id`      BIGINT       NOT NULL                            COMMENT '所属批次（逻辑外键 -> batch.id）',
    `vertical`      VARCHAR(32)  NOT NULL DEFAULT 'WALLPAPER'        COMMENT '垂类',
    `title`         VARCHAR(255) NOT NULL                            COMMENT '素材包标题（gpt-5.5 生成）',
    `tags`          JSON         NULL                                COMMENT '标签数组（gpt-5.5 生成，如 ["风景","极简"]）',
    `status`        VARCHAR(32)  NOT NULL DEFAULT 'DRAFT'            COMMENT '素材包状态：DRAFT 待发布 / PUBLISHED 已发布',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                             COMMENT '创建时间',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    `deleted_at`    DATETIME     NULL                                COMMENT '逻辑删除时间，NULL 表示未删除',
    PRIMARY KEY (`id`),
    KEY `idx_package_batch` (`batch_id`),
    KEY `idx_package_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='素材包（成品，图+标题+标签，人工发布对象）';

-- -----------------------------------------------------------------------------
-- 10. package_image 包图关联：package 与 generated_image 多对多中间表
--     业务事实：同一张高分图可能进不同主题的素材包；首版多为 1:N 但留 M:N 灵活度
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `lf_package_image` (
    `id`                 BIGINT   NOT NULL AUTO_INCREMENT           COMMENT '主键',
    `package_id`         BIGINT   NOT NULL                          COMMENT '所属素材包（逻辑外键 -> package.id）',
    `generated_image_id` BIGINT   NOT NULL                          COMMENT '入选生成图（逻辑外键 -> generated_image.id）',
    `sort_order`         INT      NOT NULL DEFAULT 0                COMMENT '包内排序（封面/首图置前）',
    `created_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP                         COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pkgimg_pkg_gen` (`package_id`, `generated_image_id`),
    KEY `idx_pkgimg_genimg` (`generated_image_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='素材包与生成图多对多关联';

-- -----------------------------------------------------------------------------
-- 11. publish 发布记录：素材包到平台账号的一次发布动作
--     业务事实：一个包可发多平台多账号（1:N）；首版平台/账号先用字符串字段
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `lf_publish` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键',
    `package_id`    BIGINT       NOT NULL                            COMMENT '所属素材包（逻辑外键 -> package.id）',
    `platform`      VARCHAR(32)  NOT NULL                            COMMENT '发布平台（字符串，首版固定值；多平台后抽表）',
    `account`       VARCHAR(64)  NOT NULL                            COMMENT '发布账号（字符串，首版固定值；多账号后抽表）',
    `status`        VARCHAR(32)  NOT NULL DEFAULT 'DRAFT'            COMMENT '发布状态：DRAFT 草稿 / PUBLISHED 已发布 / FAILED 失败',
    `external_url`  VARCHAR(512) NULL                                COMMENT '发布后的外链（平台帖子地址）',
    `published_at`  DATETIME     NULL                                COMMENT '实际发布时间',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                             COMMENT '创建时间',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_publish_package` (`package_id`),
    KEY `idx_publish_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发布记录（素材包到平台账号的发布动作）';
-- =============================================================================
-- 建表完毕。共 11 张表，按关系链：style <- batch -> run -> prompt -> generated_image -> score -> score_dimension
--                                                     batch -> reference_image（输入）
--                                                     batch -> package -> package_image -> generated_image
--                                                              package -> publish
-- =============================================================================
