# LuckyForge

> 幸运锻造厂 —— AI 驱动的手机壁纸内容工厂

LuckyForge 把"投喂参考图 → 风格提炼 → 提示词生成 → 批量出图 → 自动打分 → 打包素材包"串成一条可反复运行的生产线，半自动化地产出同风格壁纸组（图 + 标题 + 标签），供人工在平台上点发布。

## 核心闭环

```
投喂参考图 → ① 风格提炼 → ② 提示词生成 → ③ 批量出图 → ④ 自动打分 → ⑤ 素材打包
```

每一站的输入输出都落在 MySQL（元数据）与 MinIO（图片）里，中间结果全部保留、可查可重跑。

## 项目做什么

- 变现模式：内容工厂，自己运营平台账号发布
- 首版垂类：手机壁纸，架构预留向其他垂类（头像 / 海报等）扩展
- 趋势来源：人工投喂参考图，预留自动采集接口
- 发布方式：半自动，系统产出素材包，人工点发布
- 生图与分析：均依赖自部署的 chatgpt2api（gpt-image-2 出图、gpt-5.5 做风格提炼与打分）
- 产出形态：同风格壁纸组 + 标题 + 标签

## 技术栈

| 维度 | 选型 |
| --- | --- |
| 语言 / 运行时 | Java 21（虚拟线程并发出图） |
| 框架 | Spring Boot 3.5.x |
| 构建 | Maven |
| 工具库 | Lombok |
| 生图 / 分析基础设施 | 自部署 chatgpt2api（gpt-image-2 出图、gpt-5.5 分析与打分） |
| 数据存储 | MySQL（元数据，11 张业务表）+ MinIO（图片） |

## 模块划分

| 模块 | 职责 | 依赖 |
| --- | --- | --- |
| StyleAnalyzer | 读参考图，调 gpt-5.5 提炼风格特征（色调 / 构图 / 主题 / 氛围） | chatgpt2api `/v1/chat/completions` |
| PromptBuilder | 拿风格描述 + 壁纸主题参数，生成多条出图提示词 | chatgpt2api `/v1/chat/completions` |
| ImageGenerator | 拿提示词批量调生图接口，虚拟线程并发，图片入 MinIO | chatgpt2api `/v1/images/generations` |
| ImageScorer | 读生成图，调 gpt-5.5 按维度打分，取 Top N | chatgpt2api `/v1/chat/completions` |
| PackageAssembler | 拿高分图 + 风格描述，生成标题 / 标签文案，打包素材包 | chatgpt2api `/v1/chat/completions` |
| PipelineOrchestrator | 串联以上五步，提供命令行入口（`--theme`、`--count` 等） | 以上全部 |

## 数据存储

存储分两层：MySQL 存结构化元数据（11 张业务表，见 `sql/V1__init_schema.sql`），MinIO 存图片二进制（表里只存 `object_key` 指针）。

```
MySQL lucky_forge 库：style / batch / run / reference_image / prompt /
                     generated_image / score / score_dimension /
                     package / package_image / publish
MinIO bucket：        参考图 / 原始生成图 / 打分图 / 素材包成品图
```

## 本地运行

前置条件：JDK 21、Maven 3.9+、可访问的自部署 chatgpt2api、MySQL 8.0+、MinIO。

```bash
# Windows
.\mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run

# 构建
.\mvnw.cmd clean package

# 测试
.\mvnw.cmd test
```

> 流水线命令行入口与 chatgpt2api / MySQL / MinIO 连接配置将在实现阶段补全。

## 工作约定

- 用户语言偏好：中文沟通与注释。
- 代码注释：养成良好的中文注释习惯。
- Lombok 使用：项目中已引入 Lombok，POJO/实体/配置类等统一使用 Lombok 注解（`@Data`、`@Getter`/`@Setter`、`@Builder` 等），禁止手写样板 getter/setter。
- 数据库约束：涉及表结构改动必须提供 SQL 脚本，不得通过 bash 直接向数据库插入数据，SQL 脚本交由用户手动执行。
- 变更流程：重大改动走 OpenSpec 提案（`openspec/`），提案内容尽量使用中文。
- 设计流程：`superpowers:brainstorming` 技能只做设计、不写文档。

## 项目管理

- 变更提案走 [OpenSpec](./openspec/) 流程管理（`openspec/changes`、`openspec/specs`）。

## 项目状态

首版 `0.0.1-SNAPSHOT`：数据模型已定稿（11 张业务表，无外键约束，见 `sql/V1__init_schema.sql`），流水线核心闭环设计与实现中，模块代码尚未实现。
**License:** MIT
