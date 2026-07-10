# 修复：素材打包改为累积式，历史素材包保留可见

## 背景

用户对一个 batch 多次点击"一键执行流水线"，期望每次产出的素材包都保留、历史可见。但当前 `PackageAssemblerService.assemble()` 是**覆盖式**设计：每次打包前先逻辑删除该 batch 之前的所有 package（+ 物理删 package_image），再插入新的。导致一个 batch 在页面上永远只显示最新一次的素材包。

### 根因（已验证）

`PackageAssemblerServiceImpl.java:118-126`：

```java
// 6.1 查该 batch 既有未删除 package，有则先删 image + 逻辑删 package
List<Package> existingPackages = packageMapper.selectList(
        new LambdaQueryWrapper<Package>().eq(Package::getBatchId, run.getBatchId()));
for (Package old : existingPackages) {
    packageImageMapper.delete(new LambdaQueryWrapper<PackageImage>()
            .eq(PackageImage::getPackageId, old.getId()));
    packageMapper.deleteById(old.getId()); // 逻辑删除（deletedAt 生效）
}
```

类注释（line 39-40）也明确写了"覆盖式（先删旧 image + 逻辑删旧 package，再插新）"。

### DB 证据（batch 466）

- package #120（第一次执行产出）：`deleted_at = 2026-07-10 15:30:41`
- package #121（第二次执行产出）：`deleted_at = NULL`
- #120 的删除时间正好等于 #121 的创建时间——证实第二次打包把第一次的覆盖了。

## 修复方案

**删除覆盖式删除逻辑，改为直接追加新 package。**

### 改动点

1. **生产代码** `PackageAssemblerServiceImpl.assemble()`：删除 line 118-126 的覆盖式删除块（`existingPackages` 查询 + for 循环删 image + 逻辑删 package）。line 127 注释 `// 6.2 插新 package` 改为 `// 6. 插新 package`（章节号顺延），直接插入新 package，不碰旧的。

2. **类注释**（line 36-40）：
   - line 37 "→ 覆盖式写 lf_package + lf_package_image" → "→ 累积式写 lf_package + lf_package_image"
   - line 39-40 "一个 run 一个包；...覆盖式（先删旧 image + 逻辑删旧 package，再插新）" → "一个 run 一个包；...累积式（每次打包追加新 package，历史保留）"

3. **测试** `PackageAssemblerServiceIT`：
   - `覆盖式打包_二次覆盖旧数据`（line 88-112）→ 重命名为 `累积式打包_二次追加历史保留`，断言改为：二次打包后未删除 package 有 **2 条**（旧的保留 + 新的追加），分别校验标题。
   - `正常打包_sortOrder按分数降序`（line 75-77）：`assertEquals(1, pkgs.size())` 不变（单次打包仍只产 1 条，不受影响）。

### 不需要改的

- **前端**：`BatchDetail.vue` 用 `v-for="p in packages"` 渲染列表，本就支持多个。
- **查询服务** `PackageQueryServiceImpl.listPackagesByBatch`：`selectList` 无 LIMIT、`orderByDesc(id)` 最新优先，本就支持多个。
- **表结构**：`lf_package_image` 唯一键 `uk_pkgimg_pkg_gen(package_id, generated_image_id)` 是联合唯一，不同 package 可关联同一图，累积式不冲突。每次 run 独立产出新 prompt/图，实际也不会复用同一张图。
- **流水线编排**：`execute()` 调 `assemble(runId)`，每次 runId 不同，互不影响。

### 累积式安全性的依据

每次执行流水线，PromptBuilder 都会创建新的 run（新 runId）、新 prompt、新生成图。不同 run 的产出互不交叉。累积式下，多个 package 各自关联自己 run 的图，无数据冲突。

## 影响面

- 生产代码：`PackageAssemblerServiceImpl.java`（删 ~9 行 + 改注释）
- 测试：`PackageAssemblerServiceIT.java`（改 1 个测试方法的断言）
- 不涉及 DB schema、前端、其他 Service。
