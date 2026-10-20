import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 开发期把 /api 代理到后端；生产由反向代理处理，前端只认相对路径。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8010', changeOrigin: true },
      '/actuator': { target: 'http://localhost:8010', changeOrigin: true },
    },
  },
  build: { outDir: 'dist', sourcemap: true },
})
