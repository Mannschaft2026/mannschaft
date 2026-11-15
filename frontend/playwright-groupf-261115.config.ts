import { defineConfig, devices } from '@playwright/test'
import { F0121_CONFIG_MARK, F0121_DEFAULT_API } from './tests/e2e/real/helpers/f0121-endpoints'

/**
 * P1 群F（CMP-261002-1604 / CMP-261101-2008 / CMP-261006-0901）画面層の実機E2E・探索 専用設定（2026-11-15）。
 *
 * f0121 の共通土台（利用者・組織・チームの作成と globalTeardown による後始末）を流用する。
 * 検証用 BE 8081 / 検証用 FE 3017。本陣 8080/3000 は使わない。
 *
 * 実行例: BASE_URL=http://localhost:3017 API_BASE_URL=http://localhost:8081 \
 *   node node_modules/playwright/cli.js test -c playwright-groupf-261115.config.ts
 */
if (!process.env.API_BASE_URL) process.env.API_BASE_URL = F0121_DEFAULT_API
if (!process.env.BASE_URL) process.env.BASE_URL = 'http://localhost:3017'
process.env[F0121_CONFIG_MARK] = '1'

export default defineConfig({
  testDir: './tests/e2e/real',
  testMatch: /groupf-screen-261115\.real\.spec\.ts$/,
  globalTeardown: './tests/e2e/real/helpers/f0121-global-teardown.ts',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  timeout: 900_000,
  expect: { timeout: 15_000 },
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.BASE_URL,
    trace: 'off',
    screenshot: 'off',
    actionTimeout: 30_000,
    navigationTimeout: 120_000,
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] },
  },
})
