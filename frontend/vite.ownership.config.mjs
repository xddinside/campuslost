import { defineConfig, mergeConfig } from 'vite'
import base from './vite.config.ts'

export default mergeConfig(base, defineConfig({
  server: {
    port: 3003,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8083',
        changeOrigin: true,
      },
    },
  },
}))
