<template>
  <div v-loading="loading" class="batch-detail-page">
    <div class="back-bar">
      <el-button text @click="$router.push('/batches')">← 返回批次列表</el-button>
    </div>

    <!-- 批次信息 -->
    <el-card class="info-card" v-if="batch.id">
      <div class="batch-header">
        <div>
          <h2 class="batch-title">批次 #{{ batch.id }}</h2>
          <p class="batch-theme">{{ batch.theme || '（未设置主题）' }}</p>
        </div>
        <el-tag :type="statusType(batch.status)" size="large">{{ statusLabel(batch.status) }}</el-tag>
      </div>
      <el-descriptions :column="4" border size="small" style="margin-top: 16px">
        <el-descriptions-item label="垂类">{{ batch.vertical }}</el-descriptions-item>
        <el-descriptions-item label="目标数">{{ batch.targetCount }} 张</el-descriptions-item>
        <el-descriptions-item label="风格">
          <span v-if="batch.styleId" class="link" @click="$router.push(`/styles/${batch.styleId}`)">#{{ batch.styleId }}</span>
          <span v-else class="muted">未提炼</span>
        </el-descriptions-item>
        <el-descriptions-item label="运行">
          <span v-if="batch.runId">Run #{{ batch.runId }} · {{ batch.runStatus }} · {{ batch.runCurrentStep }}</span>
          <span v-else class="muted">无</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 操作区 -->
    <el-card class="action-card">
      <template #header><b>🎬 生产操作</b></template>

      <!-- 已投喂的参考图 -->
      <div class="action-section" v-if="referenceImages.length > 0">
        <h4 class="action-title">已投喂的参考图（{{ referenceImages.length }} 张）</h4>
        <div class="ref-grid">
          <div v-for="r in referenceImages" :key="r.id" class="ref-item">
            <el-image
              :src="r.previewUrl"
              :preview-src-list="referenceImages.map(x => x.previewUrl)"
              fit="cover"
              class="ref-thumb"
              loading="lazy"
            >
              <template #error><div class="ref-error">加载失败</div></template>
              <template #placeholder><div class="ref-loading">...</div></template>
            </el-image>
          </div>
        </div>
      </div>

      <!-- 投喂参考图 -->
      <div class="action-section">
        <h4 class="action-title">① 投喂参考图</h4>
        <p class="action-hint">上传 1-5 张参考图，gpt-5.5 将分析它们的风格特征</p>
        <el-upload
          :show-file-list="false"
          :before-upload="() => false"
          :on-change="onFileChange"
          multiple
          accept="image/*"
          drag
          class="upload-area"
        >
          <el-icon class="upload-icon"><UploadFilled /></el-icon>
          <div class="upload-text">拖拽图片到此处，或点击选择</div>
        </el-upload>

        <!-- 已选图片缩略图预览 -->
        <div v-if="pendingFiles.length > 0" class="preview-row">
          <div v-for="(f, i) in pendingPreviews" :key="i" class="thumb-item">
            <img :src="f" class="thumb-img" />
            <el-button
              class="thumb-remove"
              circle
              size="small"
              type="danger"
              @click="removeFile(i)"
            ><el-icon><Close /></el-icon></el-button>
          </div>
          <el-button
            type="primary"
            @click="doUpload"
            :loading="uploading"
            class="upload-btn"
          >上传 {{ pendingFiles.length }} 张</el-button>
        </div>
      </div>

      <el-divider />

      <!-- 一键 pipeline -->
      <div class="action-section">
        <h4 class="action-title">② 一键执行流水线</h4>
        <p class="action-hint">串联执行：风格提炼 → 提示词生成 → 批量出图 → 自动打分 → 素材打包</p>
        <el-button
          type="success"
          size="large"
          @click="doPipeline"
          :loading="running"
          :disabled="running"
        >🚀 {{ running ? '执行中...' : '一键执行' }}</el-button>

        <!-- pipeline 步骤动画 -->
        <div v-if="running || pipelineResult" class="pipeline-steps">
          <div
            v-for="(step, i) in pipelineSteps"
            :key="i"
            class="step-item"
            :class="stepClass(i)"
          >
            <div class="step-icon">
              <el-icon v-if="step.status === 'done'"><Check /></el-icon>
              <el-icon v-else-if="step.status === 'running'" class="is-loading"><Loading /></el-icon>
              <el-icon v-else><Clock /></el-icon>
            </div>
            <div class="step-info">
              <div class="step-name">{{ step.name }}</div>
              <div class="step-detail" v-if="step.detail">{{ step.detail }}</div>
            </div>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 素材包列表 -->
    <el-card class="packages-card">
      <template #header><b>📦 产出的素材包</b></template>
      <el-empty v-if="packages.length === 0" description="暂无素材包（执行流水线后产出）" />
      <div v-else class="package-list">
        <el-card
          v-for="p in packages"
          :key="p.id"
          class="package-item"
          shadow="hover"
          @click="$router.push(`/packages/${p.id}`)"
        >
          <div class="pkg-header">
            <span class="pkg-title">{{ p.title }}</span>
            <el-tag size="small" :type="p.status === 'PUBLISHED' ? 'success' : 'info'">
              {{ p.status === 'PUBLISHED' ? '已发布' : '待发布' }}
            </el-tag>
          </div>
          <div class="pkg-tags">
            <el-tag v-for="t in p.tags" :key="t" size="small" effect="plain" round>{{ t }}</el-tag>
          </div>
          <div class="pkg-meta">📷 {{ p.imageCount }} 张 · #{{ p.id }}</div>
        </el-card>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled, Close, Check, Clock, Loading } from '@element-plus/icons-vue'
