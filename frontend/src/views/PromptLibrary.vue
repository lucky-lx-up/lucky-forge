<template>
  <div class="prompt-library-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">提示词库</h2>
        <p class="page-desc">沉淀已验证的好提示词，按风格归档；点击任意卡片直接出图，跳过分析步骤，效果更稳定</p>
      </div>
      <div class="header-actions">
        <el-button type="primary" size="large" @click="openCreateDialog">
          <el-icon><Plus /></el-icon>
          <span>录入提示词</span>
        </el-button>
      </div>
    </div>

    <!-- 标签页：提示词库 / 出图历史 -->
    <el-tabs v-model="activeTab" class="library-tabs" @tab-change="onTabChange">
      <el-tab-pane label="提示词库" name="library" />
      <el-tab-pane label="出图历史" name="history" />
    </el-tabs>

    <!-- ===== 提示词库 tab ===== -->
    <template v-if="activeTab === 'library'">
    <!-- 筛选区 -->
    <el-card class="filter-card" shadow="never">
      <div class="filter-row">
        <span class="filter-label">风格：</span>
        <el-select
          v-model="filterStyleId"
          placeholder="全部风格"
          clearable
          @change="loadItems"
          style="width: 240px"
        >
          <el-option
            v-for="s in styles"
            :key="s.id"
            :label="`${s.name}（${s.vertical}）`"
            :value="s.id"
          />
        </el-select>
        <span class="filter-label" style="margin-left: 16px">垂类：</span>
        <el-select v-model="filterVertical" placeholder="全部" clearable @change="loadItems" style="width: 160px">
          <el-option label="壁纸 WALLPAPER" value="WALLPAPER" />
          <el-option label="头像 AVATAR" value="AVATAR" />
          <el-option label="海报 POSTER" value="POSTER" />
        </el-select>
        <span class="filter-count" v-if="items.length > 0">共 {{ items.length }} 条 · 点击卡片查看详情</span>
      </div>
    </el-card>

    <!-- 库列表 -->
    <div v-loading="loading" class="prompt-grid">
      <el-card
        v-for="it in items"
        :key="it.id"
        class="prompt-card"
        shadow="hover"
        @click="goDetail(it.id)"
      >
        <div class="card-top">
          <el-tag size="small" type="info" effect="plain">{{ it.vertical }}</el-tag>
          <span class="usage-count" title="累计出图次数">🔁 {{ it.usageCount || 0 }}</span>
          <el-button
            class="card-delete"
            type="danger"
            size="small"
            text
            :icon="Delete"
            @click.stop="confirmDelete(it)"
          />
        </div>

        <div class="prompt-content" :title="it.content">{{ it.content }}</div>

        <!-- 点击查看详情提示 -->
        <div class="generate-hint">
          <el-icon><View /></el-icon>
          <span>点击查看详情</span>
        </div>

        <div class="card-meta">
          <span class="style-name" v-if="it.styleName">
            <el-icon><MagicStick /></el-icon>
            {{ it.styleName }}
          </span>
          <span v-if="it.sourcePromptId" class="source-badge">来自归档</span>
        </div>

        <div class="tags-row" v-if="it.tags?.length">
          <el-tag v-for="t in it.tags" :key="t" size="small" round effect="plain" class="tag-chip">{{ t }}</el-tag>
        </div>

        <div class="note-row" v-if="it.note">
          <el-icon><ChatLineRound /></el-icon>
          <span>{{ it.note }}</span>
        </div>
      </el-card>
    </div>

    <el-empty v-if="!loading && items.length === 0" description="暂无提示词，可手动录入或从工作台归档" />
    </template><!-- /提示词库 tab -->

    <!-- ===== 出图历史 tab ===== -->
    <template v-else>
      <!-- 筛选区（与库 tab 共用风格/垂类筛选） -->
      <el-card class="filter-card" shadow="never">
        <div class="filter-row">
          <span class="filter-label">风格：</span>
          <el-select
            v-model="filterStyleId"
            placeholder="全部风格"
            clearable
            @change="loadRuns"
            style="width: 240px"
          >
            <el-option
              v-for="s in styles"
              :key="s.id"
              :label="`${s.name}（${s.vertical}）`"
              :value="s.id"
            />
          </el-select>
          <span class="filter-label" style="margin-left: 16px">垂类：</span>
          <el-select v-model="filterVertical" placeholder="全部" clearable @change="loadRuns" style="width: 160px">
            <el-option label="壁纸 WALLPAPER" value="WALLPAPER" />
            <el-option label="头像 AVATAR" value="AVATAR" />
            <el-option label="海报 POSTER" value="POSTER" />
          </el-select>
          <span class="filter-count" v-if="runs.length > 0">共 {{ runs.length }} 次出图 · 点击卡片查看完整结果</span>
        </div>
        <!-- 批量操作栏（有选中时显示） -->
        <div v-if="selectedRunIds.size > 0" class="batch-bar">
          <el-checkbox
            :model-value="isAllRunsSelected"
            :indeterminate="isRunsIndeterminate"
            @change="toggleSelectAllRuns"
          >全选</el-checkbox>
          <el-button type="danger" @click="confirmDeleteRuns">
            <el-icon><Delete /></el-icon>
            <span>删除选中（{{ selectedRunIds.size }}）</span>
          </el-button>
        </div>
      </el-card>

      <!-- 历史卡片网格 -->
      <div v-loading="runsLoading" class="prompt-grid">
        <el-card
          v-for="r in runs"
          :key="r.runId"
          class="prompt-card run-card"
          shadow="hover"
          @click="goRunDetail(r.runId)"
        >
          <!-- 首图缩略图（出图失败的卡片显示占位） -->
          <div class="run-thumb">
            <el-image
              v-if="r.firstPreviewUrl"
              :src="r.firstPreviewUrl"
              fit="cover"
              class="run-thumb-img"
              loading="lazy"
            >
              <template #placeholder>
                <div class="run-thumb-loading">加载中...</div>
              </template>
            </el-image>
            <div v-else class="run-thumb-empty">
              <el-icon :size="28"><Picture /></el-icon>
              <span>{{ r.status === 'RUNNING' || r.status === 'PENDING' ? '生成中' : '无图' }}</span>
            </div>
            <!-- 状态标签悬浮在缩略图上 -->
            <el-tag
              :type="runStatusType(r.status)"
              size="small"
              effect="dark"
              class="run-status-tag"
            >{{ runStatusLabel(r.status) }}</el-tag>
            <!-- 平均分徽章 -->
            <div v-if="r.avgScore" class="run-score-badge" :class="runScoreClass(r.avgScore)">
              ⭐ {{ Number(r.avgScore).toFixed(1) }}
            </div>
          </div>

          <div class="card-top">
            <el-checkbox
              :model-value="selectedRunIds.has(r.runId)"
              @click.stop
              @change="toggleSelectRun(r.runId)"
              class="run-checkbox"
            />
            <el-tag size="small" type="info" effect="plain">{{ r.vertical }}</el-tag>
            <span class="run-id">#{{ r.runId }}</span>
            <span class="run-prompt-count">{{ r.promptCount }} 条</span>
            <el-button
              class="card-delete"
              type="danger"
              size="small"
              text
              :icon="Delete"
              @click.stop="confirmDeleteRun(r)"
            />
          </div>

          <div class="card-meta">
            <span class="style-name" v-if="r.styleName">
              <el-icon><MagicStick /></el-icon>
              {{ r.styleName }}
            </span>
            <span class="run-time">{{ formatRunTime(r.finishedAt || r.startedAt) }}</span>
          </div>
        </el-card>
      </div>

      <el-empty v-if="!runsLoading && runs.length === 0" description="暂无出图历史，在提示词库点「查看详情」即可出图" />
    </template><!-- /出图历史 tab -->

    <!-- 录入对话框 -->
    <el-dialog v-model="showCreate" title="录入提示词到库" width="560px">
      <el-form label-width="80px">
        <el-form-item label="风格">
          <el-select v-model="createForm.styleId" placeholder="选择风格" style="width: 100%">
            <el-option
              v-for="s in styles"
              :key="s.id"
              :label="`${s.name}（${s.vertical}）`"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="提示词">
          <el-input
            v-model="createForm.content"
            type="textarea"
            :rows="5"
            placeholder="英文提示词，将直接送 gpt-image-2 出图"
            maxlength="8000"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.note" placeholder="如：适合夜景（可选）" maxlength="500" />
        </el-form-item>
        <el-form-item label="标签">
          <div class="tags-input">
            <el-tag
              v-for="(t, i) in createForm.tags"
              :key="i"
              closable
              @close="createForm.tags.splice(i, 1)"
              class="tag-chip"
            >{{ t }}</el-tag>
            <el-input
              v-if="createTagInputVisible"
              v-model="createTagValue"
              ref="tagInputRef"
              size="small"
              style="width: 120px"
              @keyup.enter="addCreateTag"
              @blur="addCreateTag"
            />
            <el-button v-else size="small" @click="showCreateTagInput">+ 标签</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" @click="doCreate" :loading="creating">录入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Delete, MagicStick, ChatLineRound, View, Picture } from '@element-plus/icons-vue'
