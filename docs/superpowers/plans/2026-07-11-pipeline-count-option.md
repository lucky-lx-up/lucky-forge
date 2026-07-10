# 执行流水线时灵活选择生成张数实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在"一键执行"时支持临时指定生成张数（count），count 透传到流水线影响 PromptBuilder 出词数和 PackageAssembler 打包 TopN，不持久化。

**Architecture:** POST /pipeline 加可选 body `{count}`，executeAsync 重载接收 count 透传到 execute，execute 里 PromptBuilder 用 count 覆盖 batch.targetCount，PackageAssembler 加重载用 count 做 TopN。用重载而非改签名，现有调用和测试不受影响。

**Tech Stack:** Java 21 + Spring Boot 3 + MyBatis-Plus + Vue 3 + Element Plus

## Global Constraints

- ServiceImpl 用 `@Autowired` 字段注入，不构造器注入。
- DTO 为 record 类型，带紧凑构造器校验。
- Controller 方法逻辑在 try-catch 中，返回 `ResponseEntity<ApiResponse<T>>`。
- Service 方法返回 DTO 而非实体。
- 测试方法用中文名，`*IT` 连真实 MySQL，`@Transactional` 回滚。
- 用重载而非改现有方法签名（保持向后兼容）。

---

## 文件结构

- 新增：`src/main/java/com/lucky/luckyforge/application/pipeline/dto/PipelineExecuteRequest.java`
- 修改：`src/main/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorService.java`（接口加重载）
- 修改：`src/main/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceImpl.java`（实现重载 + execute 透传）
- 修改：`src/main/java/com/lucky/luckyforge/application/packageassembler/PackageAssemblerService.java`（接口加重载）
- 修改：`src/main/java/com/lucky/luckyforge/application/packageassembler/PackageAssemblerServiceImpl.java`（实现重载）
- 修改：`src/main/java/com/lucky/luckyforge/api/controller/PipelineOrchestratorController.java`（加 body 参数）
- 修改：`frontend/src/views/BatchDetail.vue`（加张数选择器）
- 修改：`frontend/src/api/index.js`（runPipeline 加 count 参数）
- 测试：`src/test/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceIT.java`

---

### Task 1: 后端 — PackageAssembler 加 count 重载 + execute 透传 count

**Files:**
- Modify: `src/main/java/com/lucky/luckyforge/application/packageassembler/PackageAssemblerService.java`
- Modify: `src/main/java/com/lucky/luckyforge/application/packageassembler/PackageAssemblerServiceImpl.java`
- Modify: `src/main/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorService.java`
- Modify: `src/main/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceImpl.java`
- Test: `src/test/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceIT.java`

**Interfaces:**
- Consumes: 现有 `packageAssemblerService.assemble(Long runId)`；`promptBuilderService.generatePrompts(Long batchId, PromptGenerationRequest request)`；`PromptGenerationRequest(Integer count)`。
- Produces: `PackageAssemblerService.assemble(Long runId, Integer count)` 重载；`PipelineOrchestratorService.executeAsync(Long batchId, Integer count)` 重载。

- [ ] **Step 1: PackageAssemblerService 接口加重载**

在 `PackageAssemblerService.java` 的现有 `assemble(Long runId)` 方法声明之后，加：

```java
    /**
     * 对指定 run 执行打包（指定 TopN，覆盖 batch.targetCount）。
     *
     * @param runId 运行 id（必须含打分结果）
     * @param count 打包 TopN（可空：空则回退 batch.targetCount）
     * @return 打包结果
     */
    PackageAssemblyResponse assemble(Long runId, Integer count);
```

- [ ] **Step 2: PackageAssemblerServiceImpl 实现重载**

在现有 `assemble(Long runId)` 方法之前，加一个委托方法；然后把原 `assemble(Long runId)` 改为调 `assemble(runId, null)`：

把现有方法签名：
```java
    public PackageAssemblyResponse assemble(Long runId) {
```
改为（方法体第一行之前加 count 参数版本）：

