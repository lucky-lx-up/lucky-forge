import { createRouter, createWebHistory } from 'vue-router'

// 路由懒加载：每个页面独立 chunk，减小首屏 bundle
const BatchList = () => import('../views/BatchList.vue')
const BatchDetail = () => import('../views/BatchDetail.vue')
const PackageDetail = () => import('../views/PackageDetail.vue')

const routes = [
  { path: '/', redirect: '/batches' },
  { path: '/batches', name: 'BatchList', component: BatchList },
  { path: '/batches/:id', name: 'BatchDetail', component: BatchDetail },
  { path: '/packages/:id', name: 'PackageDetail', component: PackageDetail }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
