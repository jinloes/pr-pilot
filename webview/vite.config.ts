import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [
    react(),
    {
      // JCEF loads pages via file:// URLs. Chromium treats every file://
      // resource as null-origin, so `crossorigin` on <script>/<link> triggers
      // CORS mode, which fails (no CORS headers on the filesystem). Strip the
      // attribute so scripts and stylesheets load as plain same-origin requests.
      name: 'jcef-file-compat',
      transformIndexHtml(html: string): string {
        return html.replace(/ crossorigin/g, '')
      },
    },
  ],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  // Relative paths so assets resolve correctly when loaded from a file:// URL
  base: './',
})