```java
    @Override
    public PackageAssemblyResponse assemble(Long runId) {
        return assemble(runId, null);
    }

    @Override
    @Transactional
    public PackageAssemblyResponse assemble(Long runId, Integer count) {
```

然后在方法体内 TopN 计算处（原 line 98-102），把：
```java
        int topN = (batch != null && batch.getTargetCount() != null && batch.getTargetCount() > 0)
                ? batch.getTargetCount() : scores.size();
```
改为：
```java
        int topN = (count != null && count > 0)
                ? count
                : (batch != null && batch.getTargetCount() != null && batch.getTargetCount() > 0
                        ? batch.getTargetCount() : scores.size());
```

- [ ] **Step 3: PipelineOrchestratorService 接口加重载**

在 `PipelineOrchestratorService.java` 现有 `executeAsync(Long batchId)` 声明之后，加：

```java
    /**
     * 异步执行全流程（指定生成张数）。
     *
     * @param batchId 批次 id（必须已含参考图）
     * @param count   生成张数（可空：空则用 batch.targetCount）
     * @return batchId（用于轮询）
     */
    Long executeAsync(Long batchId, Integer count);
```

- [ ] **Step 4: PipelineOrchestratorServiceImpl 实现重载 + execute 透传**

现有 `executeAsync(Long batchId)` 改为委托：
```java
    @Override
    public Long executeAsync(Long batchId) {
        return executeAsync(batchId, null);
    }
```

新增 `executeAsync(Long batchId, Integer count)`，方法体与现有 `executeAsync(Long batchId)` 完全一致，唯一区别：后台任务调 `execute(batchId, count)` 而非 `execute(batchId)`。即把 `executor.submit(() -> { ... execute(batchId) ... })` 改为 `execute(batchId, count)`。

现有 `execute(Long batchId)` 改为委托：
```java
    @Override
    public PipelineResult execute(Long batchId) {
        return execute(batchId, null);
    }
```

新增 private `execute(Long batchId, Integer count)`，方法体与现有 `execute(Long batchId)` 一致，两处改动：
- 步骤② PromptBuilder：`promptBuilderService.generatePrompts(batchId, null)` 改为 `promptBuilderService.generatePrompts(batchId, count != null ? new PromptGenerationRequest(count) : null)`
- 步骤⑤ PackageAssembler：`packageAssemblerService.assemble(runIdFinal)` 改为 `packageAssemblerService.assemble(runIdFinal, count)`

需要新增 import：`import com.lucky.luckyforge.application.promptbuilder.dto.PromptGenerationRequest;`

- [ ] **Step 5: 测试 — 新增 executeAsync 带 count 的测试用例**

在 `PipelineOrchestratorServiceIT.java` 新增测试，验证传 count=1 时只生成 1 条 prompt、1 张图、打包 TopN=1：

```java
    @Test
    void executeAsync带count_应按count生成对应数量() throws Exception {
        Long batchId = setupBatchWithReferenceOnly();

        // mock STYLE + PROMPT（count=1 → 1 条 prompt）
        long runId = 888L;
        when(styleAnalysisService.analyze(batchId)).thenReturn(
                new StyleAnalysisResponse(10L, "测试风格", "描述", "{}", batchId));
        when(promptBuilderService.generatePrompts(eq(batchId), argThat(r -> r != null && r.count() == 1)))
                .thenReturn(List.of(new PromptGenerationResponse(1L, runId, 1, "p")));
        when(imageGeneratorService.generateImages(runId)).thenReturn(
                new ImageGenerationSummary(runId, 1, 1, 0, List.of()));
        when(imageScorerService.scoreImages(runId)).thenReturn(
                new ScoreSummary(runId, 1, 1, 0, 1, List.of()));
        when(packageAssemblerService.assemble(eq(runId), eq(1))).thenReturn(
                new PackageAssemblyResponse(5L, runId, batchId, "标题", List.of("标签"),
                        List.of(new PackageImageItem(1L, "test/1.png", 0, new BigDecimal("95")))));

        pipelineOrchestratorService.executeAsync(batchId, 1);

        Run run = waitForRunTerminalStatus(batchId, 10);
        assertNotNull(run);
        assertEquals("SUCCESS", run.getStatus());
        // 验证 PromptBuilder 收到 count=1 的 request
        verify(promptBuilderService).generatePrompts(eq(batchId), argThat(r -> r != null && r.count() == 1));
        // 验证 PackageAssembler 收到 count=1
        verify(packageAssemblerService).assemble(eq(runId), eq(1));
    }
```

