# 修复：getPipelineStatus 在无 run 时返回 IDLE 而非假 RUNNING

## 背景

点击「执行流水线」后，前端持续轮询 `GET /api/batches/{batchId}/pipeline/status`，永不停止。

### 根因（已用 DB 证据验证）

`PipelineOrchestratorServiceImpl.getPipelineStatus(Long batchId)` 在 `findLatestRun(batchId)` 返回 `null` 时，无条件返回一个**占位响应**：

```java
if (run == null) {
    return new PipelineStatusResponse(null, "RUNNING", "STYLE",
            "流水线启动中...", null, List.of());
}
```

该占位响应无法区分两种语义截然不同的情形：

- **(A) 刚 POST 成功、run 尚未建好** —— 应短暂返回 RUNNING（合理）。
- **(B) 根本没成功触发过 pipeline（批次无参考图、从未执行过等）** —— 永远不会有 run，却永远返回 RUNNING（错误）。

前端 `BatchDetail.vue#checkRunningPipeline`（页面加载即调用）只要看到 `status === 'RUNNING'` 就 `setInterval(pollStatus, 3000)`。情形 (B) 下 → 死循环轮询。

### 为什么情形 (A) 的窗口已被覆盖，run==null 现在只意味着"真没跑过"

`executeAsync` 在校验通过后会**预创建**一条 `RUNNING` 记录（line 209-214）再提交后台任务。因此 POST 成功后 DB 里立即就有该 batch 的 run，`getPipelineStatus` 不会再走到 `run==null` 分支。即 `run==null` 当下只对应"POST 从未成功过"——返回 IDLE 完全合理，不会丢失任何真实进度。

### 复现验证

- batch 322：存在（DRAFT）、无参考图、run 记录数 0。
- `POST /api/batches/322/pipeline` → 400「批次无参考图: 322」（正确拒绝，预创建 run 未执行）。
- `GET /api/batches/322/pipeline/status` → 200 `{"status":"RUNNING",...}`（错误：诱导前端永久轮询）。

## 修复方案

**单点修改** `PipelineOrchestratorServiceImpl.getPipelineStatus` 的 `run == null` 分支：返回 `status="IDLE"`，表示"该批次当前无流水线在执行"。

```java
if (run == null) {
    // 无 run 记录 = 从未成功触发过 pipeline（executeAsync 成功后会预创建 run）
    return new PipelineStatusResponse(null, "IDLE", null,
            "无流水线执行记录", null, List.of());
}
```

变更点：
- `status`：`"RUNNING"` → `"IDLE"`
- `currentStep`：`"STYLE"` → `null`（IDLE 无当前步骤）
- `overallMessage`：`"流水线启动中..."` → `"无流水线执行记录"`（语义准确）

## 前端配合（无需改动，仅说明）

`BatchDetail.vue`：
- `checkRunningPipeline`（line 236-249）：仅在 `status === 'RUNNING'` 时启动轮询。收到 `IDLE` 不轮询 —— **天然兼容，无需改**。
- `pollStatus`（line 350-375）：仅显式处理 `SUCCESS` / `FAILED`，对其他状态静默跳过本轮。收到 `IDLE` 不停止 timer —— 见「已知边界」。

## 已知边界（不在本次修复范围）

`pollStatus` 已启动轮询后，若 run 记录中途变为 null（收到 `IDLE`），当前逻辑不会 `clearInterval`，会持续静默轮询。该场景要求"run 在执行过程中凭空消失"——正常流程不会发生（预创建 run 与正式 run 都不会被删除），属极低概率边界。本次不扩大范围处理，仅记录。

## 影响面

- 改动文件：`PipelineOrchestratorServiceImpl.java`（1 个方法，约 3 行）。
- 不涉及 DB schema、不涉及前端、不涉及其他 Service。
- 不影响真实 RUNNING 流水线的进度查询（那条路径 `run != null`，不受影响）。
