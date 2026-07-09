# getPipelineStatus 无 run 时返回 IDLE 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 `getPipelineStatus` 在无 run 记录时返回假 `RUNNING` 导致前端永久轮询的缺陷，改为返回 `IDLE`。

**Architecture:** 单点修改 `PipelineOrchestratorServiceImpl.getPipelineStatus` 的 `run == null` 分支，把占位的 `RUNNING` 改为 `IDLE`。前端 `checkRunningPipeline` 只在 `status==='RUNNING'` 时轮询，看到 `IDLE` 天然不轮询，无需改前端。预创建 run 机制保证 POST 成功后立即有 RUNNING 记录，`run==null` 当下只意味着"从未成功触发过 pipeline"。

**Tech Stack:** Java 21 + Spring Boot 3 + MyBatis-Plus + JUnit 5 + Mockito（`@SpringBootTest` + `@MockBean` 集成测试）

## Global Constraints

- 实体类扁平 POJO，用 `@TableName`/`@Data`/`@TableId(type=IdType.AUTO)`；逻辑删除字段 `deletedAt` 由 MyBatis-Plus 全局管理。
- ServiceImpl 用 `@Autowired` 字段注入，不构造器注入。
- 测试命名与项目一致：中文方法名；`*IT` 为 `@SpringBootTest` 集成测试，连真实 MySQL（192.168.2.137:3306/lucky_forge），`@Transactional` 自动回滚。
- 集成测试用 `-Pintegration` profile 触发；默认 `mvn test` 只跑 `*Test` 纯单元测试。

---

## 文件结构

- 修改：`src/main/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceImpl.java`（`getPipelineStatus` 方法，约 line 239-253）
- 测试：`src/test/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceIT.java`（新增 2 个测试用例）

---

### Task 1: 为 getPipelineStatus 的 IDLE 行为编写失败测试

**Files:**
- Test: `src/test/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceIT.java`

**Interfaces:**
- Consumes: `PipelineOrchestratorService.getPipelineStatus(Long batchId)` 返回 `PipelineStatusResponse(Long runId, String status, String currentStep, String overallMessage, Long packageId, List<PipelineStepResult> steps)`。已有 `runMapper` 注入（line 52）和 `@Transactional` 回滚（line 46）。
- Produces: 验证 `run==null` 时返回 `status="IDLE"`、`currentStep=null`、`runId=null`；以及 `run!=null` 时仍正确返回真实状态（回归保护）。

- [ ] **Step 1: 在 `PipelineOrchestratorServiceIT` 末尾（line 210 `setupBatchWithReferenceAndRun` 之前）新增 2 个测试方法**

在 `batch无参考图时拒绝()` 方法之后、`// ===== 辅助` 注释之前插入：

```java
    @Test
    void 无run记录时getPipelineStatus返回IDLE() {
        // 建一个 batch 但不创建任何 run（模拟"从未成功触发过 pipeline"）
        Batch batch = new Batch();
        batch.setVertical("WALLPAPER");
        batch.setTargetCount(1);
        batch.setStatus(BatchStatus.DRAFT.value());
        batchMapper.insert(batch);

        PipelineStatusResponse resp = pipelineOrchestratorService.getPipelineStatus(batch.getId());

        assertEquals("IDLE", resp.status(), "无 run 记录应返回 IDLE，而非诱导轮询的 RUNNING");
        assertNull(resp.runId());
        assertNull(resp.currentStep(), "IDLE 无当前步骤");
    }

    @Test
    void 有RUNNING的run时getPipelineStatus返回RUNNING() {
        Long[] ids = setupBatchWithReferenceAndRun();
        Long batchId = ids[0];

        PipelineStatusResponse resp = pipelineOrchestratorService.getPipelineStatus(batchId);

        assertEquals("RUNNING", resp.status(), "有 RUNNING 的 run 应原样返回 RUNNING");
        assertEquals("STYLE", resp.currentStep());
        assertEquals(ids[1], resp.runId());
    }
```

需要补 import（文件顶部已有 `PipelineStatusResponse`? 若无则加）。检查：文件已 import `com.lucky.luckyforge.application.pipeline.dto.PipelineResult`（line 12），需新增：

```java
import com.lucky.luckyforge.application.pipeline.dto.PipelineStatusResponse;
```

- [ ] **Step 2: 运行测试验证其失败（IDLE 用例应红，RUNNING 回归用例应绿）**

Run: `cd E:/dev_projects/lucky-forge && ./mvnw test -Pintegration -Dtest=PipelineOrchestratorServiceIT -pl . -q`
Expected: `无run记录时getPipelineStatus返回IDLE` FAIL（实际返回 `"RUNNING"` 而非 `"IDLE"`）；`有RUNNING的run时getPipelineStatus返回RUNNING` PASS。