需新增 import：`import static org.mockito.ArgumentMatchers.argThat;`（如未有）。

- [ ] **Step 6: 运行测试验证通过**

Run: `cd E:/dev_projects/lucky-forge && ./mvnw test -Pintegration -Dtest=PipelineOrchestratorServiceIT -q`
Expected: 全部 PASS（含新增用例 + 原 10 个 = 11 个）。

- [ ] **Step 7: Commit**

```bash
cd E:/dev_projects/lucky-forge
git add src/main/java/com/lucky/luckyforge/application/packageassembler/PackageAssemblerService.java \
        src/main/java/com/lucky/luckyforge/application/packageassembler/PackageAssemblerServiceImpl.java \
        src/main/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorService.java \
        src/main/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceImpl.java \
        src/test/java/com/lucky/luckyforge/application/pipeline/PipelineOrchestratorServiceIT.java
git commit -m "feat: 流水线支持指定生成张数（count 透传 PromptBuilder + PackageAssembler）"
```

---

### Task 2: 后端 — Controller + DTO 接收 count

**Files:**
- Create: `src/main/java/com/lucky/luckyforge/application/pipeline/dto/PipelineExecuteRequest.java`
- Modify: `src/main/java/com/lucky/luckyforge/api/controller/PipelineOrchestratorController.java`

**Interfaces:**
- Consumes: `pipelineOrchestratorService.executeAsync(Long batchId, Integer count)`（Task 1 产出）。
- Produces: `POST /api/batches/{id}/pipeline` 接收可选 body `{count}`。

- [ ] **Step 1: 新建 PipelineExecuteRequest DTO**

创建 `src/main/java/com/lucky/luckyforge/application/pipeline/dto/PipelineExecuteRequest.java`：

```java
package com.lucky.luckyforge.application.pipeline.dto;

/**
 * 流水线执行请求（可选指定生成张数）。
 *
 * @param count 生成张数（可空：空则用 batch.targetCount；范围 1-12）
 */
public record PipelineExecuteRequest(
        Integer count
) {
    public PipelineExecuteRequest {
        if (count != null && (count < 1 || count > 12)) {
            throw new IllegalArgumentException("count 范围 1-12");
        }
    }
}
```

- [ ] **Step 2: Controller 加 body 参数**

修改 `PipelineOrchestratorController.java` 的 execute 方法：

```java
    @PostMapping("/{batchId}/pipeline")
    public ResponseEntity<ApiResponse<Long>> execute(@PathVariable Long batchId,
                                                     @RequestBody(required = false) PipelineExecuteRequest request) {
        Integer count = request != null ? request.count() : null;
        Long trackId = pipelineOrchestratorService.executeAsync(batchId, count);
        return ResponseEntity.ok(ApiResponse.success("流水线已启动，轮询 GET /" + batchId + "/pipeline/status 查进度", trackId));
    }
```

新增 import：`import com.lucky.luckyforge.application.pipeline.dto.PipelineExecuteRequest;` 和 `import org.springframework.web.bind.annotation.RequestBody;`。

- [ ] **Step 3: 编译验证**

Run: `cd E:/dev_projects/lucky-forge && ./mvnw compile -q`
Expected: BUILD SUCCESS（无编译错误）。

- [ ] **Step 4: Commit**

