# 素材包详情页展示提示词 + 加入提示词库实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在素材包详情页每张图下方展示提示词文本 + "加入提示词库"按钮，打通"素材包→提示词库"的复用路径。

**Architecture:** 后端 PackageImageDetail 加 promptId/promptContent 字段，getPackageDetail 查询时通过 generated_image.promptId 关联补全提示词内容。新增按 promptId 归档的接口 POST /prompt-library/archive-prompts（不破坏现有 archiveFromRun）。前端 PackageDetail.vue 每张图卡片加提示词展示 + 归档按钮。

**Tech Stack:** Java 21 + Spring Boot 3 + MyBatis-Plus + Vue 3 + Element Plus

## Global Constraints

- ServiceImpl 用 `@Autowired` 字段注入，不构造器注入。
- DTO 为 record 类型，带紧凑构造器校验。
- Controller 方法逻辑在 try-catch 中，返回 `ResponseEntity<ApiResponse<T>>`。
- Service 方法返回 DTO 而非实体。
- 不改现有 archiveFromRun 方法（LibraryRunDetail 页面在用）。

---

## 文件结构

- 修改：`src/main/java/com/lucky/luckyforge/application/packagequery/dto/PackageImageDetail.java`（加字段）
- 修改：`src/main/java/com/lucky/luckyforge/application/packagequery/PackageQueryServiceImpl.java`（查 prompt）
- 新增：`src/main/java/com/lucky/luckyforge/application/promptlibrary/dto/ArchivePromptsRequest.java`
- 修改：`src/main/java/com/lucky/luckyforge/application/promptlibrary/PromptLibraryService.java`（接口加方法）
- 修改：`src/main/java/com/lucky/luckyforge/application/promptlibrary/PromptLibraryServiceImpl.java`（实现）
- 修改：`src/main/java/com/lucky/luckyforge/api/controller/PromptLibraryController.java`（加端点）
- 修改：`frontend/src/views/PackageDetail.vue`
- 修改：`frontend/src/api/index.js`

---

### Task 1: 后端 — getPackageDetail 补全提示词字段

**Files:**
- Modify: `src/main/java/com/lucky/luckyforge/application/packagequery/dto/PackageImageDetail.java`
- Modify: `src/main/java/com/lucky/luckyforge/application/packagequery/PackageQueryServiceImpl.java`

**Interfaces:**
- Consumes: `PackageQueryServiceImpl` 已注入 `generatedImageMapper`；`GeneratedImage` 实体有 `promptId` 字段。需新增注入 `PromptMapper`。
- Produces: `PackageImageDetail` 新增 `promptId`(Long) + `promptContent`(String) 字段；`getPackageDetail` 返回含提示词。

- [ ] **Step 1: PackageImageDetail 加字段**

把 `PackageImageDetail.java` 的 record 改为（新增最后两个字段 + import）：

```java
package com.lucky.luckyforge.application.packagequery.dto;

import com.lucky.luckyforge.application.imagescorer.dto.DimensionScore;

import java.math.BigDecimal;
import java.util.List;

/**
 * 素材包内的图片项（查询用，含预览 URL + 维度分明细 + 提示词）。
 *
 * @param generatedImageId 生成图 id
 * @param objectKey        MinIO 对象路径
 * @param sortOrder        包内排序
 * @param previewUrl       预签名预览 URL（1 小时有效）
 * @param score            打分总分（可空）
 * @param remark           打分评语（可空）
 * @param dimensions       维度分明细（composition/color/clarity/relevance；可空）
 * @param promptId         该图对应的提示词 id（可空）
 * @param promptContent    该图对应的提示词正文（可空）
 */
public record PackageImageDetail(
        Long generatedImageId,
        String objectKey,
        Integer sortOrder,
        String previewUrl,
        BigDecimal score,
        String remark,
        List<DimensionScore> dimensions,
        Long promptId,
        String promptContent
) {
}
```

- [ ] **Step 2: PackageQueryServiceImpl 新增 PromptMapper 注入 + 查询提示词**

在类的字段注入区（约 line 38-44），加：

```java
    @Autowired private PromptMapper promptMapper;
```

在 `getPackageDetail` 方法内，现有 `imageMap` 构建之后（约 line 67 之后），补一段查 prompt 的逻辑。在 `// 组装图片详情` 注释（约 line 73）之前插入：

