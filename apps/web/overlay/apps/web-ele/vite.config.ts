import process from 'node:process';

import { defineConfig } from '@vben/vite-config';

import ElementPlus from 'unplugin-element-plus/vite';

const approvalBackendTarget = process.env.APPROVAL_DEMO_BACKEND_URL?.trim()
  || 'http://127.0.0.1:8080';

export default defineConfig(async () => {
  return {
    application: {},
    vite: {
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
