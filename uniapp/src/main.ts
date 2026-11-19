import { createSSRApp } from 'vue'
import App from './App.vue'

// uni-app 三端统一入口（H5 / 微信小程序 / APP）
export function createApp() {
  const app = createSSRApp(App)
  return { app }
}
