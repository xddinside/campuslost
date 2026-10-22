import { defineConfig } from 'vite'
import { tanstackStart } from '@tanstack/react-start/plugin/vite'
import viteReact from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Local-only config for the matching worktree: 3001 -> 8081.
export default defineConfig({
  resolve: { tsconfigPaths: true },
  server: {
    port: 3001,
    strictPort: true,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8081', changeOrigin: true },
    },
  },
  plugins: [tailwindcss(), tanstackStart(), viteReact()],
})
