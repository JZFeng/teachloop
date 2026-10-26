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
  // 用临时隧道（cloudflared quick tunnel 等）从外部域名访问 dev server 时，
  // Vite 的 Host 校验会回 403 Blocked request；这里只放行隧道域名后缀。
  server: { port: webPort, proxy, allowedHosts: ['.trycloudflare.com'] },
  // 测试台用 `vite preview` 服务构建好的 dist：它不监听源码，所以开发时改代码不会漏进测试台。
  preview: { port: webPort, proxy },
  build: {
    outDir: 'dist',
    sourcemap: true,
    // antd 与图标是体积大头，单独成 chunk：业务代码改动不会让用户重下整个包。
    rolldownOptions: {
      output: {
        advancedChunks: {
          groups: [
            { name: 'antd', test: /[\\/]node_modules[\\/](antd|@ant-design|rc-[^\\/]+|@rc-component)[\\/]/ },
            { name: 'react', test: /[\\/]node_modules[\\/](react|react-dom|react-router|react-router-dom|scheduler)[\\/]/ },
          ],
        },
      },
    },
  },
})
