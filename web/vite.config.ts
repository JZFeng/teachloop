import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 只有一个 env 类型声明，避免为了读环境变量引入 @types/node。
declare const process: { env: Record<string, string | undefined> }

// 开发期把 /api 代理到后端；生产由反向代理处理，前端只认相对路径。
//
// 第二个环境（`scripts/env.sh` 起的测试台）要换后端端口，所以代理目标与端口都从环境变量读：
//   TEACHLOOP_API=http://127.0.0.1:8011 WEB_PORT=5174 pnpm dev
const apiTarget = process.env.TEACHLOOP_API ?? 'http://localhost:8010'
const webPort = Number(process.env.WEB_PORT ?? 5173)

const proxy = {
  '/api': { target: apiTarget, changeOrigin: true },
  '/actuator': { target: apiTarget, changeOrigin: true },
}

export default defineConfig({
  plugins: [react()],
  server: { port: webPort, proxy },
  // 测试台用 `vite preview` 服务构建好的 dist：它不监听源码，所以开发时改代码不会漏进测试台。
  preview: { port: webPort, proxy },
  build: { outDir: 'dist', sourcemap: true },
})
