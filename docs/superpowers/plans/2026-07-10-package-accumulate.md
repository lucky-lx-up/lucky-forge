# 素材打包累积式改造实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把素材打包从覆盖式（每次删旧 package）改为累积式（历史 package 保留），让用户多次执行后能看到所有历史素材包。

**Architecture:** 删除 `PackageAssemblerServiceImpl.assemble()` 里"先删旧 package/image 再插新"的覆盖式块，直接追加新 package。同步改类注释和一个测试用例。

**Tech Stack:** Java 21 + Spring Boot 3 + MyBatis-Plus + JUnit 5 + Mockito

## Global Constraints

- ServiceImpl 用 `@Autowired` 字段注入，不构造器注入。
- 实体扁平 POJO，逻辑删除字段 `deletedAt` 由 MyBatis-Plus 全局管理。
- `*IT` 为 `@SpringBootTest` 集成测试，连真实 MySQL，`@Transactional` 回滚。
- 测试方法用中文名。
- 集成测试用 `-Pintegration` profile 触发。

---

## 文件结构

- 修改：`src/main/java/com/lucky/luckyforge/application/packageassembler/PackageAssemblerServiceImpl.java`（`assemble()` line 118-126 + 类注释 line 36-40）
- 测试：`src/test/java/com/lucky/luckyforge/application/packageassembler/PackageAssemblerServiceIT.java`（`覆盖式打包_二次覆盖旧数据` line 88-112）

---

### Task 1: 改测试断言为累积式（红色）

**Files:**
- Test: `src/test/java/com/lucky/luckyforge/application/packageassembler/PackageAssemblerServiceIT.java`

**Interfaces:**
- Consumes: `PackageAssemblerService.assemble(Long runId)`；已有 `setupRunWithScores(int[], int)` 辅助方法；`packageMapper.selectList(LambdaQueryWrapper)` 查 package。
- Produces: 验证二次打包后未删除 package 有 2 条（旧的保留 + 新的追加）。

- [ ] **Step 1: 改 `覆盖式打包_二次覆盖旧数据` 测试为累积式断言**

把 `PackageAssemblerServiceIT.java` 中（line 88-112）：

```java
    @Test
    void 覆盖式打包_二次覆盖旧数据() {
        Long[] ids = setupRunWithScores(new int[]{90}, 1);
        Long runId = ids[1];

        when(chatGpt2ApiClient.chatCompletion(anyString(), anyList()))
                .thenReturn("{\"title\":\"旧标题\",\"tags\":[\"旧\"]}");
        packageAssemblerService.assemble(runId);

        // 旧 package 数量
        long oldCount = packageMapper.selectCount(null);

        // 第二次打包
        reset(chatGpt2ApiClient);
        when(chatGpt2ApiClient.chatCompletion(anyString(), anyList()))
                .thenReturn("{\"title\":\"新标题\",\"tags\":[\"新\"]}");
        PackageAssemblyResponse resp2 = packageAssemblerService.assemble(runId);

        assertEquals("新标题", resp2.title());
        // 未删除的 package 仍 1 条（旧的逻辑删除了）
        List<Package> active = packageMapper.selectList(new LambdaQueryWrapper<Package>()
                .eq(Package::getBatchId, ids[0]));
        assertEquals(1, active.size());
        assertEquals("新标题", active.get(0).getTitle());
    }
```

替换为：

```java
    @Test
    void 累积式打包_二次追加历史保留() {
        Long[] ids = setupRunWithScores(new int[]{90}, 1);
        Long runId = ids[1];

        // 第一次打包
        when(chatGpt2ApiClient.chatCompletion(anyString(), anyList()))
                .thenReturn("{\"title\":\"旧标题\",\"tags\":[\"旧\"]}");
        packageAssemblerService.assemble(runId);

        // 第二次打包（同一 run，模拟重跑）
        reset(chatGpt2ApiClient);
        when(chatGpt2ApiClient.chatCompletion(anyString(), anyList()))
                .thenReturn("{\"title\":\"新标题\",\"tags\":[\"新\"]}");
        PackageAssemblyResponse resp2 = packageAssemblerService.assemble(runId);

        assertEquals("新标题", resp2.title());
        // 累积式：未删除的 package 应有 2 条（旧的保留 + 新的追加）
        List<Package> active = packageMapper.selectList(new LambdaQueryWrapper<Package>()
                .eq(Package::getBatchId, ids[0])
                .orderByDesc(Package::getId));
        assertEquals(2, active.size(), "累积式应保留历史 package");
        // 最新的是新标题，其次是旧标题（按 id 倒序）
        assertEquals("新标题", active.get(0).getTitle());
        assertEquals("旧标题", active.get(1).getTitle());
    }
```

- [ ] **Step 2: 运行测试验证其失败**

Run: `cd E:/dev_projects/lucky-forge && ./mvnw test -Pintegration -Dtest=PackageAssemblerServiceIT -q`
Expected: `累积式打包_二次追加历史保留` FAIL（当前覆盖式删旧，active 仍 1 条而非 2 条，`expected: <2> but was: <1>`）；其余用例 PASS。

- [ ] **Step 3: Commit**

```bash
cd E:/dev_projects/lucky-forge
git add src/test/java/com/lucky/luckyforge/application/packageassembler/PackageAssemblerServiceIT.java
git commit -m "test: 素材打包应为累积式，二次打包后历史 package 保留（红）"
```

---

### Task 2: 删除覆盖式逻辑，改为累积式（绿色）

