import { test as setup } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

/**
 * 実機テスト用: 運営アカウント（SYSTEM_ADMIN 専用）ログイン setup
 *
 * 戦役 Z-A PR2: 運営は専用アカウントを使い、チーム・組織・委員会・村に所属しない。
 * チーム ADMIN としての操作が要る spec は個人アカウント（e2e-admin）を使い、
 * SYSTEM_ADMIN の画面・API を叩く spec だけこのアカウントを使う。
 *
 * storageState を tests/e2e/.auth/real-operator.json に保存する。
 *
 * 事前条件:
 *   - backend/scripts/seed-e2e-data.js が実行済みであること（本陣の実機E2E でシード再投入が必要）
 *   - e2e-operator@test.mannschaft.local が DB に存在すること
 */
setup('実機: 運営アカウントログイン', async ({ page }) => {
  const email = process.env.TEST_OPERATOR_EMAIL ?? 'e2e-operator@test.mannschaft.local'
  const password = process.env.TEST_OPERATOR_PASSWORD ?? 'TestPass2026!'

  await loginViaApi(page, { email, password })
  await page.context().storageState({ path: 'tests/e2e/.auth/real-operator.json' })
})
