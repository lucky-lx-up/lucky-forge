# executeAsync 预创建 run 虚假 SUCCESS 修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 `executeAsync` 后台任务 finally 块无脑把预创建 run 标 SUCCESS 的缺陷，改为根据 `execute()` 真实结果标记 SUCCESS/FAILED。

**Architecture:** 修改 `executeAsync` 的虚拟线程 lambda，捕获 `execute()` 的返回值（`PipelineResult.overallSuccess()`）或异常，在 finally 块据此给预创建 run 标正确终态。成功路径不变（正式 run 盖过预创建 run），失败路径预创建 run 标 FAILED + error。

**Tech Stack:** Java 21 + Spring Boot 3 + MyBatis-Plus + JUnit 5 + Mockito（`@SpringBootTest` + `@MockBean` 集成测试）

## Global Constraints

- ServiceImpl 用 `@Autowired` 字段注入，不构造器注入。
- 实体扁平 POJO，逻辑删除字段 `deletedAt` 由 MyBatis-Plus 全局管理。
- `*IT` 为 `@SpringBootTest` 集成测试，连真实 MySQL（192.168.2.137:3306/lucky_forge），`@Transactional` 自动回滚，`@MockBean` mock 5 个子 Service。
- 测试方法用中文名（与现有测试一致）。
- 集成测试用 `-Pintegration` profile 触发。

---

## 文件结构

- 修改：`src/main/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceImpl.java`（`executeAsync` 的后台任务 lambda，约 line 219-233）
- 测试：`src/test/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceIT.java`（新增 `executeAsync` 的测试用例）

---

### Task 1: 为 executeAsync 早期失败时预创建 run 标 FAILED 编写失败测试

**Files:**
- Test: `src/test/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceIT.java`

**Interfaces:**
- Consumes: `PipelineOrchestratorService.executeAsync(Long batchId)` 返回 `Long`（batchId）；`runMapper.selectById(id)` 查 run；`@MockBean StyleAnalysisService.styleAnalysisService.analyze(batchId)` 可 mock 抛异常。已有 `setupBatchWithReferenceAndRun()` 辅助方法（line 219-241）建 batch+参考图+run，但本任务只需 batch+参考图（不要预建 run，因为 executeAsync 自己会创建）。
- Produces: 验证 `executeAsync` 在 `execute()` 早期失败时，预创建 run 被标 `FAILED` 且 `error` 非空。

- [ ] **Step 1: 在 `PipelineOrchestratorServiceIT` 末尾新增辅助方法和 2 个测试用例**

先在 `setupBatchWithReferenceAndRun()` 方法之后，新增一个只建 batch+参考图（不建 run）的辅助方法：

```java
    // 辅助：只建 batch + 参考图（不预建 run，让 executeAsync 自己创建）
    private Long setupBatchWithReferenceOnly() {
        Batch batch = new Batch();
        batch.setVertical("WALLPAPER");
        batch.setTargetCount(2);
        batch.setStatus(BatchStatus.DRAFT.value());
        batch.setTheme("test-async");
        batchMapper.insert(batch);

        ReferenceImage ref = new ReferenceImage();
        ref.setBatchId(batch.getId());
        ref.setObjectKey("test/async/" + System.nanoTime() + ".jpg");
        ref.setSource("MANUAL");
        referenceImageMapper.insert(ref);

        return batch.getId();
    }
```

然后在 `有RUNNING的run时getPipelineStatus返回RUNNING()` 测试之后，新增 2 个测试。注意 `executeAsync` 是异步的（虚拟线程），测试需轮询等待 run 从 RUNNING 变为终态：

