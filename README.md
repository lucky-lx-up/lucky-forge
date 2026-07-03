# LuckyForge

> 幸运锻造厂 —— AI 驱动的手机壁纸内容工厂

LuckyForge 把“投喂参考图 → 风格提炼 → 提示词生成 → 批量出图 → 自动打分 → 打包素材包”串成一条可反复运行的生产线，半自动化地产出同风格壁纸组（图 + 标题 + 标签），供人工在平台上点发布。

## 核心闭环

```
投喂参考图 → ① 风格提炼 → ② 提示词生成 → ③ 批量出图 → ④ 自动打分 → ⑤ 素材打包
```

每一站的输入输出都落在本地文件目录里，中间结果全部保留、可查可重跑。

## 技术栈

| 维度 | 选型 |
| --- | --- |
| 语言 / 运行时 | Java 21（虚拟线程并发出图） |
| 框架 | Spring Boot 3.5.x |
| 构建 | Maven |
| 工具库 | Lombok |
| 生图 / 分析基础设施 | 自部署 chatgpt2api（gpt-image-2 出图、gpt-5 分析与打分） |
| 数据存储（首版） | 文件系统 + JSON 元数据 |

## 模块划分

| 模块 | 职责 | 依赖 |
| --- | --- | --- |
| StyleAnalyzer | 读参考图，调 gpt-5 提炼风格特征（色调 / 构图 / 主题 / 氛围） | chatgpt2api `/v1/chat/completions` |
| PromptBuilder | 拿风格描述 + 壁纸主题参数，生成多条出图提示词 | chatgpt2api `/v1/chat/completions` |
| ImageGenerator | 拿提示词批量调生图接口，虚拟线程并发，落盘原图 | chatgpt2api `/v1/images/generations` |
| ImageScorer | 读生成图，调 gpt-5 按维度打分，取 Top N | chatgpt2api `/v1/chat/completions` |
| PackageAssembler | 拿高分图 + 风格描述，生成标题 / 标签文案，打包素材包 | chatgpt2api `/v1/chat/completions` |
| PipelineOrchestrator | 串联以上五步，提供命令行入口（`--theme`、`--count` 等） | 以上全部 |

## 数据目录

`workspace/` 是流水线各站数据的落盘目录（已加入 `.gitignore`，不纳入版本库）：

```
workspace/
├── references/   # 投喂的参考图（输入）
├── style/        # 风格提炼结果（JSON）
├── prompts/      # 生成的出图提示词（JSON）
├── raw/          # 原始生成图
├── scored/       # 打分结果（JSON + 图）
└── packages/     # 最终素材包（图 + 标题 + 标签）
```

## 本地运行

前置条件：JDK 21、Maven 3.9+、可访问的自部署 chatgpt2api。

```bash
# Windows
.\mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```

> 流水线命令行入口与 chatgpt2api 连接配置将在实现阶段补全。

## 项目管理

- 变更提案走 [OpenSpec](./openspec/) 流程管理（`openspec/changes`、`openspec/specs`）。
- 项目级 Codex 工作流技能位于 [.codex/skills](./.codex/skills)。

## 项目状态

首版 `0.0.1-SNAPSHOT`：流水线核心闭环设计与实现中。
**License:** MIT
