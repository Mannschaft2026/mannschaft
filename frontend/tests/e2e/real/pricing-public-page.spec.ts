/**
 * 公開価格ページ /pricing の実機E2E（モックなし・実 BE・実 FE・実ブラウザ）。
 *
 * 前提: BE(API_BASE_URL) / FE(BASE_URL) 起動済み、e2e-user@test.mannschaft.local が存在し、
 * 公開カタログ（GET /api/v1/public/billing/plans）に BASIC プランの有効な価格が登録されていること。
 *
 * ロール横断について: /pricing・/product・ランディングは公開ページで、権限による表示差が無い
 * 全ユーザー共通機能。そのため「ログインなし」「ログイン済み」の2視点で確認する
 * （権限なし／他テナントのロール差は存在しない）。ログインは API（loginViaApi）で済ませ、
 * 検証対象の操作（クリック・URL 直打ち・タブ切替）はすべて UI で行う。作成するデータは無い。
 */
import { test, expect, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
const SHOT_DIR = process.env.E2E_SHOT_DIR

test.use({ storageState: { cookies: [], origins: [] } })

async function shot(page: Page, name: string) {
  if (SHOT_DIR) await page.screenshot({ path: `${SHOT_DIR}/${name}.png`, fullPage: true })
}

/** /product の料金節の「プランを見る」を押す。 */
async function clickViewPlansOnProduct(page: Page) {
  await page.goto('/product')
  await waitForHydration(page)
  await page.getByRole('link', { name: /プランを見る/ }).click()
}

test.describe('PRICING-E2E: 公開価格ページ', () => {
  test('(a) 未ログイン: /product の「プランを見る」→ /pricing（/register へ行かない）', async ({ page }) => {
    await clickViewPlansOnProduct(page)
    await expect(page).toHaveURL(/\/pricing$/)
    await expect(page.getByRole('heading', { level: 1, name: '料金プラン' })).toBeVisible()
    await expect(page.getByTestId('pricing-plan-FREE')).toBeVisible()
    await expect(page.getByTestId('pricing-plan-BASIC')).toBeVisible()
    await shot(page, 'pricing-a-guest-from-product')
    expect(page.url()).not.toContain('/register')
  })

  // /product・/ は既存仕様で guest 専用ミドルウェア（ログイン済みは /dashboard へ戻す）のため、
  // ログイン済みの利用者は /product の CTA を経由できない。ログイン済みは /pricing の直打ちで確認する。
  test('(b) ログイン済み: /pricing を直打ちしても /dashboard 等へ飛ばされず表示される', async ({ page }) => {
    await loginViaApi(page, USER)
    const res = await page.goto('/pricing')
    expect(res?.status()).toBe(200)
    await waitForHydration(page)
    await expect(page).toHaveURL(/\/pricing$/)
    await expect(page.getByTestId('pricing-plan-BASIC')).toBeVisible()
    await expect(page.getByTestId('pricing-plan-BASIC')).toContainText(/[¥￥]\s?500/)
    // 認証後でも URL が /pricing のまま安定している
    await page.waitForTimeout(1500)
    await expect(page).toHaveURL(/\/pricing$/)
  })

  test('(c) 未ログインで /pricing を URL 直打ち: リダイレクトなしで表示', async ({ page }) => {
    const res = await page.goto('/pricing')
    expect(res?.status()).toBe(200)
    await expect(page).toHaveURL(/\/pricing$/)
    await expect(page.getByTestId('pricing-plan-FREE')).toBeVisible()
    // USER スコープの BASIC は確定額（通貨記号付き）を出す
    await expect(page.getByTestId('pricing-plan-BASIC')).toContainText(/[¥￥]\s?500/)
    await expect(page.getByTestId('pricing-plan-BASIC')).toContainText('税込')
  })

  test('(d) TEAM/ORG タブでは確定額を出さず見積り案内、USER に戻すと金額が出る', async ({ page }) => {
    await page.goto('/pricing')
    await waitForHydration(page)
    const basic = page.getByTestId('pricing-plan-BASIC')
    await expect(basic).toContainText(/[¥￥]\s?500/)

    for (const scope of ['TEAM', 'ORG'] as const) {
      await page.getByTestId(`pricing-scope-${scope}`).click()
      await expect(page.getByTestId(`pricing-scope-${scope}`)).toHaveAttribute('aria-pressed', 'true')
      await expect(basic).toContainText('ログイン後に対象チーム/組織で確定見積りが表示されます')
      await expect(basic).not.toContainText(/[¥￥]\s?\d/)
      await shot(page, `pricing-d-${scope}`)
    }

    await page.getByTestId('pricing-scope-USER').click()
    await expect(basic).toContainText(/[¥￥]\s?500/)
    await expect(basic).not.toContainText('確定見積り')
  })

  test('(e) ランディングの「料金プランを見る」→ /pricing', async ({ page }) => {
    await page.goto('/')
    await waitForHydration(page)
    await page.getByRole('link', { name: '料金プランを見る' }).click()
    await expect(page).toHaveURL(/\/pricing$/)
    await expect(page.getByTestId('pricing-plan-BASIC')).toBeVisible()
  })
})
