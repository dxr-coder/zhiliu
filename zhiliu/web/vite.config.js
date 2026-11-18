import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vite'

// Python AI/旧业务：默认 8000；视觉审计可用 API_PROXY=http://127.0.0.1:8001
const apiTarget = process.env.API_PROXY || 'http://127.0.0.1:8000'
// Java 业务主干（B1-B6）
const javaTarget = process.env.JAVA_PROXY || 'http://127.0.0.1:8081'

const proxyTo = (target) => ({
  target,
  changeOrigin: true,
  bypass(req) {
    if (req.headers.accept?.includes('text/html')) {
      return '/index.html'
    }
  },
})

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      // ── Java 业务（Spring Boot 8081）──
      '/api': proxyTo(javaTarget),
      // ── Python AI / 旧接口（FastAPI 8000）──
      '/ai': proxyTo(apiTarget),
      '/auth': proxyTo(apiTarget),
      '/users': proxyTo(apiTarget),
      '/documents': proxyTo(apiTarget),
      '/stats': proxyTo(apiTarget),
      '/tickets': proxyTo(apiTarget),
      '/transfer': proxyTo(apiTarget),
      '/chat/stream': proxyTo(apiTarget),
      '/conversations': proxyTo(apiTarget),
      '/feedback': proxyTo(apiTarget),
      '/audit': proxyTo(apiTarget),
      '/ab-test': proxyTo(apiTarget),
      '/csat': proxyTo(apiTarget),
      '/eval': proxyTo(apiTarget),
      '/pdf': proxyTo(apiTarget),
      '/assets': proxyTo(apiTarget),
    },
  },
})
