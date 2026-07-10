<template>
  <div class="prompt-detail-page">
    <div class="back-bar">
      <el-button text @click="$router.push('/prompts')">← 返回提示词库</el-button>
    </div>

    <div v-loading="loading">
      <el-card class="detail-card" v-if="item.id">
        <!-- 头部 -->
        <div class="detail-header">
          <div class="header-left">
            <el-tag size="small" type="info" effect="plain">{{ item.vertical }}</el-tag>
            <span class="item-id">#{{ item.id }}</span>
            <span class="usage-count" title="累计出图次数">🔁 已出图 {{ item.usageCount || 0 }} 次</span>
            <el-tag v-if="item.sourcePromptId" size="small" type="success" effect="plain" round>来自归档</el-tag>
          </div>
          <div class="header-right">
            <el-button text :icon="Edit" @click="openEditDialog">编辑备注</el-button>
            <el-button text type="danger" :icon="Delete" @click="confirmDelete">删除</el-button>
          </div>
        </div>

        <!-- 风格 -->
        <div class="meta-block" v-if="item.styleName">
          <span class="meta-label">所属风格</span>
          <el-tag type="primary" effect="light" round>
            <el-icon><MagicStick /></el-icon>
            {{ item.styleName }}
          </el-tag>
        </div>

        <!-- 提示词正文（核心） -->
        <div class="meta-block">
          <span class="meta-label">提示词</span>
          <div class="prompt-text">{{ item.content }}</div>
          <div class="prompt-actions">
            <el-button size="small" text :icon="CopyDocument" @click="copyContent">复制</el-button>
            <span class="char-count">{{ item.content.length }} 字符</span>
          </div>
        </div>

        <!-- 标签 -->
        <div class="meta-block" v-if="item.tags?.length">
          <span class="meta-label">标签</span>
          <div class="tags-area">
            <el-tag v-for="t in item.tags" :key="t" round effect="plain" class="tag-chip">{{ t }}</el-tag>
          </div>
        </div>

        <!-- 备注 -->
        <div class="meta-block" v-if="item.note">
          <span class="meta-label">备注</span>
          <div class="note-box">
            <el-icon><ChatLineRound /></el-icon>
            <span>{{ item.note }}</span>
          </div>
        </div>

        <!-- 创建时间 -->
        <div class="meta-block meta-footer">
          <span class="meta-label">录入时间</span>
          <span class="meta-value">{{ formatTime(item.createdAt) }}</span>
        </div>
      </el-card>

      <!-- 出图操作区 -->
      <el-card class="action-card" v-if="item.id" shadow="never">
        <div class="action-row">
          <div class="action-info">
            <div class="action-title">用这条提示词出图</div>
            <div class="action-hint">将直接送 gpt-image-2 生成，跳过风格分析和提示词生成步骤，自动出图并打分</div>
          </div>
          <el-button
            type="primary"
            size="large"
            :loading="generating"
            @click="confirmGenerate"
          >
            <el-icon><MagicStick /></el-icon>
            <span>开始出图</span>
          </el-button>
        </div>
      </el-card>
    </div>

    <!-- 编辑备注/标签对话框 -->
    <el-dialog v-model="showEdit" title="编辑备注与标签" width="480px">
      <el-form label-width="64px">
        <el-form-item label="备注">
          <el-input
            v-model="editForm.note"
            type="textarea"
            :rows="3"
            placeholder="如：适合夜景（可选）"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="标签">
          <div class="tags-input">
            <el-tag
              v-for="(t, i) in editForm.tags"
              :key="i"
              closable
              @close="editForm.tags.splice(i, 1)"
              class="tag-chip"
            >{{ t }}</el-tag>
            <el-input
              v-if="editTagInputVisible"
              v-model="editTagValue"
              ref="editTagInputRef"
              size="small"
              style="width: 120px"
              @keyup.enter="addEditTag"
              @blur="addEditTag"
            />
            <el-button v-else size="small" @click="showEditTagInput">+ 标签</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showEdit = false">取消</el-button>
        <el-button type="primary" @click="doEdit" :loading="editing">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  MagicStick, ChatLineRound, Edit, Delete, CopyDocument
} from '@element-plus/icons-vue'
import {
  getPromptLibraryItem,
  updatePromptLibraryItem,
  deletePromptLibraryItem,
  generateFromLibrary
} from '../api'

const route = useRoute()
const router = useRouter()
const itemId = route.params.id

