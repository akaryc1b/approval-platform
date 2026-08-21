import { defineConfig } from '@playwright/test';

const executablePath = process.env.APPROVAL_DEMO_CHROME_PATH;
if (!executablePath) {
  throw new Error('APPROVAL_DEMO_CHROME_PATH is required');
}

const timeout = Number.parseInt(
  process.env.APPROVAL_DEMO_PLAYWRIGHT_TIMEOUT_MS || '300000',
  10,
);

export default defineConfig({
  expect: { timeout: 15_000 },
  forbidOnly: true,
  fullyParallel: false,
  outputDir: process.env.APPROVAL_DEMO_EVIDENCE_DIR,
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
  ],
  reporter: [['list']],
  retries: 0,
  testDir: './__tests__/e2e',
  timeout,
  use: {
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    video: 'off',
  },
  workers: 1,
});