**Files:**
- Modify: `src/main/java/com/lucky/luckyforge/application/packageassembler/PackageAssemblerServiceImpl.java:36-40`（注释）
- Modify: `src/main/java/com/lucky/luckyforge/application/packageassembler/PackageAssemblerServiceImpl.java:118-127`（删除覆盖块 + 章节号）

**Interfaces:**
- Consumes: Task 1 的测试。
- Produces: `assemble()` 直接追加新 package，不删旧的。

- [ ] **Step 1: 改类注释 line 36-40**

把：

```java
 * <p>流程：校验 run → 查 run 下所有 score（按 total 降序取 Top N）→ 多模态调 gpt-5.5 生成标题/标签
 * → 覆盖式写 lf_package + lf_package_image → 推进 run.currentStep=PACKAGE。
 *
 * <p>关键设计：一个 run 一个包；标题看 Top N 图生成（≤5 张）；sort_order 按 total 降序（最高分封面）；
 * 覆盖式（先删旧 image + 逻辑删旧 package，再插新）。
```

替换为：

```java
 * <p>流程：校验 run → 查 run 下所有 score（按 total 降序取 Top N）→ 多模态调 gpt-5.5 生成标题/标签
 * → 累积式写 lf_package + lf_package_image → 推进 run.currentStep=PACKAGE。
 *
 * <p>关键设计：一个 run 一个包；标题看 Top N 图生成（≤5 张）；sort_order 按 total 降序（最高分封面）；
 * 累积式（每次打包追加新 package，历史保留，多次执行可查看全部历史产出）。
```

- [ ] **Step 2: 删除覆盖式删除块 line 118-127**

把：

```java
        // 6. 覆盖式写库（@Transactional 保护）
        // 6.1 查该 batch 既有未删除 package，有则先删 image + 逻辑删 package
        List<Package> existingPackages = packageMapper.selectList(
                new LambdaQueryWrapper<Package>().eq(Package::getBatchId, run.getBatchId()));
        for (Package old : existingPackages) {
            packageImageMapper.delete(new LambdaQueryWrapper<PackageImage>()
                    .eq(PackageImage::getPackageId, old.getId()));
            packageMapper.deleteById(old.getId()); // 逻辑删除（deletedAt 生效）
        }
        // 6.2 插新 package
        Package pkg = new Package();
```

替换为：

```java
        // 6. 累积式写库（@Transactional 保护）：直接追加新 package，历史保留
        Package pkg = new Package();
```

- [ ] **Step 3: 运行测试验证全部通过**

Run: `cd E:/dev_projects/lucky-forge && ./mvnw test -Pintegration -Dtest=PackageAssemblerServiceIT -q`
Expected: 全部 PASS（含改名的累积式用例 + 原 3 个 = 4 个）。

- [ ] **Step 4: Commit**

```bash
cd E:/dev_projects/lucky-forge
git add src/main/java/com/lucky/luckyforge/application/packageassembler/PackageAssemblerServiceImpl.java
git commit -m "feat: 素材打包改为累积式，历史 package 保留

去掉 assemble() 里先删旧 package/image 的覆盖式逻辑，
每次执行追加新 package，多次执行可查看全部历史产出。"
```

---

### Task 3: 重启后端并真实接口验证

**Files:** 无（运行时验证）

**说明：** 涉及停/启本机进程，由控制器在主会话执行。

- [ ] **Step 1: 停止当前后端并重启加载新代码**

查 8080 PID → Stop-Process → `./mvnw spring-boot:run`，等待 `Started LuckyForgeApplication`。

- [ ] **Step 2: 验证 batch 466 的素材包列表现在返回 2 个**

```bash
curl -s "http://localhost:8080/api/batches/466/packages"
```
注意：#120 已被软删（deleted_at 非空），接口（走 MyBatis-Plus 逻辑删除过滤）仍只会返回 #121。这是正常的——修复只影响**修复后新执行**的累积行为，不会复活已软删的历史数据。

- [ ] **Step 3: 触发 batch 466 新一次执行，验证累积**

```bash
curl -s -X POST "http://localhost:8080/api/batches/466/pipeline"
# 等待流水线跑完（轮询 status 直到 SUCCESS/FAILED）
curl -s "http://localhost:8080/api/batches/466/packages"
```
Expected: 若流水线成功，packages 列表现在应包含 #121 + 新产的 package（**2 条**），证明累积式生效（新执行不再删旧的）。

- [ ] **Step 4: 前端验证（人工）**

浏览器 `http://localhost:5173`，进入 batch 466 详情页。若 Step 3 流水线成功，预期素材包列表显示多个卡片（历史保留）。

---

## Self-Review

**1. Spec coverage:**
- spec「生产代码」删除 line 118-126 覆盖式块 → Task 2 Step 2 ✓
- spec「类注释」覆盖式→累积式 → Task 2 Step 1 ✓
- spec「测试」覆盖式断言→累积式断言（2 条）→ Task 1 Step 1 ✓
- spec「不需要改的」前端/查询服务/表结构 → 计划未涉及，符合 ✓

**2. Placeholder scan:** 无 TBD/TODO；每步完整代码 ✓

**3. Type consistency:** `Package`/`PackageImage`/`LambdaQueryWrapper` 与现有测试一致；`setupRunWithScores` 返回 `Long[]{batchId, runId}` 与现有用法一致；断言 `active.size()==2` + getTitle() 与 Package 实体一致 ✓