import {
  listPromptLibrary,
  listPromptLibraryStyles,
  createPromptLibraryItem,
  deletePromptLibraryItem,
  listLibraryRuns,
  deleteLibraryRun,
  deleteLibraryRuns
} from '../api'

const router = useRouter()

const items = ref([])
const styles = ref([])
const loading = ref(false)

const filterStyleId = ref(null)
const filterVertical = ref(null)

// ===== 标签页：提示词库 / 出图历史 =====
const activeTab = ref('library')
const runs = ref([])
const runsLoading = ref(false)
const selectedRunIds = ref(new Set())
// 防止两个 tab 共用 filterStyleId/filterVertical 时相互触发重复加载
let runsLoaded = false

const isAllRunsSelected = computed(() =>
  runs.value.length > 0 && selectedRunIds.value.size === runs.value.length
)
const isRunsIndeterminate = computed(() =>
  selectedRunIds.value.size > 0 && selectedRunIds.value.size < runs.value.length
)

const toggleSelectAllRuns = (checked) => {
  selectedRunIds.value = checked ? new Set(runs.value.map(r => r.runId)) : new Set()
}

const toggleSelectRun = (runId) => {
  const next = new Set(selectedRunIds.value)
  if (next.has(runId)) {
    next.delete(runId)
  } else {
    next.add(runId)
  }
  selectedRunIds.value = next
}

