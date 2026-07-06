import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

// Element Plus 由 unplugin-vue-components 按需自动引入模板组件的 JS + CSS
// 但 JS API 调用的组件 / 内部依赖组件的 CSS 不会自动引入，需手动补充：
// - ElMessage / ElMessageBox / ElNotification：API 调用
// - ElImageViewer：el-image 的 preview-src-list 预览依赖
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/notification/style/css'
import 'element-plus/es/components/image-viewer/style/css'

// 中文语言包
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'

const app = createApp(App)
app.use(router)
app.mount('#app')
