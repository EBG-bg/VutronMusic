import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { mobileBuild } from './build-config'

export default defineConfig({
  plugins: [vue(), {
    name: 'vutron-build-identity',
    transformIndexHtml(html) {
      const name = mobileBuild.displayName.replace(/[&<>\"]/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[char]!))
      return html.replace('<title>Vutron Music - Mobile</title>', '<title>' + name + '</title>')
    },
    generateBundle() { this.emitFile({ type: 'asset', fileName: 'build-info.json', source: JSON.stringify(mobileBuild, null, 2) }) }
  }],
  define: { __VUTRON_BUILD__: JSON.stringify(mobileBuild) },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      '@vutronmusic/core': fileURLToPath(new URL('../../packages/core/src', import.meta.url))
    }
  }
})
