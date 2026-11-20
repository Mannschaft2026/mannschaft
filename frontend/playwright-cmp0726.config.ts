import { defineConfig, devices } from '@playwright/test'

/**
 * CMP-261116-0726 大会連絡スペース存在オラクル是正 実機E2E 専用設定（BE 8081 / FE 3001）。
 * 専用設定にする理由: 本陣用の playwright-real.config.ts は BE 8080 / FE 3000・既定ユーザーのログイン済み
 * storageState（setup プロジェクト）・60 秒 timeout を前提とする。この spec は利用者・組織・チームを自前で作って
 * 画面ごとにログインし、前提データの後始末を globalTeardown で行うため、n1p1・cmp1507 と同じ流儀で分ける。
 * 前提データを残して再実行したいときだけ N1P1_KEEP_WORLD=1。
 */
process.env.API_BASE_URL ||= 'http://localhost:8081'
process.env.BASE_URL ||= 'http://localhost:3001'

export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: ['cmp-261116-0726-tournament-contact-oracle.real.spec.ts'],
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  globalTeardown: './tests/e2e/real/helpers/cmp0726-global-teardown.ts',
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
