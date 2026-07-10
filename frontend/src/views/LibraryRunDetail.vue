<template>
  <div class="run-detail-page">
    <div class="back-bar">
      <el-button text @click="$router.push('/prompts')">← 返回提示词库</el-button>
    </div>

    <!-- 头部信息卡 -->
    <el-card class="header-card" v-loading="loading">
      <div class="run-header">
        <div class="run-title-area">
          <h2 class="run-title">
            工作台出图
            <el-tag size="small" type="info" effect="plain">#{{ detail.runId }}</el-tag>
          </h2>
          <div class="run-meta">
            <el-tag :type="statusType(detail.status)" size="small">{{ statusLabel(detail.status) }}</el-tag>
            <span v-if="detail.styleName" class="meta-item">
              <el-icon><MagicStick /></el-icon> 风格：{{ detail.styleName }}
            </span>
            <span class="meta-item">{{ detail.vertical }}</span>
            <span class="meta-item">{{ detail.prompts?.length || 0 }} 条提示词</span>
          </div>
        </div>
        <!-- 进度指示（仅 RUNNING 时显示） -->
        <div v-if="detail.status === 'RUNNING'" class="progress-area">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>{{ stepLabel(detail.currentStep) }}中...</span>
        </div>
      </div>
      <div v-if="detail.error" class="error-banner">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ detail.error }}</span>
      </div>
    </el-card>

    <!-- 提示词结果列表 -->
    <div class="section-title" v-if="detail.prompts?.length">
      <span>出图结果</span>
      <span class="section-hint">效果满意可点击"归档"把提示词加入库</span>
    </div>

    <div v-loading="loading" class="prompt-result-list">
      <el-card
        v-for="p in detail.prompts"
        :key="p.promptId"
        class="prompt-result-card"
        shadow="never"
      >
        <!-- 左：图片预览 -->
        <div class="result-image-col">
          <div v-if="p.objectKey" class="image-wrapper" @click="p.previewUrl && openViewer(p.previewUrl)">
            <div v-if="p.score" class="score-badge" :class="scoreClass(p.score)">
              ⭐ {{ Number(p.score).toFixed(1) }}
            </div>
            <el-image
              v-if="p.previewUrl"
              :src="p.previewUrl"
              fit="cover"
              class="result-img"
              loading="lazy"
            >
              <template #placeholder>
                <div class="img-placeholder skeleton">
                  <el-skeleton-item variant="image" style="width: 100%; height: 100%" />
                </div>
              </template>
            </el-image>
            <div v-else class="img-placeholder">
              <el-icon :size="28"><Picture /></el-icon>
              <span>加载中...</span>
            </div>
          </div>
          <!-- objectKey 为空：RUNNING/PENDING 时是"生成中"，终态才是"出图失败" -->
          <div v-else-if="detail.status === 'RUNNING' || detail.status === 'PENDING'" class="img-placeholder generating">
            <el-icon :size="28" class="is-loading"><Loading /></el-icon>
            <span>生成中...</span>
          </div>
          <div v-else class="img-placeholder failed">
            <el-icon :size="28"><CircleClose /></el-icon>
            <span>出图失败</span>
          </div>
        </div>

        <!-- 右：提示词 + 打分明细 + 归档 -->
        <div class="result-info-col">
          <div class="prompt-text">{{ p.content }}</div>

          <div v-if="p.dimensions?.length" class="dim-scores">
            <div v-for="d in p.dimensions" :key="d.name" class="dim-bar">
              <span class="dim-name">{{ dimLabel(d.name) }}</span>
              <el-progress
                :percentage="Number(d.value)"
                :stroke-width="6"
                :color="dimColor(d.value)"
                :show-text="false"
                class="dim-progress"
              />
              <span class="dim-value">{{ Number(d.value).toFixed(0) }}</span>
            </div>
          </div>

          <div v-if="p.remark" class="remark">{{ p.remark }}</div>

          <div class="result-actions">
            <el-button
              v-if="p.objectKey && !p.archived"
              type="primary"
              plain
              size="small"
              @click="openArchiveDialog(p)"
            >
              <el-icon><CollectionTag /></el-icon>
              <span>归档到库</span>
            </el-button>
            <el-tag v-else-if="p.archived" type="success" size="small" effect="plain" round>
              ✓ 已归档
            </el-tag>
            <el-button
              v-if="p.objectKey && p.previewUrl"
              size="small"
              text
              @click="openViewer(p.previewUrl)"
            >查看大图</el-button>
          </div>
        </div>
      </el-card>
    </div>

    <el-empty v-if="!loading && (!detail.prompts || detail.prompts.length === 0)" description="暂无出图结果" />

    <!-- 归档对话框 -->
    <el-dialog v-model="showArchive" title="归档提示词到库" width="480px">
      <div class="archive-preview" v-if="archivingPrompt">
        <div class="archive-prompt-text">{{ archivingPrompt.content }}</div>
      </div>
      <el-form label-width="64px">
        <el-form-item label="备注">
          <el-input v-model="archiveForm.note" placeholder="如：适合夜景（可选）" maxlength="500" />
        </el-form-item>
        <el-form-item label="标签">
          <div class="tags-input">
            <el-tag
              v-for="(t, i) in archiveForm.tags"
              :key="i"
              closable
              @close="archiveForm.tags.splice(i, 1)"
              class="tag-chip"
            >{{ t }}</el-tag>
            <el-input
              v-if="archiveTagInputVisible"
              v-model="archiveTagValue"
              ref="archiveTagInputRef"
              size="small"
              style="width: 120px"
              @keyup.enter="addArchiveTag"
              @blur="addArchiveTag"
            />
            <el-button v-else size="small" @click="showArchiveTagInput">+ 标签</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showArchive = false">取消</el-button>
        <el-button type="primary" @click="doArchive" :loading="archiving">归档</el-button>
      </template>
    </el-dialog>

    <!-- 全局图片预览 -->
    <el-image-viewer
      v-if="viewerVisible"
      :url-list="[viewerUrl]"
      hide-on-click-modal
      @close="viewerVisible = false"
    />
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  MagicStick, Picture, CircleClose, CollectionTag,
  Loading, WarningFilled
} from '@element-plus/icons-vue'
import {
  getLibraryRunDetail,
  archiveFromRun,
  getPreviewUrl
} from '../api'

