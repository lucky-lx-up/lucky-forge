import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000 // 普通请求 30 秒
})

// 统一响应处理：ApiResponse { result, message, data }
api.interceptors.response.use(
  resp => {
    const body = resp.data
    if (body.result === 'SUCCESS') {
      return body.data
    }
    // ERROR：业务异常
    return Promise.reject(new Error(body.message || '操作失败'))
  },
  err => {
    const msg = err.response?.data?.message || err.message || '网络错误'
    return Promise.reject(new Error(msg))
  }
)

// ===== 批次 =====
export const listBatches = (page = 1, size = 10) =>
  api.get('/batches', { params: { page, size } })

export const getBatchDetail = (id) =>
  api.get(`/batches/${id}`)

export const createBatch = (data) =>
  api.post('/batches', data)

export const deleteBatch = (id) =>
  api.delete(`/batches/${id}`)

export const deleteBatches = (ids) =>
  api.delete('/batches', { data: ids })

export const uploadReferenceImages = (batchId, files) => {
  const form = new FormData()
  files.forEach(f => form.append('files', f))
  return api.post(`/batches/${batchId}/reference-images`, form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export const runPipeline = (batchId, count) =>
  api.post(`/batches/${batchId}/pipeline`, count != null ? { count } : {})

export const getPipelineStatus = (batchId) =>
  api.get(`/batches/${batchId}/pipeline/status`)

export const listReferenceImages = (batchId) =>
  api.get(`/batches/${batchId}/reference-images`)

export const deleteReferenceImage = (batchId, id) =>
  api.delete(`/batches/${batchId}/reference-images/${id}`)

// ===== 素材包 =====
export const listPackagesByBatch = (batchId) =>
  api.get(`/batches/${batchId}/packages`)

export const getPackageDetail = (id) =>
  api.get(`/packages/${id}`)

// ===== 提示词库 =====
export const listPromptLibrary = (params) =>
  api.get('/prompt-library', { params })

export const getPromptLibraryItem = (id) =>
  api.get(`/prompt-library/${id}`)

export const listPromptLibraryStyles = () =>
  api.get('/prompt-library/styles')

export const createPromptLibraryItem = (data) =>
  api.post('/prompt-library', data)

export const updatePromptLibraryItem = (id, data) =>
  api.put(`/prompt-library/${id}`, data)

export const deletePromptLibraryItem = (id) =>
  api.delete(`/prompt-library/${id}`)

export const generateFromLibrary = (data) =>
  api.post('/prompt-library/generate', data)

export const archiveFromRun = (data) =>
  api.post('/prompt-library/archive', data)

export const archivePrompts = (promptIds) =>
  api.post('/prompt-library/archive-prompts', { promptIds })

export const getLibraryRunDetail = (runId) =>
  api.get(`/prompt-library/runs/${runId}`)

export const listLibraryRuns = (params) =>
  api.get('/prompt-library/runs', { params })

export const deleteLibraryRun = (runId) =>
  api.delete(`/prompt-library/runs/${runId}`)

export const deleteLibraryRuns = (runIds) =>
  api.delete('/prompt-library/runs', { data: runIds })

// ===== 图片预览 =====
export const getPreviewUrl = (objectKey) =>
  api.get('/images/preview-url', { params: { objectKey } })

export default api
