# 执行流水线时灵活选择生成张数

## 背景

目前"生成张数"在创建批次时定死（`batch.targetCount`，默认 4，范围 1-12），每次执行流水线都用这个固定值。用户希望在 BatchDetail 页面"一键执行"时临时选择本次的张数，不用受限于批次创建时的设定。

## 修复方案（思路 A：count 参数透传）

`POST /api/batches/{id}/pipeline` 加可选的 `count` 参数。`executeAsync(batchId, count)` 透传到 `execute(batchId, count)`，`execute` 里调 PromptBuilder 时用 count 覆盖 batch.targetCount，PackageAssembler 的 TopN 也用 count。**count 不持久化**，只影响本次执行；不传则回退 batch.targetCount。

### 后端改动

#### 1. DTO：PipelineExecuteRequest

新增 record（`application/pipeline/dto/PipelineExecuteRequest.java`）：

```java
public record PipelineExecuteRequest(Integer count) {
    public PipelineExecuteRequest {
        if (count != null && (count < 1 || count > 12)) {
            throw new IllegalArgumentException("count 范围 1-12");
        }
    }
}
```

#### 2. Controller

`POST /{batchId}/pipeline` 加 `@RequestBody(required = false) PipelineExecuteRequest request`，提取 count 传给 service。

#### 3. Service 接口 + 实现

- `PipelineOrchestratorService.executeAsync(Long batchId, Integer count)` — 新增 count 参数。
- `execute(Long batchId, Integer count)` — 内部透传。
- `execute` 里步骤② PromptBuilder：`generatePrompts(batchId, new PromptGenerationRequest(count))`（原来是 `null`）。
- `execute` 里步骤⑤ PackageAssembler：assemble 的 TopN 改用传入 count 而非 batch.targetCount。

PackageAssembler.assemble 需要能接收 count —— 加一个重载 `assemble(Long runId, Integer count)`，count 非空时用它做 TopN，否则回退 batch.targetCount（保持兼容）。

### 前端改动

#### BatchDetail.vue

在"一键执行"按钮旁加 `el-input-number`（默认值 = batch.targetCount，min=1 max=12），`doPipeline` 把 count 随 POST 传。

#### api/index.js

`runPipeline(batchId, count)` — POST body 传 `{ count }`。

### 不改的

- `batch.targetCount` 表字段不变（仍是创建时的默认值）。
- 单独的 `POST /api/batches/{id}/prompts` 接口不变。
- IDLE / 虚假 SUCCESS / 累积式等之前的修复不受影响。

## 影响面

- 后端：PipelineExecuteRequest（新）、PipelineOrchestratorController、PipelineOrchestratorService/Impl、PackageAssemblerService/Impl
- 前端：BatchDetail.vue、api/index.js
- 不涉及 DB schema。
