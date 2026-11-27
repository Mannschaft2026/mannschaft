/**
 * 特定商取引法に基づく表記モーダルの実機E2E（モックなし・実 BE・実 FE・実ブラウザ）。
 *
 * ロール横断について: この表記は公開／全ユーザー共通機能で権限差が無いため、
 * 「ログインなし（ランディング・/register）」と「ログイン済み（/billing/plans）」の2視点で確認する。
 * ログインは API（loginViaApi）のみで行い、モーダルを開く操作はすべて UI で行う。作成データは無い。
 *
 * (e) /payments/subscribe/[itemId] のリンクはカード入力ステップ（Stripe SetupIntent 作成後）にしか
 * 描画されない。この環境では Stripe 秘密鍵が無く POST /api/v1/me/payment-methods/setup-intent が
 * 500(PAYMENT_006) となりカードステップへ到達できないため、本 spec では検証しない（報告参照）。
 */
import { test, expect, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
const SHOT_DIR = process.env.E2E_SHOT_DIR

test.use({ storageState: { cookies: [], origins: [] } })

const modal = (page: Page) => page.getByTestId('commerce-disclosure-modal')

async function shot(page: Page, name: string) {
  if (SHOT_DIR) await page.screenshot({ path: `${SHOT_DIR}/${name}.png` })
}

test.describe('COMMERCE-MODAL-E2E: 特商法表記モーダル', () => {
  test('(a) ランディングのフッター: モーダル表示・URL 不変・閉じる', async ({ page }) => {
    await page.goto('/')
    await waitForHydration(page)
    const before = page.url()
    await page.locator('footer').getByRole('button', { name: '特定商取引法に基づく表記' }).click()
    await expect(modal(page)).toBeVisible()
    expect(page.url()).toBe(before)
    await shot(page, 'commerce-a-modal')
    await modal(page).getByRole('button', { name: '閉じる', exact: true }).last().click()
    await expect(modal(page)).toBeHidden()
    expect(page.url()).toBe(before)
  })

  test('(b) フッター「お問い合わせ」: contact 項目がモーダルのスクロール領域内で可視', async ({ page }) => {
    await page.goto('/')
    await waitForHydration(page)
    await page.locator('footer').getByRole('button', { name: 'お問い合わせ' }).click()
    await expect(modal(page)).toBeVisible()
    const contact = modal(page).locator('[data-section="contact"]')
    await expect(contact).toBeVisible()
    await expect(contact).toBeInViewport()
    const scroller = modal(page).locator('div.overflow-y-auto').first()
    const sb = await scroller.boundingBox()
    const cb = await contact.boundingBox()
    expect(sb && cb).toBeTruthy()
    // contact 項目の上端がスクロール領域の上端付近（領域高さの 1/3 以内）に来ている
    expect(cb!.y - sb!.y).toBeGreaterThanOrEqual(-2)
    expect(cb!.y - sb!.y).toBeLessThan(sb!.height / 3)
    await shot(page, 'commerce-b-contact-scrolled')
  })

  test('(c) /register: リンク→モーダル、同意チェックボックスの状態は不変', async ({ page }) => {
    await page.goto('/register')
    await waitForHydration(page)
    const terms = page.locator('#termsAccepted')
    const privacy = page.locator('#privacyPolicyAccepted')
    await terms.click() // 利用規約のみ ON
    await expect(terms).toHaveAttribute('data-p-checked', 'true')
    await expect(privacy).toHaveAttribute('data-p-checked', 'false')
    const before = page.url()
    await page.getByRole('button', { name: '特定商取引法に基づく表記' }).click()
    await expect(modal(page)).toBeVisible()
    expect(page.url()).toBe(before)
    await modal(page).getByRole('button', { name: '閉じる', exact: true }).last().click()
    await expect(modal(page)).toBeHidden()
    await expect(terms).toHaveAttribute('data-p-checked', 'true')
    await expect(privacy).toHaveAttribute('data-p-checked', 'false')
  })

  test('(d) ログイン済み /billing/plans: リンク→モーダル', async ({ page }) => {
    await loginViaApi(page, USER)
    await page.goto('/billing/plans')
    await waitForHydration(page)
    const before = page.url()
    await page.getByRole('button', { name: '特定商取引法に基づく表記' }).click()
    await expect(modal(page)).toBeVisible()
    expect(page.url()).toBe(before)
  })

  test('(f) /commerce-disclosure を URL 直打ち: ページ表示（モーダルではない）', async ({ page }) => {
    const res = await page.goto('/commerce-disclosure')
    expect(res?.status()).toBe(200)
    await expect(page).toHaveURL(/\/commerce-disclosure$/)
    await expect(page.getByRole('heading', { level: 1, name: '特定商取引法に基づく表記' })).toBeVisible()
    await expect(modal(page)).toBeHidden()
  })
})
