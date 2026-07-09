# 修复：executeAsync 预创建 run 被无脑标 SUCCESS 导致虚假成功

## 背景

用户执行流水线后，`getPipelineStatus` 返回 `status: SUCCESS`，但页面下方没有素材包。

### 根因（已用 DB 证据验证）

`executeAsync` 的后台任务 finally 块（`PipelineOrchestratorServiceImpl.java:224-232`）无论 `execute()` 成功还是失败，都把预创建 run 标成 `SUCCESS`：

```java
finally {
    Run pre = runMapper.selectById(preRunId);
    if (pre != null && "RUNNING".equals(pre.getStatus())) {
        pre.setStatus("SUCCESS"); // 预创建的只是标记，实际成败由 PromptBuilder 的 run 体现
        pre.setFinishedAt(LocalDateTime.now());
        runMapper.updateById(pre);
    }
}
```

注释的设计意图：`execute()` 成功时 PromptBuilder 会创建**正式 run**（id 更大），`findLatestRun` 返回正式 run，预创建 run 被盖过，标 SUCCESS 无所谓。

**但这个前提在 `execute()` 早期失败时不成立：**

`execute()` 的步骤顺序是 STYLE → PROMPT → GENERATE → SCORE → PACKAGE。其中 **PROMPT 步骤（PromptBuilder）才创建正式 run**。若 STYLE 步骤就失败：
- PromptBuilder 没被调用 → 没有正式 run
- `execute()` 内部的 catch（line 122-128）调用 `finalizeRun(runId, FAILED, ...)`，但此时 `runId==null`（PromptBuilder 没跑），`finalizeRun` 直接 return（line 295 `if (runId == null) return;`）
- 于是 batch 只剩预创建 run 一条
- finally 块照样把它标成 `SUCCESS`
- `findLatestRun` 返回预创建 run → 前端看到 `SUCCESS` → **虚假成功**

### 复现验证（batch 393）

- 有 5 张参考图 → `executeAsync` 校验通过 → 预创建 run 199
- `lf_style_analysis` 对 batch 393 无记录 → STYLE 步骤未成功
- run 199：`started_at == finished_at`（00:27:48，3 秒"完成"）、`current_step=STYLE`、`error=NULL`
- `lf_package` 对 batch 393 无记录 → 无素材包
- batch 393 只有 run 199 一条（无正式 run）

触发 STYLE 失败的具体原因（问题2，不在本次范围）：风格名唯一键冲突 `uk_style_name_vertical`，AI 对相似参考图给出重复风格名时 insert 撞键抛异常。本次只修状态管理缺陷，不修风格去重。

## 修复方案

**修改 `executeAsync` 的后台任务**，用 `execute()` 的真实返回值/异常决定预创建 run 的终态：

```java
executor.submit(() -> {
    boolean success = false;
    String failureMsg = null;
    try {
        PipelineResult result = execute(batchId);
        success = result.overallSuccess();
        if (!success) {
            failureMsg = result.overallMessage();
        }
    } catch (Exception e) {
        failureMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        log.error("异步 pipeline 执行异常 batchId={}", batchId, e);
    } finally {
        Run pre = runMapper.selectById(preRunId);
        if (pre != null && "RUNNING".equals(pre.getStatus())) {
            if (success) {
                pre.setStatus("SUCCESS");
            } else {
                pre.setStatus("FAILED");
                pre.setError(failureMsg != null ? failureMsg : "流水线执行失败");
            }
            pre.setFinishedAt(LocalDateTime.now());
            runMapper.updateById(pre);
        }
    }
});
```

### 行为变化

| 场景 | 改前（预创建 run 终态） | 改后 |
|------|------|------|
| `execute()` 成功 | SUCCESS（正式 run 盖过，不影响） | SUCCESS（不变） |
| `execute()` 返回失败（overallSuccess=false） | **SUCCESS（虚假）** | **FAILED + error** |
| `execute()` 抛异常 | **SUCCESS（虚假）** | **FAILED + error** |

### 成功路径不受影响

`execute()` 成功时，PromptBuilder 已创建正式 run（id 更大）。`findLatestRun` 按 id 倒序返回正式 run，预创建 run 被盖过——这与原逻辑完全一致。预创建 run 标 SUCCESS 或 FAILED 都不影响前端看到的真实结果（前端看正式 run）。

### 失败路径的正确性

`execute()` 失败时：
- 若 PromptBuilder 跑过（有正式 run）：正式 run 已被 `execute()` 内部 `finalizeRun(FAILED)` 标记，`findLatestRun` 返回正式 run，前端看到 FAILED。预创建 run 也被标 FAILED（之前是虚假 SUCCESS），两者一致，无冲突。
- 若 PromptBuilder 没跑（早期失败，无正式 run）：预创建 run 标 FAILED + error，`findLatestRun` 返回它，前端看到 FAILED + error。**这正是本次修复的核心收益**。

## 前端配合（无需改动）

`BatchDetail.vue#pollStatus`（line 360-371）已处理 `FAILED`：`clearInterval` + `ElMessage.warning('流水线未完成：' + ...)` + `running=false` + `load()`。改后前端会正确显示失败提示，不再误显示成功。**前端无需改动**。

## 影响面

- 改动文件：`PipelineOrchestratorServiceImpl.java`（`executeAsync` 的后台任务 lambda，约 line 219-233）
- 需新增 import：`PipelineResult`（已 import，line 10）
- 不涉及 DB schema、前端、其他 Service。
- 不影响成功路径的进度查询与终态。
