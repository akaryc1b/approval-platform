import process from 'node:process';

import { defineConfig } from '@vben/vite-config';

import ElementPlus from 'unplugin-element-plus/vite';

const approvalBackendTarget = process.env.APPROVAL_DEMO_BACKEND_URL?.trim()
  || 'http://127.0.0.1:8080';

export default defineConfig(async () => {
  return {
    application: {},
    vite: {
      // The post-transform Element Plus plugin adds these style imports after
      // Vite's raw SFC dependency scan. Declare the governed workbench graph
      // before the first browser request, keeping normal discovery enabled.
      optimizeDeps: {
        include: [
          'element-plus/es/components/alert/style/css',
          'element-plus/es/components/button/style/css',
          'element-plus/es/components/card/style/css',
          'element-plus/es/components/col/style/css',
          'element-plus/es/components/collapse/style/css',
          'element-plus/es/components/collapse-item/style/css',
          'element-plus/es/components/date-picker/style/css',
          'element-plus/es/components/descriptions/style/css',
          'element-plus/es/components/descriptions-item/style/css',
          'element-plus/es/components/drawer/style/css',
          'element-plus/es/components/empty/style/css',
          'element-plus/es/components/form/style/css',
          'element-plus/es/components/form-item/style/css',
          'element-plus/es/components/input/style/css',
          'element-plus/es/components/input-number/style/css',
          'element-plus/es/components/message/style/css',
          'element-plus/es/components/message-box/style/css',
          'element-plus/es/components/option/style/css',
          'element-plus/es/components/pagination/style/css',
          'element-plus/es/components/row/style/css',
          'element-plus/es/components/select/style/css',
          'element-plus/es/components/skeleton/style/css',
          'element-plus/es/components/switch/style/css',
          'element-plus/es/components/tab-pane/style/css',
          'element-plus/es/components/tabs/style/css',
          'element-plus/es/components/tag/style/css',
          'element-plus/es/components/timeline/style/css',
          'element-plus/es/components/timeline-item/style/css',
          'element-plus/es/components/upload/style/css',
        ],
      },
      plugins: [
        ElementPlus({
          format: 'esm',
        }),
      ],
      server: {
        proxy: {
          '/api': {
            changeOrigin: true,
            rewrite: path => path.replace(/^\/api/, ''),
            target: 'http://localhost:5320/api',
            ws: true,
          },
          '/approval-api': {
            changeOrigin: true,
            rewrite: path => path.replace(/^\/approval-api/, ''),
            target: approvalBackendTarget,
            ws: false,
          },
        },
      },
    },
  };
});