import { getBatchDetail, listPackagesByBatch, listReferenceImages, uploadReferenceImages, runPipeline, getPipelineStatus } from '../api'

const route = useRoute()
const batchId = route.params.id

const batch = ref({})
const packages = ref([])
const referenceImages = ref([])
const loading = ref(false)

const pendingFiles = ref([])
const pendingPreviews = ref([])
const uploading = ref(false)
const running = ref(false)

// pipeline 步骤状态
const STEP_DEFS = [
  { name: '风格提炼', key: 'STYLE' },
  { name: '提示词生成', key: 'PROMPT' },
  { name: '批量出图', key: 'GENERATE' },
  { name: '自动打分', key: 'SCORE' },
  { name: '素材打包', key: 'PACKAGE' }
]
const pipelineResult = ref(null)
const currentStepIndex = ref(-1)

const pipelineSteps = computed(() => {
  if (pipelineResult.value?.steps) {
    // 真实结果
    return STEP_DEFS.map(def => {
      const real = pipelineResult.value.steps.find(s => s.step === def.key)
      return {
        name: def.name,
        status: real ? (real.success ? 'done' : 'failed') : 'pending',
        detail: real ? (real.detail || real.errorMessage) : ''
      }
    })
  }
  // 运行中：模拟动画（逐步点亮）
  return STEP_DEFS.map((def, i) => ({
    name: def.name,
    status: i < currentStepIndex.value ? 'done' : (i === currentStepIndex.value ? 'running' : 'pending'),
    detail: ''
  }))
})

const stepClass = (i) => pipelineSteps.value[i]?.status || 'pending'

const statusType = (s) => ({ DRAFT: 'info', RUNNING: 'warning', DONE: 'success', FAILED: 'danger' }[s] || 'info')
const statusLabel = (s) => ({ DRAFT: '草稿', RUNNING: '生产中', DONE: '已完成', FAILED: '失败' }[s] || s)

