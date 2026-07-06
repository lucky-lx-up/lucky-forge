<template>
  <div class="batch-list-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">生产批次</h2>
        <p class="page-desc">管理壁纸生产批次，投喂参考图后一键执行流水线</p>
      </div>
      <div class="header-actions">
        <el-checkbox
          v-if="batches.length > 0"
          :model-value="isAllSelected"
          :indeterminate="isIndeterminate"
          @change="toggleSelectAll"
        >全选</el-checkbox>
        <el-button
          v-if="selectedIds.size > 0"
          type="danger"
          @click="confirmBatchDelete"
        >
          <el-icon><Delete /></el-icon>
          <span>删除选中（{{ selectedIds.size }}）</span>
        </el-button>
        <el-button type="primary" size="large" @click="showCreate = true">
          <el-icon><Plus /></el-icon>
          <span>创建批次</span>
        </el-button>
      </div>
    </div>

    <!-- 批次卡片网格 -->
    <div v-loading="loading" class="batch-grid">
      <el-card
        v-for="b in batches"
        :key="b.id"
        class="batch-card"
        :class="{ selected: selectedIds.has(b.id) }"
        shadow="hover"
        @click="$router.push(`/batches/${b.id}`)"
      >
        <!-- 复选框融入 header 左侧，不与状态标签冲突 -->
        <div class="card-header">
          <el-checkbox
            :model-value="selectedIds.has(b.id)"
            @click.stop
            @change="toggleSelect(b.id)"
            class="header-checkbox"
          />
          <span class="batch-id">#{{ b.id }}</span>
          <el-tag :type="statusType(b.status)" size="small" effect="light">{{ statusLabel(b.status) }}</el-tag>
        </div>
        <h3 class="batch-theme">{{ b.theme || '（未设置主题）' }}</h3>
        <div class="batch-info">
          <span class="info-item">
            <el-icon><Picture /></el-icon>
            {{ b.vertical }}
          </span>
          <span class="info-item">
            <el-icon><Aim /></el-icon>
            目标 {{ b.targetCount }} 张
          </span>
        </div>
        <div class="batch-footer">
          <span v-if="b.styleId" class="style-tag">
            <el-icon><MagicStick /></el-icon>
            风格 #{{ b.styleId }}
          </span>
          <span v-else class="style-tag pending">未提炼风格</span>
          <div class="footer-right">
            <span class="created-time">{{ formatTime(b.createdAt) }}</span>
            <el-button
              class="delete-btn"
              type="danger"
              size="small"
              text
              :icon="Delete"
              @click.stop="confirmDelete(b)"
            />
          </div>
        </div>
      </el-card>
    </div>

    <el-empty v-if="!loading && batches.length === 0" description="暂无批次，点击右上角创建">
      <el-button type="primary" @click="showCreate = true">创建第一个批次</el-button>
    </el-empty>

    <el-pagination
      v-if="total > 10"
      v-model:current-page="page"
      :page-size="10"
      :total="total"
      layout="prev, pager, next, total"
      @current-change="loadBatches"
      class="pagination"
    />

    <!-- 创建对话框 -->
    <el-dialog v-model="showCreate" title="创建生产批次" width="480px">
      <el-form label-width="80px">
        <el-form-item label="主题">
          <el-input v-model="form.theme" placeholder="如：宁静的自然风景" />
        </el-form-item>
        <el-form-item label="目标数">
          <el-input-number v-model="form.targetCount" :min="1" :max="12" />
          <span class="form-hint">流水线将产出这么多张候选图</span>
        </el-form-item>
        <el-form-item label="垂类">
          <el-select v-model="form.vertical">
            <el-option label="壁纸 WALLPAPER" value="WALLPAPER" />
            <el-option label="头像 AVATAR" value="AVATAR" />
            <el-option label="海报 POSTER" value="POSTER" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" @click="doCreate" :loading="creating">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Picture, Aim, MagicStick, Delete } from '@element-plus/icons-vue'