```bash
cd E:/dev_projects/lucky-forge
git add src/main/java/com/lucky/luckyforge/application/pipeline/dto/PipelineExecuteRequest.java \
        src/main/java/com/lucky/luckyforge/api/controller/PipelineOrchestratorController.java
git commit -m "feat: POST /pipeline 接收可选 count 参数（PipelineExecuteRequest）"
```

---

### Task 3: 前端 — 张数选择器 + api 传参

**Files:**
- Modify: `frontend/src/api/index.js`
- Modify: `frontend/src/views/BatchDetail.vue`

**Interfaces:**
- Consumes: `POST /api/batches/{id}/pipeline` 现接收 body `{count}`（Task 2 产出）。
- Produces: BatchDetail 页面"一键执行"旁有张数选择器。

- [ ] **Step 1: api/index.js — runPipeline 加 count 参数**

把：
```js
export const runPipeline = (batchId) =>
  api.post(`/batches/${batchId}/pipeline`)
```
改为：
```js
export const runPipeline = (batchId, count) =>
  api.post(`/batches/${batchId}/pipeline`, count != null ? { count } : {})
```

- [ ] **Step 2: BatchDetail.vue — 加张数选择器**

在 script 区找到 `doPipeline` 方法定义前，加一个响应式变量（用 batch.targetCount 做默认，已有的 `batch` ref 加载后有值）：

```js
const generateCount = ref(4)
```

在 `load()` 方法里，batch 加载后同步默认值：
```js
    batch.value = await getBatchDetail(batchId)
    generateCount.value = batch.value.targetCount || 4
```

- [ ] **Step 3: BatchDetail.vue — template 加选择器**

在"一键执行"按钮之前（`<div class="action-section">` 内，`<button>` 之前），加：

```html
      <div class="count-selector">
        <span class="count-label">生成张数</span>
        <el-input-number v-model="generateCount" :min="1" :max="12" size="default" :disabled="running" />
      </div>
```

- [ ] **Step 4: BatchDetail.vue — doPipeline 传 count**

把 `doPipeline` 里的：
```js
    await runPipeline(batchId)
```
改为：
```js
    await runPipeline(batchId, generateCount.value)
```

- [ ] **Step 5: 重启前端 + 人工验证**

前端 Vite HMR 自动热更新。在浏览器强刷，进入 batch 详情页：
- 预期"一键执行"按钮上方出现"生成张数"选择器，默认值为 batch 的 targetCount
- 可调整 1-12
- 点执行后流水线按选择的张数生成（需配合后端重启验证）

- [ ] **Step 6: Commit**

```bash
cd E:/dev_projects/lucky-forge
git add frontend/src/api/index.js frontend/src/views/BatchDetail.vue
git commit -m "feat: BatchDetail 加生成张数选择器，执行时传 count"
```

---

## Self-Review

**1. Spec coverage:**
- spec「DTO PipelineExecuteRequest」→ Task 2 Step 1 ✓
- spec「Controller 加 body 参数」→ Task 2 Step 2 ✓
- spec「executeAsync/execute 加 count 重载」→ Task 1 Step 3-4 ✓
- spec「PromptBuilder 用 count 覆盖」→ Task 1 Step 4（generatePrompts 传 PromptGenerationRequest(count)）✓
- spec「PackageAssembler 加重载用 count 做 TopN」→ Task 1 Step 1-2 ✓
- spec「前端 el-input-number 默认 batch 值 1-12」→ Task 3 Step 2-3 ✓
- spec「runPipeline 传 count」→ Task 3 Step 1/4 ✓
- spec「不改 batch 表 / 不改现有签名（重载）」→ 全程用重载 ✓

**2. Placeholder scan:** 无 TBD/TODO；每步完整代码 ✓

**3. Type consistency:** `PipelineExecuteRequest(Integer count)`、`executeAsync(Long, Integer)`、`assemble(Long, Integer)`、`PromptGenerationRequest(Integer count)` 签名一致；前端 `runPipeline(batchId, count)` 一致 ✓