```java
        // 查每张图对应的提示词（通过 generated_image.promptId 关联）
        List<Long> promptIds = imageMap.values().stream()
                .map(GeneratedImage::getPromptId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Prompt> promptMap = promptIds.isEmpty() ? Map.of()
                : promptMapper.selectList(new LambdaQueryWrapper<Prompt>()
                        .in(Prompt::getId, promptIds)).stream()
                .collect(Collectors.toMap(Prompt::getId, p -> p));
```

然后修改组装循环里的 `images.add(...)`（约 line 99-100），在构造 PackageImageDetail 时补入 promptId + promptContent。把：

```java
            images.add(new PackageImageDetail(pi.getGeneratedImageId(), objectKey,
                    pi.getSortOrder(), previewUrl, total, remark, dimensions));
```

改为：

```java
            Prompt prompt = gi != null && gi.getPromptId() != null
                    ? promptMap.get(gi.getPromptId()) : null;
            images.add(new PackageImageDetail(pi.getGeneratedImageId(), objectKey,
                    pi.getSortOrder(), previewUrl, total, remark, dimensions,
                    prompt != null ? prompt.getId() : null,
                    prompt != null ? prompt.getContent() : null));
```

新增 import（文件顶部）：`import com.lucky.luckyforge.infrastructure.persistence.entity.Prompt;` 和 `import com.lucky.luckyforge.infrastructure.persistence.mapper.PromptMapper;`。

- [ ] **Step 3: 编译验证**

Run: `cd E:/dev_projects/lucky-forge && ./mvnw compile -q`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
cd E:/dev_projects/lucky-forge
git add src/main/java/com/lucky/luckyforge/application/packagequery/dto/PackageImageDetail.java \
        src/main/java/com/lucky/luckyforge/application/packagequery/PackageQueryServiceImpl.java
git commit -m "feat: getPackageDetail 返回每张图的提示词（promptId + promptContent）"
```

---

### Task 2: 后端 — 新增按 promptId 归档接口

**Files:**
- Create: `src/main/java/com/lucky/luckyforge/application/promptlibrary/dto/ArchivePromptsRequest.java`
- Modify: `src/main/java/com/lucky/luckyforge/application/promptlibrary/PromptLibraryService.java`
- Modify: `src/main/java/com/lucky/luckyforge/application/promptlibrary/PromptLibraryServiceImpl.java`
- Modify: `src/main/java/com/lucky/luckyforge/api/controller/PromptLibraryController.java`

**Interfaces:**
- Consumes: `PromptMapper.selectById`、`RunMapper.selectById`、`BatchMapper.selectById`、`StyleMapper.selectById`、`PromptLibraryItemMapper.insert`（均已注入）。
- Produces: `POST /api/prompt-library/archive-prompts` 接口；`PromptLibraryService.archivePrompts(List<Long> promptIds)` 方法。

- [ ] **Step 1: 新建 ArchivePromptsRequest DTO**

创建 `src/main/java/com/lucky/luckyforge/application/promptlibrary/dto/ArchivePromptsRequest.java`：

```java
package com.lucky.luckyforge.application.promptlibrary.dto;

import java.util.List;

/**
 * 按 promptId 直接归档提示词到库的请求（不需传 runId，后端自动追溯）。
 *
 * @param promptIds 来源提示词 id 列表（必填，非空）
 */
public record ArchivePromptsRequest(
        List<Long> promptIds
) {
    public ArchivePromptsRequest {
        if (promptIds == null || promptIds.isEmpty()) {
            throw new IllegalArgumentException("promptIds 不能为空");
        }
    }
}
```

- [ ] **Step 2: PromptLibraryService 接口加方法**

在 `PromptLibraryService.java` 现有 `archiveFromRun` 声明之后，加：

```java
    /**
     * 按 promptId 直接归档提示词到库（自动追溯 run→batch→style 继承风格信息）。
     * <p>适用于素材包详情页等不知道 runId 的场景。
     *
     * @param promptIds 来源提示词 id 列表
     * @return 归档结果
     */
    List<PromptLibraryItemResponse> archivePrompts(List<Long> promptIds);