const load = async () => {
  loading.value = true
  try {
    batch.value = await getBatchDetail(batchId)
    packages.value = await listPackagesByBatch(batchId)
    referenceImages.value = await listReferenceImages(batchId)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

const onFileChange = (file) => {
  pendingFiles.value.push(file.raw)
  // 生成缩略图预览
  const reader = new FileReader()
  reader.onload = (e) => pendingPreviews.value.push(e.target.result)
  reader.readAsDataURL(file.raw)
}

const removeFile = (index) => {
  pendingFiles.value.splice(index, 1)
  pendingPreviews.value.splice(index, 1)
}

const doUpload = async () => {
  uploading.value = true
  try {
    const result = await uploadReferenceImages(batchId, pendingFiles.value)
    ElMessage.success(`已上传 ${result.length} 张参考图`)
    pendingFiles.value = []
    pendingPreviews.value = []
    referenceImages.value = await listReferenceImages(batchId)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    uploading.value = false
  }
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms))

const doPipeline = async () => {
  try {
    await ElMessageBox.confirm(
      '将异步执行完整流水线（预计 2-5 分钟）。执行期间可继续浏览其他页面，完成后自动刷新。是否开始？',
      '确认执行流水线',
      { type: 'warning', confirmButtonText: '开始执行', cancelButtonText: '取消' }
    )
  } catch { return }

  running.value = true
  pipelineResult.value = null
  currentStepIndex.value = 0

  try {
    // 异步触发（立即返回）
    await runPipeline(batchId)
    ElMessage.info('流水线已启动，后台执行中...')
    // 开始轮询状态
    pollTimer.value = setInterval(pollStatus, 3000)
  } catch (e) {
    ElMessage.error(e.message)
    running.value = false
  }
}

const pollTimer = ref(null)

const pollStatus = async () => {
  try {
    const status = await getPipelineStatus(batchId)
    if (status.steps?.length > 0) {
      // 有真实步骤数据，更新展示
      currentStepIndex.value = status.steps.filter(s => s.success).length
      pipelineResult.value = { steps: status.steps, overallSuccess: false, overallMessage: status.overallMessage }
    } else {
      // 还没步骤数据，根据 currentStep 推进动画
      const idx = STEP_DEFS.findIndex(s => s.key === status.currentStep)
      if (idx >= 0) currentStepIndex.value = idx
    }

    if (status.status === 'SUCCESS') {
      clearInterval(pollTimer.value)
      pipelineResult.value = { steps: status.steps, overallSuccess: true, overallMessage: status.overallMessage }
      ElMessage.success(`🎉 流水线完成！素材包 #${status.packageId}`)
      running.value = false
      await load()
    } else if (status.status === 'FAILED') {
      clearInterval(pollTimer.value)
      pipelineResult.value = { steps: status.steps, overallSuccess: false, overallMessage: status.overallMessage }
      ElMessage.warning('流水线未完成：' + status.overallMessage)
      running.value = false
      await load()
    }
  } catch (e) {
    // 轮询出错不中断（可能是瞬时网络问题）
    console.warn('轮询状态失败:', e.message)
  }
}

onMounted(load)
</script>

<style scoped>
.batch-detail-page {
  max-width: 900px;
  margin: 0 auto;
}

.back-bar {
  margin-bottom: 8px;
}

.info-card, .action-card, .packages-card {
  margin-bottom: 16px;
  border-radius: 12px;
}

.batch-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.batch-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: #1d2129;
}

.batch-theme {
  margin: 4px 0 0 0;
  font-size: 15px;
  color: #86909c;
}

.link {
  color: var(--brand-primary);
  cursor: pointer;
  text-decoration: underline;
}

.muted {
  color: #c0c4cc;
}

.action-section {
  padding: 4px 0;
}

.action-title {
  margin: 0 0 4px 0;
  font-size: 16px;
  color: #1d2129;
}

.action-hint {
  margin: 0 0 12px 0;
  font-size: 13px;
  color: #909399;
}

.upload-area {
  width: 100%;
}

.upload-icon {
  font-size: 32px;
  color: #c0c4cc;
}

.upload-text {
  font-size: 14px;
  color: #86909c;
  margin-top: 4px;
}

/* 缩略图预览行 */
.preview-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.thumb-item {
  position: relative;
  width: 80px;
  height: 80px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #ebeef5;
}

.thumb-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-remove {
  position: absolute;
  top: -8px;
  right: -8px;
  width: 20px !important;
  height: 20px !important;
  min-height: 20px !important;
  padding: 0 !important;
}

.upload-btn {
  margin-left: auto;
}

/* 已投喂参考图网格 */
.ref-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 10px;
  margin-top: 8px;
}

.ref-item {
  aspect-ratio: 1;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #ebeef5;
}

.ref-thumb {
  width: 100%;
  height: 100%;
  cursor: pointer;
}

.ref-error, .ref-loading {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #c0c4cc;
  font-size: 12px;
  background: #f5f7fa;
}

/* pipeline 步骤动画 */
.pipeline-steps {
  margin-top: 20px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.step-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-radius: 8px;
  background: #f5f7fa;
  transition: all 0.3s ease;
}

.step-item.done {
  background: #f0f9eb;
  color: #67c23a;
}

.step-item.running {
  background: #ecf5ff;
  color: var(--brand-primary);
  box-shadow: 0 0 0 2px var(--brand-primary-light);
}

.step-item.failed {
  background: #fef0f0;
  color: #f56c6c;
}

.step-icon {
  font-size: 18px;
}

.step-name {
  font-size: 14px;
  font-weight: 500;
}

.step-detail {
  font-size: 11px;
  opacity: 0.8;
}

/* 素材包列表 */
.package-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.package-item {
  cursor: pointer;
  border-radius: 10px;
  transition: transform 0.2s ease;
}

.package-item:hover {
  transform: translateY(-2px);
}

.pkg-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.pkg-title {
  font-weight: 600;
  color: #1d2129;
}

.pkg-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 8px;
}

.pkg-meta {
  font-size: 12px;
  color: #909399;
}
</style>
