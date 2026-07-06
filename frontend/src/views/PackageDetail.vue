<template>
  <div v-loading="loading" class="package-detail">
    <div class="back-bar">
      <el-button text @click="$router.back()">← 返回</el-button>
    </div>

    <!-- 头部信息卡 -->
    <el-card class="header-card" v-if="pkg.id">
      <div class="package-header">
        <div class="package-title-area">
          <h2 class="package-title">{{ pkg.title }}</h2>
          <div class="package-meta">
            <el-tag size="small" type="info">ID #{{ pkg.id }}</el-tag>
            <el-tag size="small" :type="pkg.status === 'PUBLISHED' ? 'success' : 'warning'">
              {{ pkg.status === 'PUBLISHED' ? '已发布' : '待发布' }}
            </el-tag>
            <span class="image-count">📷 {{ pkg.images?.length || 0 }} 张壁纸</span>
          </div>
        </div>
        <div class="tags-area" v-if="pkg.tags?.length">
          <el-tag
            v-for="t in pkg.tags"
            :key="t"
            class="tag-item"
            effect="plain"
            round
          >{{ t }}</el-tag>
        </div>
      </div>
    </el-card>

    <!-- 壁纸图片网格 -->
    <div class="section-title" v-if="pkg.images?.length">
      <span>壁纸预览</span>
      <span class="section-hint">点击图片查看大图</span>
    </div>

    <el-empty v-if="!loading && (!pkg.images || pkg.images.length === 0)" description="无图片" />

    <div v-else class="wallpaper-grid">
      <div
        v-for="img in pkg.images"
        :key="img.generatedImageId"
        class="wallpaper-item"
      >
        <!-- 封面角标 -->
        <div v-if="img.sortOrder === 0" class="cover-badge">封面</div>
        <!-- 打分徽章 -->
        <div v-if="img.score" class="score-badge" :class="scoreClass(img.score)">
          ⭐ {{ Number(img.score).toFixed(1) }}
        </div>

        <el-image
          :src="img.previewUrl"
          fit="cover"
          class="wallpaper-img"
          loading="lazy"
          @click="openViewer(pkg.images.indexOf(img))"
        >
          <template #error>
            <div class="img-placeholder">
              <el-icon :size="32"><Picture /></el-icon>
              <span>加载失败</span>
            </div>
          </template>
          <template #placeholder>
            <div class="img-placeholder skeleton">
              <el-skeleton-item variant="image" style="width: 100%; height: 100%" />
            </div>
          </template>
        </el-image>

        <div class="wallpaper-footer">
          <span class="sort-order">排序 #{{ img.sortOrder }}</span>
          <!-- 维度分明细 -->
          <div v-if="img.dimensions?.length" class="dim-scores">
            <div v-for="d in img.dimensions" :key="d.name" class="dim-bar">
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
          <!-- 评语 -->
          <div v-if="img.remark" class="remark">{{ img.remark }}</div>
        </div>
      </div>
    </div>

    <!-- 独立的全局图片预览 viewer（避免多实例冲突导致闪烁） -->
    <el-image-viewer
      v-if="viewerVisible"
      :url-list="allPreviewUrls"
      :initial-index="viewerIndex"
      hide-on-click-modal
      @close="viewerVisible = false"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Picture } from '@element-plus/icons-vue'
import { getPackageDetail } from '../api'

const route = useRoute()
const packageId = route.params.id

const pkg = ref({})
const loading = ref(false)

const allPreviewUrls = computed(() =>
  (pkg.value.images || []).map(i => i.previewUrl).filter(Boolean)
)

// 独立 viewer 控制（避免每个 el-image 各自触发预览导致多实例闪烁）
const viewerVisible = ref(false)
const viewerIndex = ref(0)
const openViewer = (index) => {
  viewerIndex.value = Math.max(0, Math.min(index, allPreviewUrls.value.length - 1))
  viewerVisible.value = true
}

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
  loading.value = true
  try {
    pkg.value = await getPackageDetail(packageId)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.package-detail {
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

.package-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}

.package-title {
  margin: 0 0 8px 0;
  font-size: 24px;
  font-weight: 700;
  color: #1d2129;
}

.package-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #86909c;
}

.image-count {
  margin-left: 4px;
}

.tags-area {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-width: 400px;
}

.tag-item {
  margin: 0;
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

/* 瀑布流网格 */
.wallpaper-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 20px;
}

.wallpaper-item {
  position: relative;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  background: #fff;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.wallpaper-item:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
}

.wallpaper-img {
  width: 100%;
  aspect-ratio: 9 / 16;
  display: block;
  background: #f5f5f5;
}

.img-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #c0c4cc;
  background: #f5f7fa;
}

.img-placeholder.skeleton {
  padding: 0;
}

/* 封面角标 */
.cover-badge {
  position: absolute;
  top: 12px;
  left: 12px;
  background: var(--brand-primary);
  color: #fff;
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 600;
  z-index: 2;
  box-shadow: 0 2px 8px rgba(99, 102, 241, 0.4);
}

/* 打分徽章 */
.score-badge {
  position: absolute;
  top: 12px;
  right: 12px;
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 700;
  z-index: 2;
  backdrop-filter: blur(4px);
}

.score-excellent {
  background: rgba(245, 158, 11, 0.9);
  color: #fff;
}

.score-good {
  background: rgba(34, 197, 94, 0.9);
  color: #fff;
}

.score-normal {
  background: rgba(100, 116, 139, 0.9);
  color: #fff;
}

.wallpaper-footer {
  padding: 10px 12px;
  font-size: 12px;
  color: #909399;
}

.sort-order {
  font-variant-numeric: tabular-nums;
  display: block;
  margin-bottom: 6px;
  text-align: center;
}

/* 维度分明细 */
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
  margin-top: 6px;
  padding: 6px 8px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 11px;
  color: #606266;
  line-height: 1.5;
}

/* 响应式 */
@media (max-width: 640px) {
  .wallpaper-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 12px;
  }
  .package-title {
    font-size: 20px;
  }
}
</style>