import { listBatches, createBatch, deleteBatch, deleteBatches } from '../api'

const batches = ref([])
const loading = ref(false)
const page = ref(1)
const total = ref(0)
const selectedIds = ref(new Set())

const isAllSelected = computed(() =>
  batches.value.length > 0 && selectedIds.value.size === batches.value.length
)
const isIndeterminate = computed(() =>
  selectedIds.value.size > 0 && selectedIds.value.size < batches.value.length
)

const toggleSelectAll = (checked) => {
  selectedIds.value = checked ? new Set(batches.value.map(b => b.id)) : new Set()
}

const showCreate = ref(false)
const creating = ref(false)
const form = ref({ theme: '', targetCount: 4, vertical: 'WALLPAPER' })

const statusType = (s) => ({
  DRAFT: 'info', RUNNING: 'warning', DONE: 'success', FAILED: 'danger'
}[s] || 'info')

const statusLabel = (s) => ({
  DRAFT: '草稿', RUNNING: '生产中', DONE: '已完成', FAILED: '失败'
}[s] || s)

const formatTime = (t) => {
  if (!t) return ''
  return t.replace('T', ' ').slice(0, 16)
}

const loadBatches = async () => {
  loading.value = true
  try {
    const data = await listBatches(page.value, 10)
    batches.value = data.records
    total.value = data.total
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

const doCreate = async () => {
  creating.value = true
  try {
    await createBatch(form.value)
    ElMessage.success('批次已创建')
    showCreate.value = false
    form.value = { theme: '', targetCount: 4, vertical: 'WALLPAPER' }
    page.value = 1
    await loadBatches()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    creating.value = false
  }
}

const confirmDelete = async (batch) => {
  try {
    await ElMessageBox.confirm(
      `确定删除批次 #${batch.id}（${batch.theme || '无主题'}）？\n草稿将从列表移除，关联数据保留。`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
    await deleteBatch(batch.id)
    ElMessage.success('已删除')
    await loadBatches()
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}

const toggleSelect = (id) => {
  const next = new Set(selectedIds.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  selectedIds.value = next
}

const confirmBatchDelete = async () => {
  const ids = Array.from(selectedIds.value)
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${ids.length} 个批次？\n草稿将从列表移除，关联数据保留。`,
      '批量删除确认',
      {
        type: 'warning',
        confirmButtonText: '全部删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
    const count = await deleteBatches(ids)
    ElMessage.success(`已删除 ${count} 个批次`)
    selectedIds.value = new Set()
    await loadBatches()
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}

onMounted(loadBatches)
</script>

<style scoped>
.batch-list-page {
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
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

.batch-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}

.batch-card {
  cursor: pointer;
  border-radius: 12px;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
  position: relative;
}

.batch-card:hover {
  transform: translateY(-2px);
}

.batch-card.selected {
  box-shadow: 0 0 0 2px var(--brand-primary);
}

.header-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.card-header .el-tag {
  margin-left: auto;
}

.header-checkbox {
  margin-right: 0;
}

.batch-id {
  font-size: 13px;
  color: #c0c4cc;
  font-weight: 600;
}

.batch-theme {
  margin: 0 0 12px 0;
  font-size: 18px;
  font-weight: 600;
  color: #1d2129;
  line-height: 1.4;
}

.batch-info {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
  font-size: 13px;
  color: #86909c;
}

.info-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.batch-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 12px;
  border-top: 1px solid #f0f0f0;
  font-size: 12px;
}

.style-tag {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--brand-primary);
  font-weight: 500;
}

.style-tag.pending {
  color: #c0c4cc;
}

.created-time {
  color: #c0c4cc;
}

.footer-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.delete-btn {
  padding: 2px;
  color: #c0c4cc;
}

.delete-btn:hover {
  color: #f56c6c;
}

.form-hint {
  margin-left: 12px;
  font-size: 13px;
  color: #909399;
}

.pagination {
  margin-top: 24px;
  display: flex;
  justify-content: center;
}
</style>
