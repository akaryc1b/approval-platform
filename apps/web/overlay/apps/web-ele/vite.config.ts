import { defineConfig } from '@vben/vite-config';

import ElementPlus from 'unplugin-element-plus/vite';

function isPrivateIpv4(hostname: string) {
  const octets = hostname.split('.').map(value => Number.parseInt(value, 10));
  if (octets.length !== 4 || octets.some(value => !Number.isInteger(value))) return false;
  if (octets.some(value => value < 0 || value > 255)) return false;
  return octets[0] === 10
    || octets[0] === 127
    || (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31)
    || (octets[0] === 192 && octets[1] === 168);
}

function localDemoTarget() {
  const configured = process.env.VITE_APPROVAL_DEV_PROXY_TARGET?.trim();
  if (!configured) return undefined;
  const target = new URL(configured);
  const hostname = target.hostname.toLowerCase();
  const localHost = hostname === 'localhost'
    || hostname === '::1'
    || isPrivateIpv4(hostname);
  if (target.protocol !== 'http:' || !localHost || target.username || target.password) {
    throw new Error('VITE_APPROVAL_DEV_PROXY_TARGET must be a local HTTP origin');
  }
  if (target.pathname !== '/' || target.search || target.hash) {
    throw new Error('VITE_APPROVAL_DEV_PROXY_TARGET must not contain a path, query or hash');
  }
  return target.origin;
}

export default defineConfig(async () => {
  const approvalTarget = localDemoTarget();
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
          ...(approvalTarget
            ? {
                '/approval-api': {
                  changeOrigin: true,
                  rewrite: (path: string) => path.replace(/^\/approval-api/, '/api'),
                  target: approvalTarget,
                  ws: false,
                },
              }
            : {}),
          '/api': {
            changeOrigin: true,
            rewrite: (path: string) => path.replace(/^\/api/, ''),
            target: 'http://localhost:5320/api',
            ws: true,
          },
        },
      },
    },
  };
});