const route = useRoute()
const runId = route.params.runId

const detail = ref({})
const loading = ref(false)
let pollTimer = null

// 图片预览 viewer
const viewerVisible = ref(false)
const viewerUrl = ref('')
const openViewer = (url) => {
  viewerUrl.value = url
  viewerVisible.value = true
}

const statusType = (s) => ({
  PENDING: 'info', RUNNING: 'warning', SUCCESS: 'success', FAILED: 'danger'
}[s] || 'info')

const statusLabel = (s) => ({
  PENDING: '待执行', RUNNING: '执行中', SUCCESS: '已完成', FAILED: '失败'
}[s] || s)

const stepLabel = (step) => ({
  GENERATE: '出图', SCORE: '打分'
}[step] || '处理')

const scoreClass = (score) => {
  const s = Number(score)
  if (s >= 90) return 'score-excellent'
  if (s >= 80) return 'score-good'
  return 'score-normal'
}

const dimLabel = (name) => ({
  composition: '构图', color: '色彩', clarity: '清晰度', relevance: '契合度'
}[name] || name)

const dimColor = (val) => {
  const v = Number(val)
  if (v >= 90) return '#f59e0b'
  if (v >= 80) return '#22c55e'
  return '#64748b'
}

const load = async () => {
  try {
    const data = await getLibraryRunDetail(runId)
    // 为有 objectKey 的项预加载预览 URL
    await Promise.all((data.prompts || []).map(async (p) => {
      if (p.objectKey) {
        try {
          p.previewUrl = await getPreviewUrl(p.objectKey)
        } catch (e) {
          p.previewUrl = null
        }
      }
    }))
    detail.value = data

    // 终态停止轮询；运行中继续轮询
    if (pollTimer) {
      if (data.status === 'SUCCESS' || data.status === 'FAILED') {
        clearInterval(pollTimer)
        pollTimer = null
      }
    } else if (data.status === 'RUNNING' || data.status === 'PENDING') {
      startPolling()
    }
  } catch (e) {
    ElMessage.error(e.message)
    loading.value = false
  }
}

const startPolling = () => {
  pollTimer = setInterval(load, 3000)
}

// ===== 归档对话框 =====
const showArchive = ref(false)
const archiving = ref(false)
const archivingPrompt = ref(null)
const archiveForm = ref({ note: '', tags: [] })
const archiveTagInputVisible = ref(false)
const archiveTagValue = ref('')
const archiveTagInputRef = ref(null)

const openArchiveDialog = (p) => {
  archivingPrompt.value = p
  archiveForm.value = { note: '', tags: [] }
  showArchive.value = true
}

const showArchiveTagInput = () => {
  archiveTagInputVisible.value = true
  nextTick(() => archiveTagInputRef.value?.focus())
}

const addArchiveTag = () => {
  const v = archiveTagValue.value.trim()
  if (v && !archiveForm.value.tags.includes(v)) {
    archiveForm.value.tags.push(v)
  }
  archiveTagInputVisible.value = false
  archiveTagValue.value = ''
}

