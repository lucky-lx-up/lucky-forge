import { createRouter, createWebHistory } from 'vue-router'

// 路由懒加载：每个页面独立 chunk，减小首屏 bundle
const BatchList = () => import('../views/BatchList.vue')
const BatchDetail = () => import('../views/BatchDetail.vue')
const PackageDetail = () => import('../views/PackageDetail.vue')
const PromptLibrary = () => import('../views/PromptLibrary.vue')
const PromptDetail = () => import('../views/PromptDetail.vue')
const LibraryRunDetail = () => import('../views/LibraryRunDetail.vue')

const routes = [
  { path: '/', redirect: '/batches' },
  { path: '/batches', name: 'BatchList', component: BatchList },
  { path: '/batches/:id', name: 'BatchDetail', component: BatchDetail },
  { path: '/packages/:id', name: 'PackageDetail', component: PackageDetail },
  { path: '/prompts', name: 'PromptLibrary', component: PromptLibrary },
  // :id 限定为纯数字，避免与 /prompts/run/:runId 冲突（无需依赖路由声明顺序）
  { path: '/prompts/run/:runId', name: 'LibraryRunDetail', component: LibraryRunDetail },
  { path: '/prompts/:id(\\d+)', name: 'PromptDetail', component: PromptDetail }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