const item = ref({})
const loading = ref(false)
const generating = ref(false)

const formatTime = (t) => {
  if (!t) return ''
  return t.replace('T', ' ').slice(0, 19)
}

const load = async () => {
  loading.value = true
  try {
    item.value = await getPromptLibraryItem(itemId)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

const copyContent = async () => {
  try {
    await navigator.clipboard.writeText(item.value.content)
    ElMessage.success('已复制到剪贴板')
  } catch (e) {
    ElMessage.warning('复制失败，请手动选择文本复制')
  }
}

// 出图
const confirmGenerate = async () => {
  try {
    await ElMessageBox.confirm(
      '确定用这条提示词出图？将自动生成并打分，完成后可在结果页查看。',
      '出图确认',
      { type: 'info', confirmButtonText: '开始出图', cancelButtonText: '取消' }
    )
    generating.value = true
    const result = await generateFromLibrary({ libraryItemIds: [Number(itemId)] })
    ElMessage.success('已触发出图，正在生成...')
    router.push(`/prompts/run/${result.runId}`)
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  } finally {
    generating.value = false
  }
}

// 删除
const confirmDelete = async () => {
  try {
    await ElMessageBox.confirm(
      '确定删除这条提示词？删除后不可恢复。',
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
    await deletePromptLibraryItem(itemId)
    ElMessage.success('已删除')
    router.push('/prompts')
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}

// ===== 编辑备注/标签 =====
const showEdit = ref(false)
const editing = ref(false)
const editForm = ref({ note: '', tags: [] })
const editTagInputVisible = ref(false)
const editTagValue = ref('')
const editTagInputRef = ref(null)

const openEditDialog = () => {
  editForm.value = {
    note: item.value.note || '',
    tags: item.value.tags ? [...item.value.tags] : []
  }
  showEdit.value = true
}

const showEditTagInput = () => {
  editTagInputVisible.value = true
  nextTick(() => editTagInputRef.value?.focus())
}

const addEditTag = () => {
  const v = editTagValue.value.trim()
  if (v && !editForm.value.tags.includes(v)) {
    editForm.value.tags.push(v)
  }
  editTagInputVisible.value = false
  editTagValue.value = ''
}

const doEdit = async () => {
  editing.value = true
  try {
    const updated = await updatePromptLibraryItem(itemId, {
      note: editForm.value.note.trim() || null,
      tags: editForm.value.tags.length > 0 ? editForm.value.tags : null
    })
    item.value = updated
    ElMessage.success('已保存')
    showEdit.value = false
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    editing.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.prompt-detail-page {
  max-width: 800px;
  margin: 0 auto;
}

.back-bar {
  margin-bottom: 8px;
}

.detail-card {
  margin-bottom: 16px;
  border-radius: 12px;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #f0f0f0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.item-id {
  font-size: 13px;
  color: #c0c4cc;
  font-weight: 600;
}

.usage-count {
  font-size: 13px;
  color: #86909c;
}

.header-right {
  display: flex;
  gap: 4px;
}

.meta-block {
  margin-bottom: 20px;
}

.meta-label {
  display: block;
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
  font-weight: 500;
}

.prompt-text {
  font-size: 15px;
  line-height: 1.7;
  color: #1d2129;
  padding: 14px 16px;
  background: #f5f7fa;
  border-radius: 8px;
  border-left: 3px solid var(--brand-primary);
  word-break: break-word;
  white-space: pre-wrap;
}

.prompt-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
}

.char-count {
  font-size: 12px;
  color: #c0c4cc;
}

.tags-area {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag-chip {
  margin: 0;
}

.note-box {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 10px 12px;
  background: #fdf6ec;
  border-radius: 8px;
  font-size: 13px;
  color: #b88230;
  line-height: 1.6;
}

.meta-footer {
  margin-bottom: 0;
}

.meta-value {
  font-size: 13px;
  color: #86909c;
}

/* 出图操作区 */
.action-card {
  border-radius: 12px;
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.04), rgba(118, 75, 162, 0.04));
}

.action-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

.action-info {
  flex: 1;
}

.action-title {
  font-size: 16px;
  font-weight: 600;
  color: #1d2129;
  margin-bottom: 4px;
}

.action-hint {
  font-size: 13px;
  color: #86909c;
  line-height: 1.5;
}

/* 编辑对话框标签输入 */
.tags-input {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
</style>
