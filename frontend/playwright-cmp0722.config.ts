import { defineConfig, devices } from '@playwright/test'

/**
 * CMP-261116-0722 全体検索 F04.6 実機E2E 専用設定（BE 8081 / FE 3001）。
 * 専用設定にする理由: playwright-real.config.ts は BE 8080 / FE 3000・既定ユーザーの storageState を前提とする。
 * この spec は利用者・組織・チーム・各種コンテンツを自前で作って画面ごとにログインし、前提データの後始末を
 * globalTeardown で行うため、cmp0726 と同じ流儀で分ける。前提データを残して再実行したいときだけ N1P1_KEEP_WORLD=1。
 */
process.env.API_BASE_URL ||= 'http://localhost:8081'
process.env.BASE_URL ||= 'http://localhost:3001'

export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: ['cmp-261116-0722-global-search.real.spec.ts'],
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  globalTeardown: './tests/e2e/real/helpers/cmp0722-global-teardown.ts',
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.BASE_URL,
    screenshot: 'only-on-failure',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] },
  },
  timeout: 600_000,
  expect: { timeout: 15_000 },
})