const doArchive = async () => {
  if (!archivingPrompt.value) return
  archiving.value = true
  try {
    await archiveFromRun({
      runId: Number(runId),
      items: [{
        promptId: archivingPrompt.value.promptId,
        note: archiveForm.value.note.trim() || null,
        tags: archiveForm.value.tags.length > 0 ? archiveForm.value.tags : null
      }]
    })
    ElMessage.success('已归档到提示词库')
    showArchive.value = false
    archivingPrompt.value.archived = true
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    archiving.value = false
  }
}

onMounted(async () => {
  loading.value = true
  await load()
  loading.value = false
})

onUnmounted(() => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})
</script>

<style scoped>
.run-detail-page {
  max-width: 1200px;
  margin: 0 auto;
}

.back-bar {
  margin-bottom: 8px;
}

.header-card {
  margin-bottom: 24px;
  border-radius: 12px;
}

.run-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}

.run-title {
  margin: 0 0 8px 0;
  font-size: 22px;
  font-weight: 700;
  color: #1d2129;
  display: flex;
  align-items: center;
  gap: 8px;
}

.run-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 14px;
  color: #86909c;
  flex-wrap: wrap;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.progress-area {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--brand-primary);
  font-weight: 500;
}

.progress-area .is-loading {
  animation: rotating 1.5s linear infinite;
}

@keyframes rotating {
  from { transform: rotate(0); }
  to { transform: rotate(360deg); }
}

.error-banner {
  margin-top: 12px;
  padding: 10px 12px;
  background: #fef0f0;
  border-radius: 8px;
  color: #f56c6c;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 0 0 16px 0;
  font-size: 18px;
  font-weight: 600;
  color: #1d2129;
}

.section-hint {
  font-size: 13px;
  font-weight: 400;
  color: #c0c4cc;
}

.prompt-result-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.prompt-result-card {
  border-radius: 12px;
  display: flex;
  overflow: hidden;
}

.prompt-result-card :deep(.el-card__body) {
  padding: 0;
  display: flex;
  width: 100%;
}

.result-image-col {
  flex: 0 0 240px;
  background: #f5f5f5;
}

.image-wrapper {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 240px;
  cursor: pointer;
}

.result-img {
  width: 100%;
  height: 100%;
  min-height: 240px;
  display: block;
}

.img-placeholder {
  width: 100%;
  height: 100%;
  min-height: 240px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #c0c4cc;
  background: #f5f7fa;
}

.img-placeholder.failed {
  color: #f56c6c;
}

.img-placeholder.generating {
  color: var(--brand-primary);
}

.img-placeholder.generating .is-loading {
  animation: rotating 1.5s linear infinite;
}

.img-placeholder.skeleton {
  padding: 0;
}

.score-badge {
  position: absolute;
  top: 10px;
  right: 10px;
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 700;
  z-index: 2;
  backdrop-filter: blur(4px);
}

.score-excellent { background: rgba(245, 158, 11, 0.9); color: #fff; }
.score-good { background: rgba(34, 197, 94, 0.9); color: #fff; }
.score-normal { background: rgba(100, 116, 139, 0.9); color: #fff; }

.result-info-col {
  flex: 1;
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.prompt-text {
  font-size: 14px;
  line-height: 1.6;
  color: #1d2129;
  word-break: break-word;
  max-height: 96px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
}

.dim-scores {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.dim-bar {
  display: flex;
  align-items: center;
  gap: 6px;
}

.dim-name {
  width: 42px;
  font-size: 11px;
  color: #86909c;
}

.dim-progress {
  flex: 1;
  max-width: 240px;
}

.dim-value {
  width: 24px;
  text-align: right;
  font-size: 11px;
  font-weight: 600;
  color: #4c555a;
  font-variant-numeric: tabular-nums;
}

.remark {
  padding: 6px 8px;
  background: #f5f7fa;
  border-radius: 6px;
  font-size: 12px;
  color: #606266;
  line-height: 1.5;
}

.result-actions {
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 12px;
  padding-top: 8px;
}

.archive-preview {
  margin-bottom: 16px;
  padding: 10px 12px;
  background: #f5f7fa;
  border-radius: 6px;
}

.archive-prompt-text {
  font-size: 13px;
  line-height: 1.5;
  color: #606266;
  word-break: break-word;
  max-height: 80px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
}

.tags-input {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.tag-chip {
  margin: 0;
}

/* 响应式：窄屏图片在上方 */
@media (max-width: 640px) {
  .prompt-result-card :deep(.el-card__body) {
    flex-direction: column;
  }
  .result-image-col {
    flex: 0 0 auto;
  }
  .image-wrapper,
  .result-img,
  .img-placeholder {
    min-height: 200px;
  }
}
</style>
