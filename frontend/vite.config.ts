import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// In dev the SPA and the API share one origin through this proxy, so the SameSite=Strict refresh cookie works
// exactly as it does behind Nginx. Point it at the compose backend with VITE_API_PROXY=http://localhost:8090.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5174,
    proxy: {
      '/api': process.env.VITE_API_PROXY ?? 'http://localhost:8080',
      '/actuator/health': process.env.VITE_API_PROXY ?? 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})
