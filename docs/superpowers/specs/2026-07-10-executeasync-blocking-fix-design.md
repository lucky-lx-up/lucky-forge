# 修复：executeAsync 阻塞导致前端 POST 超时

## 背景

点击「执行流水线」，前端 `POST /api/batches/{id}/pipeline` 报错超时，但后台流水线实际仍在正常推进。

### 根因

`executeAsync`（`PipelineOrchestratorServiceImpl.java:218-246`）用了 `try-with-resources` 包裹虚拟线程 executor：

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    executor.submit(() -> { execute(batchId); ... });
}  // ← executor.close() → awaitTermination()，阻塞到任务跑完（2-5 分钟）
```

`ExecutorService` 实现了 `AutoCloseable`，try-with-resources 退出时调 `close()`，其语义是 `awaitTermination()`——等待所有已提交任务完成。因此 `executeAsync` 实际阻塞整个流水线时长（2-5 分钟）才返回，与 Controller 注释"立即返回"、方法名 `executeAsync` 完全矛盾。前端 axios 超时远小于此，故请求超时报错。

后台任务本身并未中断（DB 里 run 记录持续更新 currentStep），只是 HTTP 响应迟迟不返回。

### 复现验证（batch 466）

- 前端 POST 报错（超时）
- DB 显示 run 258（预创建）、259（正式 run，currentStep=GENERATE），均 RUNNING，流水线正常推进
- 后端进程存活（8080 LISTENING），非崩溃

## 修复方案

**去掉 `try-with-resources` + `executor.submit`，改为 `Thread.startVirtualThread(...)`**——虚拟线程最直接的 fire-and-forget 用法：

```java
// 虚拟线程后台执行（fire-and-forget：提交后立即返回，进度通过 lf_run 表追踪）
final Long preRunId = preRun.getId();
Thread.startVirtualThread(() -> {
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

### 改动点

仅替换任务提交方式，后台任务 lambda 体（success/failureMsg/finally 标记终态）**完全不变**：

| 项 | 改前 | 改后 |
|----|------|------|
| 任务提交 | `try(var e = newVirtualThreadPerTaskExecutor()){ e.submit(()->{...}); }` | `Thread.startVirtualThread(()->{...})` |
| 返回时机 | 阻塞到任务完成（2-5 分钟） | 立即返回 |
| import | `java.util.concurrent.Executors` | 无需（`Thread` 是 java.lang） |

### 为什么 `Thread.startVirtualThread` 安全

- 虚拟线程专为 fire-and-forget 设计：任务结束后虚拟线程自动销毁，无需显式关闭，无资源泄漏。
- 每次只提交一个任务，原 executor 是多余的（executor 适用于多次提交/复用场景）。
- lambda 内部已有完整的 try/catch/finally，异常处理与终态标记逻辑不变。
- 虚拟线程内调用的 MyBatis-Plus Mapper 等仍是线程安全的（Spring 注入的单例 bean）。

### 副作用：未捕获异常

`Thread.startVirtualThread` 的未捕获异常默认打印到 stderr（行为同普通线程）。但 lambda 内部已有 `catch (Exception e)` 捕获所有受检/非受检异常并记录日志，finally 保证终态标记，因此不会有未捕获异常逃逸。仅极端的 `Error`（如 `OutOfMemoryError`）会逃逸，但 finally 仍会执行（finally 在 Error 时也会跑，除非 JVM 崩溃）。

## 前端配合（无需改动）

前端 `doPipeline`（`BatchDetail.vue:336-345`）调用 `runPipeline(batchId)` 后 `setInterval(pollStatus, 3000)` 轮询。修复后 POST 立即返回，前端正常进入轮询。**无需改前端**。

## 影响面

- 改动文件：`PipelineOrchestratorServiceImpl.java`（`executeAsync`，约 line 216-246）
- 可移除 import：`java.util.concurrent.Executors`（若文件其他地方不再使用——需确认）
- 不涉及 DB schema、前端、其他 Service。
- 不改变后台任务的执行逻辑与终态标记（上一轮修复的 FAILED 逻辑保留）。
