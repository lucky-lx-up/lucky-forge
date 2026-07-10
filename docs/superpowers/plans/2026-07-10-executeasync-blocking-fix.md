# executeAsync 阻塞导致前端 POST 超时修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 `executeAsync` 因 `try-with-resources` 关闭 executor 时 `awaitTermination` 阻塞导致前端 POST 超时的缺陷，改为 `Thread.startVirtualThread` 真正 fire-and-forget。

**Architecture:** 把 `executeAsync` 里 `try-with-resources + executor.submit` 替换为 `Thread.startVirtualThread(...)`。后台任务 lambda 体（success/failureMsg/finally 标记终态）完全不变。提交后立即返回，不再阻塞。

**Tech Stack:** Java 21 + Spring Boot 3 + MyBatis-Plus + JUnit 5 + Mockito（`@SpringBootTest` + `@MockBean` 集成测试）

## Global Constraints

- ServiceImpl 用 `@Autowired` 字段注入，不构造器注入。
- 实体扁平 POJO，逻辑删除字段 `deletedAt` 由 MyBatis-Plus 全局管理。
- `*IT` 为 `@SpringBootTest` 集成测试，连真实 MySQL（192.168.2.137:3306/lucky_forge）。
- 异步测试方法用 `@Transactional(propagation = Propagation.NEVER)` + `TransactionTemplate(REQUIRES_NEW)` 显式提交设置数据（虚拟线程读不到测试未提交事务，上一轮已建立此模式）。
- 测试方法用中文名。
- 集成测试用 `-Pintegration` profile 触发。

---

## 文件结构

- 修改：`src/main/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceImpl.java`（`executeAsync`，约 line 216-246）
- 测试：`src/test/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceIT.java`（新增"不阻塞"测试用例）

---

### Task 1: 为 executeAsync 不阻塞编写失败测试

**Files:**
- Test: `src/test/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceIT.java`

**Interfaces:**
- Consumes: `PipelineOrchestratorService.executeAsync(Long batchId)` 返回 `Long`（应立即返回）；`@MockBean StyleAnalysisService.analyze(batchId)` 可 mock 阻塞；已有 `setupBatchWithReferenceOnly()` 辅助方法（上一轮新增，line 315+，用 TransactionTemplate 提交）。
- Produces: 验证 `executeAsync` 在 `execute()` 完成**之前**就返回（即不阻塞），证明 fire-and-forget。

- [ ] **Step 1: 在 `PipelineOrchestratorServiceIT` 新增 import 和测试用例**

新增 import（文件顶部 import 区，按字母序）：

```java
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
```

在 `executeAsync全流程成功_预创建run标SUCCESS()` 测试之后，新增测试用例。核心思路：mock `styleAnalysisService.analyze` 用 `CountDownLatch` 阻塞，使 `execute()` 至少阻塞 3 秒；断言 `executeAsync` 在远小于 3 秒内返回（证明未阻塞等待 execute）：

```java
    @Test
    void executeAsync应立即返回不阻塞等待execute完成() throws Exception {
        Long batchId = setupBatchWithReferenceOnly();

        // 用 latch 让 analyze 阻塞：execute() 会卡在 STYLE 步骤直到 latch 释放
        CountDownLatch analyzeStarted = new CountDownLatch(1);
        CountDownLatch releaseAnalyze = new CountDownLatch(1);
        when(styleAnalysisService.analyze(batchId)).thenAnswer(inv -> {
            analyzeStarted.countDown();
            // 阻塞直到测试主线程释放（最多等 10 秒兜底，避免卡死）
            releaseAnalyze.await(10, TimeUnit.SECONDS);
            throw new BizException("测试用阻塞已结束");
        });

        long start = System.currentTimeMillis();
        pipelineOrchestratorService.executeAsync(batchId);
        long elapsed = System.currentTimeMillis() - start;

        // executeAsync 应在 execute() 完成前就返回（analyze 还在阻塞中）
        // 阈值 2000ms：execute() 至少阻塞 3 秒，若 executeAsync 阻塞等待则 elapsed >= 3000
        assertTrue(elapsed < 2000,
                "executeAsync 应立即返回，实际耗时 " + elapsed + "ms（疑似阻塞等待 execute）");

        // 确认 analyze 确实被调用了（后台任务已启动）
        assertTrue(analyzeStarted.await(2, TimeUnit.SECONDS),
                "后台任务应在 executeAsync 返回后被调用");

        // 释放后台任务，避免泄漏
        releaseAnalyze.countDown();
    }
```

- [ ] **Step 2: 运行测试验证其失败**

Run: `cd E:/dev_projects/lucky-forge && ./mvnw test -Pintegration -Dtest=PipelineOrchestratorServiceIT -q`
Expected: `executeAsync应立即返回不阻塞等待execute完成` FAIL（`elapsed < 2000` 断言失败，实际耗时 >= 3000ms，因 try-with-resources 阻塞等待 analyze 释放）；其余用例 PASS。

- [ ] **Step 3: Commit（红色测试先行）**