```

- [ ] **Step 3: PromptLibraryServiceImpl 实现 archivePrompts**

在 `PromptLibraryServiceImpl.java` 现有 `archiveFromRun` 方法之后，新增：

```java
    @Override
    @Transactional
    public List<PromptLibraryItemResponse> archivePrompts(List<Long> promptIds) {
        List<PromptLibraryItem> archived = new ArrayList<>(promptIds.size());
        for (Long promptId : promptIds) {
            // 1. 查 prompt
            Prompt prompt = promptMapper.selectById(promptId);
            if (prompt == null) {
                log.warn("归档跳过：promptId={} 不存在", promptId);
                continue;
            }
            // 2. 查 run → batch → style
            Run run = runMapper.selectById(prompt.getRunId());
            if (run == null) {
                log.warn("归档跳过：promptId={} 的 run 不存在", promptId);
                continue;
            }
            Batch batch = batchMapper.selectById(run.getBatchId());
            if (batch == null || batch.getStyleId() == null) {
                log.warn("归档跳过：promptId={} 的批次无风格", promptId);
                continue;
            }
            Style style = styleMapper.selectById(batch.getStyleId());
            if (style == null) {
                log.warn("归档跳过：promptId={} 的风格记录不存在", promptId);
                continue;
            }
            // 3. 归档
            PromptLibraryItem lib = new PromptLibraryItem();
            lib.setStyleId(style.getId());
            lib.setContent(prompt.getContent());
            lib.setVertical(style.getVertical());
            lib.setSourcePromptId(promptId);
            lib.setUsageCount(0);
            promptLibraryItemMapper.insert(lib);
            archived.add(lib);
        }
        if (archived.isEmpty()) {
            throw new BizException("没有可归档的提示词（均不存在或无风格）");
        }
        // 批量查风格名（archived 可能含不同 styleId）
        Map<Long, String> styleNameMap = archived.stream()
                .map(PromptLibraryItem::getStyleId).distinct()
                .map(styleMapper::selectById)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toMap(Style::getId, Style::getName));
        return archived.stream()
                .map(it -> toItemResponse(it, styleNameMap.get(it.getStyleId())))
                .toList();
    }
```

需确认 import 已有（ArrayList/Map/Collectors/Style 等大部分已有，因为 archiveFromRun 用了）。

- [ ] **Step 4: Controller 加端点**

在 `PromptLibraryController.java` 现有 `archive`（POST /archive）端点之后，加：

```java
    /** 按 promptId 直接归档提示词到库（不需 runId） */
    @PostMapping("/archive-prompts")
    public ResponseEntity<ApiResponse<List<PromptLibraryItemResponse>>> archivePrompts(
            @RequestBody ArchivePromptsRequest request) {
        try {
            List<PromptLibraryItemResponse> result = promptLibraryService.archivePrompts(request.promptIds());
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException e) {
            throw e;
        }
    }
```

新增 import：`import com.lucky.luckyforge.application.promptlibrary.dto.ArchivePromptsRequest;`。

- [ ] **Step 5: 编译验证**

Run: `cd E:/dev_projects/lucky-forge && ./mvnw compile -q`
Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
cd E:/dev_projects/lucky-forge
git add src/main/java/com/lucky/luckyforge/application/promptlibrary/dto/ArchivePromptsRequest.java \
        src/main/java/com/lucky/luckyforge/application/promptlibrary/PromptLibraryService.java \
        src/main/java/com/lucky/luckyforge/application/promptlibrary/PromptLibraryServiceImpl.java \
        src/main/java/com/lucky/luckyforge/api/controller/PromptLibraryController.java
git commit -m "feat: 新增按 promptId 归档接口（POST /prompt-library/archive-prompts）"
```

---

### Task 3: 前端 — PackageDetail 展示提示词 + 归档按钮

**Files:**
- Modify: `frontend/src/api/index.js`
- Modify: `frontend/src/views/PackageDetail.vue`

**Interfaces:**
- Consumes: `getPackageDetail` 返回的 images 现含 promptId/promptContent（Task 1）；`POST /prompt-library/archive-prompts`（Task 2）。
- Produces: PackageDetail 页面每张图下方显示提示词 + 归档按钮。

- [ ] **Step 1: api/index.js 新增 archivePrompts**

在 `archiveFromRun` 之后加：

```js
export const archivePrompts = (promptIds) =>
  api.post('/prompt-library/archive-prompts', { promptIds })
```

- [ ] **Step 2: PackageDetail.vue — import + 响应式状态**

