# 素材包详情页展示提示词 + 加入提示词库

## 背景

提示词库当前是独立闭环（库内出图 → 归档），和主批次流水线没有打通。用户跑完批次、看到满意的素材包，却找不到入口把提示词存进库复用。

**数据关系澄清**：一个素材包含多张图，每张图对应一条独立的提示词（N 条提示词各出 1 张图，打分后选 TopN 打包）。用户按需归档满意的提示词。

## 方案

### 后端

#### 1. PackageImageDetail DTO 加提示词字段

`PackageImageDetail` 新增 `promptId`（Long）和 `promptContent`（String）：

```java
public record PackageImageDetail(
        Long generatedImageId,
        String objectKey,
        Integer sortOrder,
        String previewUrl,
        BigDecimal score,
        String remark,
        List<DimensionScore> dimensions,
        Long promptId,          // 新增：该图对应的提示词 id
        String promptContent    // 新增：提示词正文
) {}
```

#### 2. getPackageDetail 查询补全提示词

`PackageQueryServiceImpl.getPackageDetail` 已有 `generated_image` 的 id 列表。补一步：批量查这些 generated_image 的 promptId（GeneratedImage 实体已有 promptId 字段），再批量查 prompt 内容。组装时填入 PackageImageDetail。

无需新增 Mapper 方法——GeneratedImageMapper.selectList + PromptMapper.selectList 已有（BaseMapper）。

#### 3. 归档接口支持只传 promptId（简化）

现有 `archiveFromRun` 需要 `runId + items[{promptId}]`，且假设所有 prompt 属于同一 run。为不破坏它（LibraryRunDetail 页面仍在用），**新增一个按 promptId 直接归档的接口**：

`POST /api/prompt-library/archive-prompts`，请求体 `ArchivePromptsRequest(List<Long> promptIds)`。后端按每个 promptId 查 `prompt.runId` → `run.batchId` → `batch.styleId/vertical` → 继承风格信息归档。promptId 不存在或 batch 无 styleId 的跳过并记录。

新增：`ArchivePromptsRequest` DTO、`PromptLibraryService.archivePrompts(List<Long> promptIds)` 方法、Controller 端点。不动现有 archiveFromRun。

### 前端

#### PackageDetail.vue

每张图卡片下方（维度分明细之后）加：
- 提示词文本区（显示 promptContent，带复制按钮）
- "加入提示词库"按钮 → 调 `archivePrompts([promptId])`（新接口）

归档成功后按钮变为"✓ 已加入"，或弹提示。可选：加 note/tags 输入（简单起见可先不加，归档后到提示词库再编辑）。

#### api/index.js

新增 `archivePrompts(promptIds)` — POST `/prompt-library/archive-prompts`，body `{ promptIds }`。

### 不改的

- 素材包的 package_image / generated_image / prompt 表结构不变。
- 提示词库的库内出图 → 归档闭环不变。
- 归档接口的归档保护、重复归档逻辑不变。

## 影响面

- 后端：PackageImageDetail DTO、PackageQueryServiceImpl、ArchivePromptsRequest DTO（新）、PromptLibraryService/Impl（新增 archivePrompts）、PromptLibraryController（新增端点）
- 前端：PackageDetail.vue、api/index.js
- 不涉及 DB schema、不动现有 archiveFromRun。
- 不涉及 DB schema。
