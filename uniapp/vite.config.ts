import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// uni-app 的 H5 构建与本地开发都走这里；小程序/APP 由同一份代码编译到不同目标
export default defineConfig({
  plugins: [uni()],
  server: {
    port: 5175,
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://127.0.0.1:8080',
        changeOrigin: true,
        secure: false
      }
    }
  }
})
