<template>
  <el-config-provider :locale="zhCn">
  <el-container class="app-container">
    <el-header class="app-header">
      <div class="header-inner">
        <h1 class="logo">
          <span class="logo-icon">🎨</span>
          <span class="logo-text">LuckyForge</span>
          <span class="logo-sub">壁纸工厂</span>
        </h1>
        <el-menu mode="horizontal" :router="true" :default-active="$route.path" class="nav-menu">
          <el-menu-item index="/batches">批次管理</el-menu-item>
          <el-menu-item index="/prompts">提示词库</el-menu-item>
        </el-menu>
      </div>
    </el-header>
    <el-main class="app-main">
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </el-main>
    <el-footer class="app-footer">
      LuckyForge · 个人技术研究项目 · AI 驱动的手机壁纸生成流水线
    </el-footer>
  </el-container>
  </el-config-provider>
</template>

<script setup>
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
</script>

<style>
:root {
  --brand-primary: #6366f1;
  --brand-primary-light: #818cf8;
  --brand-gradient: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

body {
  margin: 0;
  background: #f0f2f5;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
}

.app-container {
  min-height: 100vh;
}

.app-header {
  background: var(--brand-gradient);
  padding: 0 !important;
  height: 64px !important;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  position: sticky;
  top: 0;
  z-index: 100;
}

.header-inner {
  max-width: 1400px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  height: 100%;
  padding: 0 24px;
}

.logo {
  margin: 0 32px 0 0;
  display: flex;
  align-items: center;
  gap: 8px;
  color: #fff;
}

.logo-icon {
  font-size: 24px;
}

.logo-text {
  font-size: 20px;
  font-weight: 700;
}

.logo-sub {
  font-size: 13px;
  opacity: 0.8;
  font-weight: 400;
}

.nav-menu {
  background: transparent !important;
  border-bottom: none !important;
  flex: 1;
}

.nav-menu .el-menu-item {
  color: rgba(255, 255, 255, 0.85) !important;
  border-bottom-color: transparent !important;
  font-size: 15px;
}

.nav-menu .el-menu-item.is-active,
.nav-menu .el-menu-item:hover {
  color: #fff !important;
  background: rgba(255, 255, 255, 0.15) !important;
  border-bottom-color: #fff !important;
}

.app-main {
  max-width: 1400px;
  margin: 0 auto;
  width: 100%;
  padding: 24px !important;
}

.app-footer {
  text-align: center;
  color: #909399;
  font-size: 13px;
  padding: 16px !important;
  background: #fff;
  border-top: 1px solid #ebeef5;
}

/* 路由切换动画 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* 全局：Element Plus 主色对齐品牌色 */
.el-button--primary {
  --el-button-bg-color: var(--brand-primary);
  --el-button-border-color: var(--brand-primary);
  --el-button-hover-bg-color: var(--brand-primary-light);
  --el-button-hover-border-color: var(--brand-primary-light);
}
</style>
