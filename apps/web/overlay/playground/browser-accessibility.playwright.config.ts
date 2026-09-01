import { resolve } from 'node:path';

import { defineConfig } from '@playwright/test';

function requiredEnvironment(name: string) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

const executablePath = requiredEnvironment('APPROVAL_DEMO_CHROME_PATH');
const evidenceDirectory = requiredEnvironment(
  'APPROVAL_BROWSER_ACCESSIBILITY_EVIDENCE_DIR',
);
const timeout = Number.parseInt(
  process.env.APPROVAL_DEMO_PLAYWRIGHT_TIMEOUT_MS || '600000',
  10,
);

export default defineConfig({
  expect: { timeout: 20_000 },
  forbidOnly: true,
  fullyParallel: false,
  outputDir: resolve(evidenceDirectory, 'playwright'),
  projects: [
    {
      name: 'system-chromium',
      use: {
        browserName: 'chromium',
        launchOptions: {
          args: ['--disable-dev-shm-usage', '--no-sandbox'],
          executablePath,
        },
      },
    },
    {
      name: 'bundled-firefox',
      use: { browserName: 'firefox' },
    },
    {
      name: 'bundled-webkit',
      use: { browserName: 'webkit' },
    },
  ],
  reporter: [['list']],
  retries: 0,
  testDir: './__tests__/e2e',
  testMatch: 'product-readiness-browser-accessibility.spec.ts',
  timeout,
  use: {
    headless: true,
    locale: 'zh-CN',
    screenshot: 'only-on-failure',
    trace: 'on',
    video: 'off',
  },
  workers: 1,
});