- [ ] **Step 3: Commit（测试先行，先提交红色测试）**

```bash
cd E:/dev_projects/lucky-forge
git add src/test/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceIT.java
git commit -m "test: getPipelineStatus 无 run 时应返回 IDLE（红）"
```

---

### Task 2: 实现 IDLE 返回，使测试通过

**Files:**
- Modify: `src/main/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceImpl.java:243-247`

**Interfaces:**
- Consumes: Task 1 的测试用例。
- Produces: `getPipelineStatus` 在 `run==null` 时返回 `PipelineStatusResponse(null, "IDLE", null, "无流水线执行记录", null, List.of())`。

- [ ] **Step 1: 修改 getPipelineStatus 的 run==null 分支**

把 `PipelineOrchestratorServiceImpl.java` 中（约 line 243-247）：

```java
        if (run == null) {
            // 可能 pipeline 刚触发，PromptBuilder 还没创建 run，返回启动中
            return new PipelineStatusResponse(null, "RUNNING", "STYLE",
                    "流水线启动中...", null, List.of());
        }
```

替换为：

```java
        if (run == null) {
            // 无 run 记录 = 从未成功触发过 pipeline（executeAsync 成功后会预创建 run）。
            // 返回 IDLE 而非 RUNNING，避免前端 checkRunningPipeline 误判为"执行中"而永久轮询。
            return new PipelineStatusResponse(null, "IDLE", null,
                    "无流水线执行记录", null, List.of());
        }
```

变更点：`status` RUNNING→IDLE；`currentStep` "STYLE"→null；`overallMessage` "流水线启动中..."→"无流水线执行记录"。

- [ ] **Step 2: 重新运行测试验证通过**

Run: `cd E:/dev_projects/lucky-forge && ./mvnw test -Pintegration -Dtest=PipelineOrchestratorServiceIT -pl . -q`
Expected: 全部 PASS（含新增 2 个 + 原有 5 个）。

- [ ] **Step 3: Commit**

```bash
cd E:/dev_projects/lucky-forge
git add src/main/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceImpl.java
git commit -m "fix: getPipelineStatus 无 run 时返回 IDLE 而非假 RUNNING

run==null 只意味着从未成功触发过 pipeline（executeAsync 成功会预创建 run）。
原实现返回 RUNNING 诱导前端 checkRunningPipeline 永久轮询。"
```

---

### Task 3: 重启后端并用真实接口验证

**Files:** 无（运行时验证）

- [ ] **Step 1: 重新编译并重启后端**

当前后端进程 PID 58020（`com.lucky.luckyforge.LuckyForgeApplication`）。需停掉后重启以加载新代码。

```bash
# 停旧进程
powershell -NoProfile -Command "Stop-Process -Id 58020 -Force"
# 后台启动新进程
cd E:/dev_projects/lucky-forge && ./mvnw spring-boot:run
```

启动后等待看到 `Started LuckyForgeApplication` 日志。

- [ ] **Step 2: 用真实接口验证 batch 322（无 run）现在返回 IDLE**

Run: `curl -s http://localhost:8080/api/batches/322/pipeline/status`
Expected: `"status":"IDLE"`、`"currentStep":null`、`"runId":null`（不再是 `"RUNNING"`）。

- [ ] **Step 3: 验证有 run 的 batch（223）仍返回真实状态**

Run: `curl -s http://localhost:8080/api/batches/223/pipeline/status`
Expected: `status` 为 `SUCCESS` 或 `FAILED`（最近 run id=145 是 SUCCESS），`runId` 非空。确认未破坏正常路径。

- [ ] **Step 4: 前端验证不再死循环轮询（人工）**

浏览器打开 `http://localhost:5173`，进入 batch 322 详情页。预期：
- 不再出现"检测到流水线执行中，已恢复进度显示"的误提示。
- Network 面板不再有 `/api/batches/322/pipeline/status` 的持续轮询请求。

---

## Self-Review

**1. Spec coverage:**
- spec「修复方案」单点修改 getPipelineStatus 的 run==null 分支 → Task 2 Step 1 ✓
- spec「前端配合（无需改动）」→ 不涉及代码改动，Task 3 Step 4 人工验证 ✓
- spec「已知边界」(pollStatus 进行中收到 IDLE 不停 timer) → 明确不在范围，计划未扩大 ✓

**2. Placeholder scan:** 无 TBD/TODO；每个 Step 都有完整代码或精确命令 + 期望输出 ✓

**3. Type consistency:** `PipelineStatusResponse` 6 参数 record `(runId, status, currentStep, overallMessage, packageId, steps)`——Task 2 实现与 Task 1 测试断言字段名一致（`status`/`runId`/`currentStep`）；`setupBatchWithReferenceAndRun` 返回 `Long[]{batchId, runId}` 与现有测试用法一致 ✓
