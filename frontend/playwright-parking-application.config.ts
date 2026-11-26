import { defineConfig, devices } from '@playwright/test'
import { F0121_CONFIG_MARK } from './tests/e2e/real/helpers/f0121-endpoints'

/**
 * CMP-260930-0233 駐車場申請 実機E2E 専用設定。F0121 の土台（利用者作成・後始末）を共用する。
 * 実行例: BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081 \
 *   node node_modules/playwright/cli.js test -c playwright-parking-application.config.ts
 */
if (!process.env.API_BASE_URL) process.env.API_BASE_URL = 'http://localhost:8081'
if (!process.env.BASE_URL) process.env.BASE_URL = 'http://localhost:3001'
process.env[F0121_CONFIG_MARK] = '1'

export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: /parking-application\.spec\.ts$/,
  globalTeardown: './tests/e2e/real/helpers/f0121-global-teardown.ts',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  timeout: 300_000,
  expect: { timeout: 8_000 },
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.BASE_URL,
    trace: 'off',
    screenshot: 'off',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    launchOptions: {
      args: ['--host-resolver-rules=MAP localhost 127.0.0.1'],
      ...(process.env.PW_CHROMIUM_PATH ? { executablePath: process.env.PW_CHROMIUM_PATH } : {}),
    },
  },
})