```java
    @Test
    void executeAsync早期失败_预创建run标FAILED() throws Exception {
        Long batchId = setupBatchWithReferenceOnly();

        // STYLE 步骤直接抛异常 → execute 早期失败，PromptBuilder 不会创建正式 run
        when(styleAnalysisService.analyze(batchId))
                .thenThrow(new BizException("风格提炼失败"));

        pipelineOrchestratorService.executeAsync(batchId);

        // executeAsync 异步执行，轮询等待预创建 run 变为终态（最多 10 秒）
        Run run = waitForRunTerminalStatus(batchId, 10);
        assertNotNull(run, "应存在 run 记录");
        assertEquals("FAILED", run.getStatus(),
                "execute 早期失败时预创建 run 应标 FAILED，而非虚假 SUCCESS");
        assertNotNull(run.getError(), "失败时应写入 error 信息");
        assertNotNull(run.getFinishedAt(), "应有完成时间");
    }

    @Test
    void executeAsync全流程成功_预创建run标SUCCESS() throws Exception {
        Long batchId = setupBatchWithReferenceOnly();

        // mock 全流程成功（复用 IT 已有的 mock 模式，runId 用一个固定值）
        long runId = 999L;
        when(styleAnalysisService.analyze(batchId)).thenReturn(
                new StyleAnalysisResponse(10L, "测试风格", "描述", "{}", batchId));
        when(promptBuilderService.generatePrompts(eq(batchId), any())).thenReturn(List.of(
                new PromptGenerationResponse(1L, runId, 1, "p")));
        when(imageGeneratorService.generateImages(runId)).thenReturn(
                new ImageGenerationSummary(runId, 2, 2, 0, List.of()));
        when(imageScorerService.scoreImages(runId)).thenReturn(
                new ScoreSummary(runId, 2, 2, 0, 2, List.of()));
        when(packageAssemblerService.assemble(runId)).thenReturn(
                new PackageAssemblyResponse(5L, runId, batchId, "标题", List.of("标签"),
                        List.of(new PackageImageItem(1L, "test/1.png", 0, new BigDecimal("95")))));

        pipelineOrchestratorService.executeAsync(batchId);

        Run run = waitForRunTerminalStatus(batchId, 10);
        assertNotNull(run);
        // 成功时 PromptBuilder 会创建正式 run（id 更大），findLatestRun 返回正式 run；
        // 预创建 run 也应被标 SUCCESS（与原逻辑一致，回归保护）
        // 这里查到的可能是预创建 run 或正式 run，都应是 SUCCESS
        assertEquals("SUCCESS", run.getStatus());
    }

    // 辅助：轮询等待 batch 最新的 run 变为非 RUNNING 终态
    private Run waitForRunTerminalStatus(Long batchId, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Run run = runMapper.selectOne(new LambdaQueryWrapper<Run>()
                    .eq(Run::getBatchId, batchId)
                    .orderByDesc(Run::getId)
                    .last("LIMIT 1"));
            if (run != null && !"RUNNING".equals(run.getStatus())) {
                return run;
            }
            Thread.sleep(200);
        }
        return null;
    }
```

确认 import：`LambdaQueryWrapper` 已 import（line 3）。其余 `Batch`/`ReferenceImage`/`Run`/`BatchStatus`/`RunStatus`/各 DTO 均已 import（与现有测试一致）。

- [ ] **Step 2: 运行测试验证其失败**

Run: `cd E:/dev_projects/lucky-forge && ./mvnw test -Pintegration -Dtest=PipelineOrchestratorServiceIT -q`
Expected: `executeAsync早期失败_预创建run标FAILED` FAIL（预创建 run 实际是 `SUCCESS` 而非 `FAILED`）；`executeAsync全流程成功_预创建run标SUCCESS` PASS。

- [ ] **Step 3: Commit（红色测试先行）**

```bash
cd E:/dev_projects/lucky-forge
git add src/test/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceIT.java
git commit -m "test: executeAsync 早期失败时预创建 run 应标 FAILED（红）"
```

---

### Task 2: 修复 executeAsync finally 块，按真实结果标记终态

**Files:**
- Modify: `src/main/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceImpl.java:219-234`

**Interfaces:**
- Consumes: Task 1 的测试用例；`PipelineResult.overallSuccess()` / `overallMessage()`（已存在）。
- Produces: `executeAsync` 后台任务在 `execute()` 失败时把预创建 run 标 FAILED + error。

- [ ] **Step 1: 替换后台任务 lambda**

把 `PipelineOrchestratorServiceImpl.java` 中（约 line 217-234）：