```bash
cd E:/dev_projects/lucky-forge
git add src/test/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceIT.java
git commit -m "test: executeAsync 应立即返回不阻塞等待 execute 完成（红）"
```

---

### Task 2: 改为 Thread.startVirtualThread，使测试通过

**Files:**
- Modify: `src/main/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceImpl.java:216-246`
- Modify: `src/main/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceImpl.java:33`（移除 import）

**Interfaces:**
- Consumes: Task 1 的测试用例。
- Produces: `executeAsync` 提交后台任务后立即返回。

- [ ] **Step 1: 替换任务提交方式 + 移除 import**

把 `PipelineOrchestratorServiceImpl.java` 中（约 line 216-246）：

```java
        // 虚拟线程后台执行（进度通过 lf_run 表的 currentStep/status 追踪，重启不丢）
        final Long preRunId = preRun.getId();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
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
                    // 按 execute 真实结果标记预创建 run 终态（避免孤儿 RUNNING）
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
        }
```

替换为：

```java
        // 虚拟线程后台执行（fire-and-forget：提交后立即返回，进度通过 lf_run 表追踪，重启不丢）
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
                // 按 execute 真实结果标记预创建 run 终态（避免孤儿 RUNNING）
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

然后移除 import（line 33，已确认全文件仅此处使用 Executors）：

```java
import java.util.concurrent.Executors;
```

删除这一行。

- [ ] **Step 2: 运行测试验证全部通过**

Run: `cd E:/dev_projects/lucky-forge && ./mvnw test -Pintegration -Dtest=PipelineOrchestratorServiceIT -q`
Expected: 全部 PASS（含新增的不阻塞用例 + 原 9 个 = 10 个）。

- [ ] **Step 3: Commit**

```bash
cd E:/dev_projects/lucky-forge
git add src/main/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceImpl.java
git commit -m "fix: executeAsync 改用 Thread.startVirtualThread 真正异步，修复阻塞导致前端超时

try-with-resources 关闭 executor 会 awaitTermination 阻塞到任务完成（2-5分钟），
导致 POST /pipeline 前端超时。改为 Thread.startVirtualThread 提交后立即返回。"
```

---

### Task 3: 重启后端并真实接口验证

**Files:** 无（运行时验证）

**说明：** Task 3 涉及停/启本机进程，由控制器在主会话执行，不派 subagent。

- [ ] **Step 1: 停止当前后端并重启加载新代码**

```bash
# 查 8080 PID
netstat -ano | findstr ":8080" | findstr "LISTENING"
# 停掉（用查到的 PID 替换）
powershell -NoProfile -Command "Stop-Process -Id <PID> -Force"
# 后台启动
cd E:/dev_projects/lucky-forge && ./mvnw spring-boot:run
```
等待 `Started LuckyForgeApplication` 日志。

- [ ] **Step 2: 验证 POST 立即返回（不超时）**

选一个有参考图的 batch（如 466，或重新建一个）。触发 pipeline 并计时：

```bash
# 计时触发 pipeline（应在 1-2 秒内返回，不再超时）
time curl -s -m 60 -w "\nHTTP %{http_code}\n" -X POST "http://localhost:8080/api/batches/466/pipeline"
```
Expected: HTTP 200 或 400（"已有流水线在执行中"，若上次的还在跑）——但关键是**快速返回**（1-2 秒内），不再阻塞 2-5 分钟。

- [ ] **Step 3: 前端验证（人工）**

浏览器 `http://localhost:5173`，进入一个有参考图的 batch 详情页，点击执行流水线。预期：
- 立即弹出"流水线已启动，后台执行中..."提示（不再卡住/超时报错）
- 进度条正常轮询更新

---

## Self-Review

**1. Spec coverage:**
- spec「修复方案」去掉 try-with-resources 改为 Thread.startVirtualThread → Task 2 Step 1 ✓
- spec「改动点」import 移除 → Task 2 Step 1（移除 Executors import）✓
- spec「lambda 体完全不变」→ Task 2 的替换代码 lambda 体与原逐字一致 ✓
- spec「前端无需改动」→ Task 3 Step 3 人工验证 ✓
- spec「影响面」只改 PipelineOrchestratorServiceImpl.java → Task 2 一致 ✓

**2. Placeholder scan:** 无 TBD/TODO；每步有完整代码或精确命令 + 期望输出 ✓

**3. Type consistency:** `Thread.startVirtualThread(Runnable)` 接收 lambda，与原 `executor.submit(Runnable)` 签名兼容；`CountDownLatch`/`TimeUnit` import 明确；`setupBatchWithReferenceOnly()` 复用上一轮辅助方法 ✓

**4. 测试设计稳健性：** 不阻塞测试用 `CountDownLatch` 而非 `Thread.sleep`（确定性更高——能确认后台任务确实启动了），阈值 2000ms 远小于 analyze 阻塞时长，避免 flaky；`releaseAnalyze.countDown()` 兜底释放避免线程泄漏 ✓
