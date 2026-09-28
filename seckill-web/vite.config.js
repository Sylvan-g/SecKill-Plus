import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 需求文档 5.1：seckill-web 端口 5173，dev 代理 /api -> gateway:8080
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})