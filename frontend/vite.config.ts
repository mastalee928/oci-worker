import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { AntDesignVueResolver } from 'unplugin-vue-components/resolvers'
import AutoImport from 'unplugin-auto-import/vite'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      imports: ['vue', 'vue-router', 'pinia'],
      dts: 'src/auto-imports.d.ts',
    }),
    Components({
      resolvers: [
        AntDesignVueResolver({
          importStyle: false,
          resolveIcons: true,
        }),
      ],
      dts: 'src/components.d.ts',
    }),
  ],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8818',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://localhost:8818',
        ws: true,
      },
    },
  },
  build: {
    outDir: '../backend/src/main/resources/dist',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('ant-design-vue') || id.includes('@ant-design/icons-vue')) {
            return 'antd'
          }
          if (id.includes('vue-router')) return 'vue-router'
          if (id.includes('pinia')) return 'pinia'
          if (id.includes('/vue/') || id.includes('@vue/')) return 'vue'
          if (id.includes('dayjs')) return 'dayjs'
          if (id.includes('axios')) return 'axios'
          if (id.includes('@tanstack/vue-virtual')) return 'virtual'
        },
      },
    },
  },
})
