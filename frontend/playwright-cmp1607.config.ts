import { defineConfig, devices } from '@playwright/test'

/**
 * CMP-260910-1607 実機E2E 専用設定。検証用 BE 8081 / 検証用 FE 3001 を既定にする。
 * Chromium の実行ファイルは CHROMIUM_PATH で上書きできる（未指定なら Playwright 同梱のものを使う）。
 */
process.env.API_BASE_URL ||= 'http://localhost:8081'
process.env.BASE_URL ||= 'http://localhost:3001'

export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: "shift-error-handling-1607.spec.ts",
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.BASE_URL,
    trace: 'off',
    screenshot: 'off',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    launchOptions: {
      executablePath: process.env.CHROMIUM_PATH || undefined,
      args: ['--host-resolver-rules=MAP localhost 127.0.0.1', '--no-sandbox'],
    },
  },
  timeout: 300_000,
  expect: { timeout: 20_000 },
})