```java
        final Long preRunId = preRun.getId();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                try {
                    execute(batchId);
                } catch (Exception e) {
                    log.error("异步 pipeline 执行异常 batchId={}", batchId, e);
                } finally {
                    // 无论成功失败，把预创建的 run 也标记终态（避免孤儿 RUNNING）
                    Run pre = runMapper.selectById(preRunId);
                    if (pre != null && "RUNNING".equals(pre.getStatus())) {
                        pre.setStatus("SUCCESS"); // 预创建的只是标记，实际成败由 PromptBuilder 的 run 体现
                        pre.setFinishedAt(LocalDateTime.now());
                        runMapper.updateById(pre);
                    }
                }
            });
        }
```

替换为：

```java
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

`PipelineResult` 已 import（line 10），无需新增 import。

- [ ] **Step 2: 运行测试验证全部通过**

Run: `cd E:/dev_projects/lucky-forge && ./mvnw test -Pintegration -Dtest=PipelineOrchestratorServiceIT -q`
Expected: 全部 PASS（含新增 2 个 + 原 7 个 = 9 个）。

- [ ] **Step 3: Commit**

```bash
cd E:/dev_projects/lucky-forge
git add src/main/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceImpl.java
git commit -m "fix: executeAsync 按真实结果标记预创建 run 终态，修复虚假 SUCCESS

execute 早期失败（无正式 run）时，预创建 run 不再无脑标 SUCCESS，
改为 FAILED + error，前端如实看到失败而非虚假成功。"
```

---

### Task 3: 重启后端并验证 batch 393 场景

**Files:** 无（运行时验证）

- [ ] **Step 1: 重新编译并重启后端**

当前后端进程需停掉重启以加载新代码。先查 8080 占用的 PID：

```bash
netstat -ano | findstr ":8080" | findstr "LISTENING"
```

用查到的 PID 停掉，然后后台启动：

```bash
powershell -NoProfile -Command "Stop-Process -Id <PID> -Force"
cd E:/dev_projects/lucky-forge && ./mvnw spring-boot:run
```

等待日志出现 `Started LuckyForgeApplication`。

- [ ] **Step 2: 触发 batch 393 流水线并验证返回 FAILED**

batch 393 有 5 张参考图，但 STYLE 会因风格名唯一键冲突失败（问题2，未修）。修复后应如实返回 FAILED：

```bash
# 触发流水线
curl -s -X POST "http://localhost:8080/api/batches/393/pipeline"
# 等待几秒后查状态
sleep 5
curl -s "http://localhost:8080/api/batches/393/pipeline/status"
```

Expected: `status` 为 `FAILED`（不再是虚假的 `SUCCESS`），`overallMessage` 含错误信息（如风格名冲突相关）。`runId` 非空。

- [ ] **Step 3: 前端验证（人工）**

浏览器打开 `http://localhost:5173`，进入 batch 393 详情页，点击执行流水线。预期：
- 轮询结束后显示"流水线未完成：..."的失败提示（而非假装成功）
- 不再出现"成功但无素材包"的矛盾现象

---

## Self-Review

**1. Spec coverage:**
- spec「修复方案」替换后台任务 lambda，用 `execute()` 返回值/异常决定终态 → Task 2 Step 1 ✓
- spec「行为变化」三种场景（成功/返回失败/抛异常）→ Task 2 代码的 `success` + `failureMsg` 覆盖 ✓
- spec「前端配合（无需改动）」→ Task 3 Step 3 人工验证前端显示 FAILED ✓
- spec「影响面」只改 `PipelineOrchestratorServiceImpl.java` → Task 2 一致 ✓

**2. Placeholder scan:** 无 TBD/TODO；每个 Step 有完整代码或精确命令 + 期望输出 ✓

**3. Type consistency:** `PipelineResult.overallSuccess()` / `overallMessage()` 与 spec 一致；`waitForRunTerminalStatus` 返回 `Run`，用 `LambdaQueryWrapper`（已 import）；测试用例字段断言 `getStatus()`/`getError()`/`getFinishedAt()` 与 `Run` 实体一致 ✓
