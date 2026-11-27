import { defineConfig, devices } from '@playwright/test'

/**
 * CMP-261118-0900 大会連絡スペース参加者向け導線 実機E2E 専用設定（BE 8081 / FE 3001）。
 * 利用者・組織・チームを自前で作って画面ごとにログインし、前提データの後始末を globalTeardown で行うため、
 * playwright-cmp0726.config.ts と同じ流儀で本陣用 playwright-real.config.ts から分ける。
 * 前提データを残して再実行したいときだけ N1P1_KEEP_WORLD=1。
 */
process.env.API_BASE_URL ||= 'http://localhost:8081'
process.env.BASE_URL ||= 'http://localhost:3001'

export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: ['cmp-261118-0900-participant-contact.real.spec.ts'],
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  globalTeardown: './tests/e2e/real/helpers/cmp0900-global-teardown.ts',
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