// tab 切换：首次进入 history 懒加载历史记录
const onTabChange = (tab) => {
  if (tab === 'history' && !runsLoaded) {
    loadRuns()
  }
}

// 点击库卡片 → 进入详情页
const goDetail = (id) => {
  router.push(`/prompts/${id}`)
}

// 点击历史卡片 → 进入出图结果页
const goRunDetail = (runId) => {
  router.push(`/prompts/run/${runId}`)
}

const loadItems = async () => {
  loading.value = true
  try {
    const params = {}
    if (filterStyleId.value) params.styleId = filterStyleId.value
    if (filterVertical.value) params.vertical = filterVertical.value
    items.value = await listPromptLibrary(params)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

const loadStyles = async () => {
  try {
    styles.value = await listPromptLibraryStyles()
  } catch (e) {
    console.warn('加载风格列表失败:', e.message)
  }
}

// ===== 出图历史 =====
const loadRuns = async () => {
  runsLoading.value = true
  try {
    const params = {}
    if (filterStyleId.value) params.styleId = filterStyleId.value
    if (filterVertical.value) params.vertical = filterVertical.value
    runs.value = await listLibraryRuns(params)
    runsLoaded = true
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    runsLoading.value = false
  }
}

// 运行状态映射（与 LibraryRunDetail 一致：PENDING/RUNNING/SUCCESS/FAILED）
const runStatusType = (s) => ({
  PENDING: 'info', RUNNING: 'warning', SUCCESS: 'success', FAILED: 'danger'
}[s] || 'info')

const runStatusLabel = (s) => ({
  PENDING: '待执行', RUNNING: '执行中', SUCCESS: '已完成', FAILED: '失败'
}[s] || s)

const runScoreClass = (score) => {
  const s = Number(score)
  if (s >= 90) return 'score-excellent'
  if (s >= 80) return 'score-good'
  return 'score-normal'
}

const formatRunTime = (t) => {
  if (!t) return ''
  return t.replace('T', ' ').slice(0, 16)
}

// 删除出图历史记录（连图片和打分一起彻底删除）
const confirmDeleteRun = async (run) => {
  try {
    await ElMessageBox.confirm(
      `确定删除这次出图记录？\n将同时删除关联的图片和打分，操作不可恢复。`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
    await deleteLibraryRun(run.runId)
    ElMessage.success('已删除')
    runs.value = runs.value.filter(r => r.runId !== run.runId)
    selectedRunIds.value = new Set([...selectedRunIds.value].filter(id => id !== run.runId))
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}

// 批量删除（宽容模式：后端跳过已归档的，返回删了几条/跳过几条）
const confirmDeleteRuns = async () => {
  const ids = [...selectedRunIds.value]
  if (ids.length === 0) return
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${ids.length} 条出图记录？\n将同时删除关联的图片和打分，操作不可恢复。\n（已归档的记录会自动跳过）`,
      '批量删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
    const result = await deleteLibraryRuns(ids)
    let msg = `已删除 ${result.deleted} 条`
    if (result.skipped > 0) {
      msg += `，${result.skipped} 条已归档未删除`
    }
    ElMessage.success(msg)
    selectedRunIds.value = new Set()
    await loadRuns()
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}

const confirmDelete = async (it) => {
  try {
    await ElMessageBox.confirm(
      `确定删除这条提示词？\n${it.content.slice(0, 50)}${it.content.length > 50 ? '...' : ''}`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
    await deletePromptLibraryItem(it.id)
    ElMessage.success('已删除')
    await loadItems()
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}

// ===== 录入对话框 =====
const showCreate = ref(false)
const creating = ref(false)
const createForm = ref({ styleId: null, content: '', note: '', tags: [] })
const createTagInputVisible = ref(false)
const createTagValue = ref('')
const tagInputRef = ref(null)

const openCreateDialog = () => {
  createForm.value = { styleId: filterStyleId.value || null, content: '', note: '', tags: [] }
  showCreate.value = true
}

const showCreateTagInput = () => {
  createTagInputVisible.value = true
  nextTick(() => tagInputRef.value?.focus())
}

const addCreateTag = () => {
  const v = createTagValue.value.trim()
  if (v && !createForm.value.tags.includes(v)) {
    createForm.value.tags.push(v)
  }
  createTagInputVisible.value = false
  createTagValue.value = ''
}

const doCreate = async () => {
  if (!createForm.value.styleId) {
    ElMessage.warning('请选择风格')
    return
  }
  if (!createForm.value.content.trim()) {
    ElMessage.warning('请输入提示词')
    return
  }
  creating.value = true
  try {
    await createPromptLibraryItem({
      styleId: createForm.value.styleId,
      content: createForm.value.content.trim(),
      note: createForm.value.note.trim() || null,
      tags: createForm.value.tags.length > 0 ? createForm.value.tags : null
    })
    ElMessage.success('已录入')
    showCreate.value = false
    await loadItems()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    creating.value = false
  }
}

onMounted(async () => {
  await loadStyles()
  await loadItems()
})
</script>

<style scoped>
.prompt-library-page {
  max-width: 1200px;
  margin: 0 auto;
}

/* 标签页：激活色用品牌紫，与全局风格统一 */
.library-tabs {
  margin-bottom: 8px;
}
.library-tabs :deep(.el-tabs__active-bar) {
  background-color: var(--brand-primary);
}
.library-tabs :deep(.el-tabs__item.is-active) {
  color: var(--brand-primary);
}
.library-tabs :deep(.el-tabs__item:hover) {
  color: var(--brand-primary);
}
.library-tabs :deep(.el-tabs__item) {
  font-size: 15px;
  font-weight: 500;
}

/* ===== 出图历史卡片 ===== */
.run-card {
  overflow: hidden;
}

/* 批量操作栏 */
.batch-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed #e4e7ed;
}

.run-checkbox {
  margin-right: 0;
}

.run-thumb {
  position: relative;
  width: 100%;
  height: 180px;
  background: #f5f7fa;
  margin-bottom: 10px;
  border-radius: 8px;
  overflow: hidden;
}

.run-thumb-img {
  width: 100%;
  height: 100%;
  display: block;
}

.run-thumb-loading {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c0c4cc;
  font-size: 13px;
}

.run-thumb-empty {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #c0c4cc;
}

.run-status-tag {
  position: absolute;
  top: 8px;
  left: 8px;
}

.run-score-badge {
  position: absolute;
  top: 8px;
  right: 8px;
  padding: 3px 9px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 700;
  color: #fff;
  backdrop-filter: blur(4px);
}

.score-excellent { background: rgba(245, 158, 11, 0.9); }
.score-good { background: rgba(34, 197, 94, 0.9); }
.score-normal { background: rgba(100, 116, 139, 0.9); }

.run-id {
  font-size: 13px;
  color: #c0c4cc;
  font-weight: 600;
}

.run-prompt-count {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}

.run-time {
  font-size: 12px;
  color: #c0c4cc;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-title {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  color: #1d2129;
}

.page-desc {
  margin: 4px 0 0 0;
  font-size: 14px;
  color: #86909c;
}

.header-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.filter-card {
  margin-bottom: 16px;
  border-radius: 12px;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-label {
  font-size: 14px;
  color: #606266;
  white-space: nowrap;
}

.filter-count {
  margin-left: auto;
  font-size: 13px;
  color: #909399;
}

.prompt-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 16px;
}

.prompt-card {
  cursor: pointer;
  border-radius: 12px;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
  position: relative;
}

.prompt-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(99, 102, 241, 0.12);
}

.card-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.usage-count {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}

.card-delete {
  padding: 2px;
  color: #c0c4cc;
}

.card-delete:hover {
  color: #f56c6c;
}

.prompt-content {
  font-size: 14px;
  line-height: 1.6;
  color: #1d2129;
  margin-bottom: 10px;
  max-height: 96px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  word-break: break-word;
}

/* 点击出图提示（hover 显示） */
.generate-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 6px 0;
  margin-bottom: 10px;
  border: 1px dashed var(--brand-primary);
  border-radius: 6px;
  color: var(--brand-primary);
  font-size: 13px;
  font-weight: 500;
  background: rgba(99, 102, 241, 0.04);
  opacity: 0;
  transition: opacity 0.2s ease;
}

.prompt-card:hover .generate-hint {
  opacity: 1;
}

.card-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #86909c;
  margin-bottom: 8px;
}

.style-name {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--brand-primary);
  font-weight: 500;
}

.source-badge {
  padding: 2px 6px;
  background: #f0f9eb;
  color: #67c23a;
  border-radius: 4px;
  font-size: 11px;
}

.tags-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 8px;
}

.tag-chip {
  margin: 0;
}

.note-row {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  padding: 6px 8px;
  background: #f5f7fa;
  border-radius: 6px;
  font-size: 12px;
  color: #606266;
  line-height: 1.5;
}

/* 录入对话框标签输入 */
.tags-input {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
</style>
