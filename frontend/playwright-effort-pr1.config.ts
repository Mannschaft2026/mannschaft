import { defineConfig, devices } from '@playwright/test'

/**
 * F06.4.1 PR-1（個人の頑張り記録）実機E2E 専用設定。
 * 検証用 BE 8081 / 検証用 FE 3001 を既定にする（本陣 8080/3000 は使わない）。
 * ログイン・前提データ作成・後始末は spec 内で行うため setup プロジェクトは持たない。
 */
process.env.API_BASE_URL ||= 'http://localhost:8081'
process.env.BASE_URL ||= 'http://localhost:3001'

export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: 'effort-records-pr1.spec.ts',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.BASE_URL,
    screenshot: 'only-on-failure',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] },
  },
  timeout: 300_000,
  expect: { timeout: 15_000 },
})