在 import 行加 `archivePrompts`。在 script 区加响应式状态记录每张图的归档状态：

```js
const archivedImageIds = ref(new Set())
const archiving = ref(false)
```

- [ ] **Step 3: PackageDetail.vue — 归档方法**

加归档方法：

```js
const handleArchive = async (img) => {
  if (!img.promptId || archivedImageIds.value.has(img.generatedImageId)) return
  archiving.value = true
  try {
    await archivePrompts([img.promptId])
    archivedImageIds.value.add(img.generatedImageId)
    ElMessage.success('已加入提示词库')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    archiving.value = false
  }
}
```

确保 `ElMessage` 已 import（检查现有 import，如无则补）。

- [ ] **Step 4: PackageDetail.vue — template 加提示词展示 + 按钮**

在每张图的卡片里，维度分明细之后（wallpaper-card 内部最后），加提示词区块。找到现有的维度分/评语区域之后，加：

```html
        <!-- 提示词 + 归档 -->
        <div v-if="img.promptContent" class="prompt-section">
          <div class="prompt-text">{{ img.promptContent }}</div>
          <el-button
            size="small"
            :type="archivedImageIds.has(img.generatedImageId) ? 'success' : 'primary'"
            plain
            :disabled="archivedImageIds.has(img.generatedImageId) || archiving"
            @click="handleArchive(img)"
          >
            {{ archivedImageIds.has(img.generatedImageId) ? '✓ 已加入' : '加入提示词库' }}
          </el-button>
        </div>
```

加 CSS（style 区）：

```css
.prompt-section {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.prompt-text {
  font-size: 12px;
  color: #6b7280;
  line-height: 1.5;
  background: #f9fafb;
  padding: 6px 8px;
  border-radius: 4px;
}
```

- [ ] **Step 5: Commit**

```bash
cd E:/dev_projects/lucky-forge
git add frontend/src/api/index.js frontend/src/views/PackageDetail.vue
git commit -m "feat: 素材包详情页展示提示词 + 加入提示词库按钮"
```

---

### Task 4: 重启后端 + 端到端验证

**Files:** 无（运行时验证）

**说明：** 涉及停/启进程，由控制器在主会话执行。

- [ ] **Step 1: 重启后端加载新代码**

查 8080 PID → Stop-Process → IDEA 重启或 mvnw spring-boot:run。等待 Started。

- [ ] **Step 2: 验证 getPackageDetail 返回提示词**

Run: `curl -s "http://localhost:8080/api/packages/137" | python -c "import sys,json; d=json.load(sys.stdin)['data']; [print(f\"img {i['sortOrder']}: promptId={i.get('promptId')} content={i.get('promptContent','')[:40]}\") for i in d['images']]"`
Expected: 每张图都有 promptId 和 promptContent（非 null）。

- [ ] **Step 3: 验证归档接口**

Run: `curl -s -X POST "http://localhost:8080/api/prompt-library/archive-prompts" -H "Content-Type: application/json" -d '{"promptIds":[329]}' | head -c 200`
Expected: result SUCCESS，返回归档的库条目（promptId=329 对应的提示词）。

- [ ] **Step 4: 前端验证（人工）**

浏览器打开一个有多张图的素材包（如 `/packages/137`），预期：
- 每张图下方显示提示词文本
- 有"加入提示词库"按钮，点击后变"✓ 已加入"
- 到提示词库列表页能看到新加入的条目

---

## Self-Review

**1. Spec coverage:**
- spec「PackageImageDetail 加 promptId/promptContent」→ Task 1 Step 1 ✓
- spec「getPackageDetail 查 prompt」→ Task 1 Step 2 ✓
- spec「新增 archive-prompts 接口」→ Task 2（DTO + Service + Controller）✓
- spec「前端展示提示词 + 归档按钮」→ Task 3 ✓
- spec「不动现有 archiveFromRun」→ 计划新增独立方法 ✓

**2. Placeholder scan:** 无 TBD/TODO；每步完整代码 ✓

**3. Type consistency:** `PackageImageDetail(..., Long promptId, String promptContent)` 一致；`ArchivePromptsRequest(List<Long> promptIds)` 一致；`archivePrompts(List<Long>)` 返回 `List<PromptLibraryItemResponse>` 与现有 archiveFromRun 一致；前端 `archivePrompts(promptIds)` 一致 ✓
