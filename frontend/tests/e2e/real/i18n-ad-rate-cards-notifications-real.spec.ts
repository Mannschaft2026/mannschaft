/**
 * 広告料金カード管理・通知設定の i18n（日本語直書きの解消）— 実機 E2E（CMP-260925-0902 / PR #4739）
 *
 * API モックなし。実 BE・実 FE・実ブラウザで次を確かめる。
 *   - /admin/ad-rate-cards（SYSTEM_ADMIN 専用）: 見出し・新規作成ボタン・列名・作成ダイアログのラベル/placeholder・
 *     作成/削除トーストが、ja では日本語、en・de では日本語が一切残らず訳で出る
 *   - /settings/notifications（本人の設定）: 見出し「通知設定」が訳で出て、本文にも日本語が残らない
 *   - 言語の切り替えは、アプリの正規の導線（設定 > 言語とタイムゾーン画面の「表示言語」→保存）で行う。
 *     ログイン中はアカウント言語（users.locale）が正で、cookie `i18n_locale` だけでは切り替わらない
 *     （locale.client.ts がアカウント言語で上書きする）ため。アカウント言語を保存するので、
 *     LC-01 は ja → en → de の順に切り替え、finally で必ず元の言語へ戻す（共有アカウントの言語を残さない）。
 *   - ロール横断: ad-rate-cards は SYSTEM_ADMIN のみ。一般ユーザー・他テナント ADMIN は直打ちで弾かれる。
 *     notifications は本人の設定（URL に他人の ID を取る口が無い）ため、他人の設定が見えないことは
 *     「別アカウントでログインした画面に自分の設定だけが出る」ことで確認する。
 *
 * API の利用はログイン・読み取り（現在言語の取得）・言語の復元（finally）に限る。
 * 作成する料金カードは対象都道府県に RUN_TAG を付け、削除は画面のゴミ箱ボタンで行う。
 *
 * 実行方法:
 *   BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8080 \
 *     npx playwright test tests/e2e/real/i18n-ad-rate-cards-notifications-real.spec.ts \
 *     -c playwright-real.config.ts --project chromium-real --reporter=list
 */

import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {
  test,
  expect,
  type Browser,
  type BrowserContext,
  type Page,
  type TestInfo,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'default' })
test.setTimeout(300_000)

const BE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }
const USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
const OTHER_TENANT_ADMIN = { email: 'e2e-dummy-1@test.mannschaft.local', password: 'TestPass2026!' }
const RUN_TAG = `R${Date.now().toString(36)}`.toUpperCase()
/** system-admin ミドルウェアの拒否トースト（ja / en）。非 SYSTEM_ADMIN は /dashboard へ戻される。 */
const DENIED = { ja: ['アクセスできません', '管理者権限が必要です'], en: ['Access denied', 'Administrator privileges are required'] } as const
/** ひらがな・カタカナ・漢字（全角記号・全角英数は含めない）。 */
const JAPANESE = /[぀-ゟ゠-ヿ一-鿿]/

type Lang = 'ja' | 'en' | 'de'

async function newLoggedInPage(
  browser: Browser,
  creds: { email: string; password: string },
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
  const page = await context.newPage()
  await loginViaApi(page, creds, { apiBaseUrl: BE, deferNavigation: true })
  return { context, page }
}

/** advertising.json / common.json の正本（PR #4739 のロケール）。画面に出るべき文言。 */
const RATE_CARD = {
  ja: { title: '広告料金カード管理', open: '新規作成', submit: '作成', cols: ['都道府県', 'テンプレート', '課金モデル', '単価', '最低日予算', '適用開始', '適用終了'], dialog: '料金カード作成', labels: ['都道府県（空欄=全国）', 'テンプレート（空欄=共通）', '課金モデル', '単価', '最低日予算（円）', '適用開始日'], placeholders: ['例: 東京都', '例: SPORTS'], unlimited: '無期限', created: '料金カードを作成しました', deleted: '削除しました' },
  en: { title: 'Ad rate card management', open: 'Create', submit: 'Create', cols: ['Prefecture', 'Template', 'Pricing model', 'Unit price', 'Minimum daily budget', 'Effective from', 'Effective until'], dialog: 'Create rate card', labels: ['Prefecture (blank = nationwide)', 'Template (blank = common)', 'Pricing model', 'Unit price', 'Minimum daily budget (JPY)', 'Effective start date'], placeholders: ['e.g. Tokyo', 'e.g. SPORTS'], unlimited: 'No end date', created: 'Rate card created', deleted: 'Deleted' },
  de: { title: 'Verwaltung der Werbetarife', open: 'Neu', submit: 'Erstellen', cols: ['Präfektur', 'Vorlage', 'Abrechnungsmodell', 'Stückpreis', 'Mindesttagesbudget', 'Gültig ab', 'Gültig bis'], dialog: 'Tarif erstellen', labels: ['Präfektur (leer = landesweit)', 'Vorlage (leer = allgemein)', 'Abrechnungsmodell', 'Stückpreis', 'Mindesttagesbudget (JPY)', 'Startdatum der Gültigkeit'], placeholders: ['z. B. Tokio', 'z. B. SPORTS'], unlimited: 'Unbefristet', created: 'Tarif erstellt', deleted: 'Gelöscht' },
} as const
const NOTIFICATION_TITLE: Record<Lang, string> = { ja: '通知設定', en: 'Notification settings', de: 'Benachrichtigungseinstellungen' }
/** 設定 > 言語とタイムゾーン 画面の「表示言語」の選択肢名 */
const LANGUAGE_OPTION: Record<Lang, string> = { ja: '日本語', en: 'English', de: 'Deutsch' }
/** 保存ボタン（保存時点の表示言語のもの） */
const SAVE_BUTTON: Record<Lang, string> = { ja: '保存', en: 'Save', de: 'Speichern' }

async function gotoAndSettle(page: Page, url: string): Promise<void> {
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 180_000 })
  await waitForHydration(page)
  // eslint-disable-next-line no-restricted-syntax -- スピナーが初めから存在しないページでは待つ対象が無いだけ（失敗要因ではない）
  await page.locator('.p-progressspinner').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
  // eslint-disable-next-line no-restricted-syntax -- スピナーが初めから存在しないページでは待つ対象が無いだけ（失敗要因ではない）
  await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
}

async function shot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const file = testInfo.outputPath(`${name}.png`)
  await page.screenshot({ path: file, fullPage: true })
  await testInfo.attach(name, { path: file, contentType: 'image/png' })
}

async function note(testInfo: TestInfo, name: string, body: unknown): Promise<void> {
  const text = typeof body === 'string' ? body : JSON.stringify(body, null, 2)
  console.log(`[${testInfo.title}] ${name}: ${text}`)
  await testInfo.attach(name, { body: text, contentType: 'text/plain' })
}

/**
 * 翻訳の対象外（利用者・運用者が入力して保存したデータ。画面の文言ではない）。seed アカウントが所属するチーム・組織の名前と、
 * ログイン者の氏名。これ以外の日本語行は直書きの残りとして失敗させる。
 */
const USER_DATA_LINES = ['FC東京U-18（テスト）', '日本サッカー協会（テスト）', 'E2Eテストマンション管理組合', 'E2E管理者', '管理', 'E2Eユーザー 一般', 'E2E管理者 管理']

/** 指定範囲の本文から、日本語を含む行（利用者データを除く）を返す。 */
async function japaneseLines(page: Page, selector: string): Promise<string[]> {
  const text = await page.locator(selector).first().innerText()
  return text.split('\n').map((l) => l.trim()).filter((l) => l && JAPANESE.test(l) && !USER_DATA_LINES.includes(l))
}

async function expectHtmlLang(page: Page, lang: Lang): Promise<void> {
  await expect(page.locator('html')).toHaveAttribute('lang', new RegExp(`^${lang}`), { timeout: 60_000 })
}

/** 設定 > 言語とタイムゾーン で表示言語を切り替えて保存する（from: 保存前の表示言語）。 */
async function switchLanguageViaUi(page: Page, from: Lang, to: Lang, testInfo: TestInfo): Promise<void> {
  await gotoAndSettle(page, '/settings/language')
  await expectHtmlLang(page, from)
  const select = page.locator('.p-select').first()
  await expect(select).toBeVisible({ timeout: 30_000 })
  await select.click()
  await page.getByRole('option', { name: LANGUAGE_OPTION[to], exact: true }).click()
  const [put] = await Promise.all([
    page.waitForResponse((r) => r.url().endsWith('/api/v1/users/me') && r.request().method() === 'PUT'),
    page.getByRole('button', { name: SAVE_BUTTON[from], exact: true }).click(),
  ])
  expect(put.status()).toBeLessThan(300)
  await note(testInfo, `言語を ${to} に保存 (PUT users/me)`, JSON.parse(put.request().postData() ?? '{}') as Record<string, unknown>)
  await expectHtmlLang(page, to)
}

/** /admin/ad-rate-cards の見出し・ボタン・列名・作成ダイアログのラベル/placeholder を確認する。 */
async function checkRateCardsScreen(page: Page, lang: Lang, testInfo: TestInfo): Promise<void> {
  const exp = RATE_CARD[lang]
  await gotoAndSettle(page, '/admin/ad-rate-cards')
  await expectHtmlLang(page, lang)
  await expect(page.getByRole('heading', { name: exp.title })).toBeVisible({ timeout: 30_000 })
  await expect(page.getByRole('button', { name: exp.open, exact: true })).toBeVisible()
  for (const col of exp.cols) await expect(page.getByRole('columnheader', { name: col, exact: true })).toBeVisible()
  await shot(page, testInfo, `rate-cards-${lang}`)

  await page.getByRole('button', { name: exp.open, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: exp.dialog })
  await expect(dialog).toBeVisible()
  for (const label of exp.labels) await expect(dialog.getByText(label, { exact: true })).toBeVisible()
  for (const ph of exp.placeholders) await expect(dialog.getByPlaceholder(ph, { exact: true })).toBeVisible()
  await expect(dialog.getByRole('button', { name: exp.submit, exact: true })).toBeVisible()
  await shot(page, testInfo, `rate-cards-dialog-${lang}`)
  if (lang !== 'ja') {
    const residue = await japaneseLines(page, 'body')
    await note(testInfo, `${lang} の ad-rate-cards（ダイアログ表示中）に残った日本語行`, residue)
    expect(residue, `${lang} で日本語の直書きが残っていない`).toEqual([])
  }
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
}

/** 料金カードを画面で作成→作成トースト→画面で削除→削除トースト（対象都道府県に RUN_TAG を付ける）。 */
async function createAndDeleteRateCard(page: Page, lang: Lang, testInfo: TestInfo): Promise<void> {
  const exp = RATE_CARD[lang]
  const prefecture = `E2E-${RUN_TAG}-${lang}`
  await gotoAndSettle(page, '/admin/ad-rate-cards')
  await page.getByRole('button', { name: exp.open, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: exp.dialog })
  await expect(dialog).toBeVisible()
  await dialog.getByPlaceholder(exp.placeholders[0]).fill(prefecture)
  const unit = dialog.getByRole('spinbutton').first()
  await unit.click()
  await unit.pressSequentially('1.5')
  await unit.press('Tab')
  // 適用開始日: 未来日（過去の料金は削除できないため、削除検証のために先の日付にする）
  const future = new Date(Date.now() + 400 * 86400_000).toISOString().slice(0, 10)
  await dialog.locator('input[type="date"]').fill(future)
  const [res] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/ad-rate-cards') && r.request().method() === 'POST'),
    dialog.getByRole('button', { name: exp.submit, exact: true }).click(),
  ])
  await note(testInfo, `POST ad-rate-cards [${lang}]`, { status: res.status(), request: res.request().postData(), body: (await res.text()).slice(0, 300) })
  expect(res.status()).toBeLessThan(300)
  await expect(page.locator('.p-toast-message').filter({ hasText: exp.created }).last()).toBeVisible()
  await shot(page, testInfo, `rate-card-created-toast-${lang}`)
  const row = page.getByRole('row').filter({ hasText: prefecture })
  await expect(row).toBeVisible()
  await expect(row).toContainText(exp.unlimited)

  const [del] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/ad-rate-cards/') && r.request().method() === 'DELETE'),
    row.locator('button').last().click(),
  ])
  expect(del.status()).toBeLessThan(300)
  await expect(page.locator('.p-toast-message').filter({ hasText: exp.deleted }).last()).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: prefecture })).toHaveCount(0)
  await shot(page, testInfo, `rate-card-deleted-toast-${lang}`)
  if (lang !== 'ja') {
    const toastResidue = (await page.locator('.p-toast-message').allInnerTexts()).filter((t) => JAPANESE.test(t))
    expect(toastResidue, `${lang} のトーストに日本語が残らない`).toEqual([])
  }
}

/** LC-01 が測った「BE が返す通知種別ラベルのうち日本語のままのもの」（言語別）を LC-07 へ渡す一時ファイル。 */
const BE_LABEL_FILE = path.join(os.tmpdir(), 'i18n-ad-rate-cards-notifications-be-labels.json')

/**
 * /settings/notifications を確認する。FE の文言（見出し・説明・ボタンなど）に日本語が残らないことを検証する。
 * 通知種別の名前（`tp.label`）は BE が利用者の言語で返す値（GET /api/v1/notification-type-preferences）で、
 * FE の i18n の対象外のため、FE の直書き検査からは外し、日本語のまま返ったものを別に数えて LC-07 で検証する。
 */
async function checkNotificationsScreen(page: Page, lang: Lang, testInfo: TestInfo): Promise<void> {
  const beLabels: string[] = []
  page.on('response', (r) => {
    if (r.url().includes('/api/v1/notification-type-preferences') && r.request().method() === 'GET') {
      void r.json().then((j: { data?: { label?: string }[] }) => {
        for (const row of j.data ?? []) if (row.label) beLabels.push(row.label)
      })
    }
  })
  await gotoAndSettle(page, '/settings/notifications')
  await expectHtmlLang(page, lang)
  await expect(page.getByRole('heading', { name: NOTIFICATION_TITLE[lang] })).toBeVisible({ timeout: 60_000 })
  await expect.poll(() => beLabels.length, { timeout: 30_000 }).toBeGreaterThan(0)
  await page.waitForTimeout(2_000)
  await shot(page, testInfo, `notifications-${lang}`)
  if (lang !== 'ja') {
    const lines = await japaneseLines(page, 'body')
    const beJapanese = beLabels.filter((l) => JAPANESE.test(l))
    const residue = lines.filter((l) => !beLabels.includes(l))
    await note(testInfo, `${lang} の /settings/notifications: FE 文言に残った日本語行`, residue)
    await note(testInfo, `${lang} の通知種別ラベル（BE 応答）のうち日本語のもの`, { total: beLabels.length, japanese: beJapanese.length, sample: beJapanese.slice(0, 5) })
    let all: Record<string, { total: number; japanese: number }> = {}
    try {
      all = JSON.parse(fs.readFileSync(BE_LABEL_FILE, 'utf8')) as typeof all
    } catch {
      all = {}
    }
    all[lang] = { total: beLabels.length, japanese: beJapanese.length }
    fs.writeFileSync(BE_LABEL_FILE, JSON.stringify(all))
    expect(residue, `${lang} で FE の日本語直書きが残っていない`).toEqual([])
  }
}

// ===========================================================================
// LC-01 SYSTEM_ADMIN: ja（基準）→ 設定画面で en → de に切り替え → ja へ戻す。各言語で両画面を確認
// ===========================================================================
test('LC-01 /admin/ad-rate-cards と /settings/notifications: ja で日本語、設定画面で en・de に切り替えると日本語が残らず訳で出る', async ({ browser }, testInfo) => {
  test.setTimeout(900_000)
  const { context, page } = await newLoggedInPage(browser, ADMIN)
  const me = await page.request.get(`${BE}/api/v1/users/me`)
  const original = ((await me.json()) as { data: { locale: string; timezone: string; countryCode?: string | null } }).data
  await note(testInfo, '元の言語設定（終了時に復元する）', { locale: original.locale, timezone: original.timezone, countryCode: original.countryCode })
  try {
    expect(original.locale, 'テスト開始時のアカウント言語は ja').toBe('ja')
    // 基準: ja
    await checkRateCardsScreen(page, 'ja', testInfo)
    await createAndDeleteRateCard(page, 'ja', testInfo)
    await checkNotificationsScreen(page, 'ja', testInfo)

    // 注: loginViaApi(deferNavigation) は文書ごとに localStorage の currentUser（locale）を初期値へ戻す
    //     init script を入れる。言語を保存した後の確認は、アカウント言語で再ログインした新しいセッションで行う
    //     （実利用で「保存後にもう一度ログインして使う」流れと同じ）。
    let current: Lang = 'ja'
    for (const lang of ['en', 'de'] as const) {
      const sw = await newLoggedInPage(browser, ADMIN)
      try {
        await switchLanguageViaUi(sw.page, current, lang, testInfo)
      } finally {
        await sw.context.close()
      }
      current = lang
      const chk = await newLoggedInPage(browser, ADMIN)
      try {
        await checkRateCardsScreen(chk.page, lang, testInfo)
        await createAndDeleteRateCard(chk.page, lang, testInfo)
        await checkNotificationsScreen(chk.page, lang, testInfo)
      } finally {
        await chk.context.close()
      }
    }
    // 画面で ja に戻す（ja 復帰後も日本語で出る）
    const back = await newLoggedInPage(browser, ADMIN)
    try {
      await switchLanguageViaUi(back.page, current, 'ja', testInfo)
    } finally {
      await back.context.close()
    }
    const fin = await newLoggedInPage(browser, ADMIN)
    try {
      await checkNotificationsScreen(fin.page, 'ja', testInfo)
    } finally {
      await fin.context.close()
    }
  } finally {
    // 言語を元へ戻す（後始末。API 利用は復元のみ。画面で戻せた場合も冪等）
    const restore = await page.request.put(`${BE}/api/v1/users/me`, {
      data: { locale: original.locale, timezone: original.timezone, countryCode: original.countryCode ?? null },
    })
    await note(testInfo, '言語の復元 PUT', restore.status())
    const after = ((await (await page.request.get(`${BE}/api/v1/users/me`)).json()) as { data: { locale: string } }).data.locale
    await note(testInfo, '終了時のアカウント言語', after)
    expect(after).toBe(original.locale)
    await context.close()
  }
})

// ===========================================================================
// LC-07 通知種別ラベル（BE が返す名前）も ja 以外で日本語が残らない
//   既知の実装欠陥: BE の messages_de / zh / ko / es には notification.type.*.label が 5 件しか無く、
//   残りは既定（日本語）へフォールバックする。FE の i18n（本 PR の範囲）の外のため、FAIL（実装欠陥）として
//   test.fail() で明示する（直った場合は逆に緑→赤の反転で気付ける。skip にはしない）。
// ===========================================================================
test.fail('LC-07 /settings/notifications: ja 以外（de）でも通知種別の名前が日本語のまま返らない（既知の実装欠陥: BE の messages_de に通知種別ラベルが 5/50 件しか無い）', async () => {
  const all = JSON.parse(fs.readFileSync(BE_LABEL_FILE, 'utf8')) as Record<string, { total: number; japanese: number }>
  expect(all.en?.japanese, 'en は通知種別の名前がすべて訳で返る').toBe(0)
  expect(all.de?.japanese, 'de でも通知種別の名前がすべて訳で返る').toBe(0)
})

// ===========================================================================
// LC-05 ロール横断
// ===========================================================================
for (const [label, creds] of [['一般ユーザー（組織 e2e の ADMIN）', USER], ['他テナント ADMIN（チーム fc-u-18）', OTHER_TENANT_ADMIN]] as const) {
  test(`LC-05 ${label}: /admin/ad-rate-cards は直打ちで弾かれ、導線も出ない`, async ({ browser }, testInfo) => {
    const { context, page } = await newLoggedInPage(browser, creds)
    try {
      const rateApis: { url: string; status: number }[] = []
      page.on('response', (r) => {
        if (r.url().startsWith(BE) && r.url().includes('/ad-rate-cards')) rateApis.push({ url: r.url(), status: r.status() })
      })
      await page.goto('/admin/ad-rate-cards', { waitUntil: 'domcontentloaded', timeout: 180_000 })
      const toast = page.locator('.p-toast-message').filter({ hasText: DENIED.ja[0] })
      await expect(toast).toContainText(DENIED.ja[1], { timeout: 60_000 })
      await shot(page, testInfo, `denied-${creds.email.split('@')[0]}`)
      await page.waitForURL(/\/dashboard/, { timeout: 30_000 })
      await note(testInfo, `${creds.email} 直打ち`, { url: page.url(), toast: await toast.innerText(), rateApis })
      await expect(page.getByRole('heading', { name: RATE_CARD.ja.title })).toHaveCount(0)
      await expect(page.getByRole('button', { name: RATE_CARD.ja.open, exact: true })).toHaveCount(0)
      expect(rateApis.filter((x) => x.status < 401), '一覧 API が成功していない').toEqual([])
      // BE も直接叩いて 403（FE の出し分けだけに頼っていない）
      const api = await page.request.get(`${BE}/api/v1/system-admin/ad-rate-cards`)
      await note(testInfo, `${creds.email} GET /system-admin/ad-rate-cards（素通り検査）`, api.status())
      expect([401, 403]).toContain(api.status())
    } finally {
      await context.close()
    }
  })
}

test('LC-06 /settings/notifications は本人の設定のみ: アカウントごとに本人の設定画面に入れ、未ログインはログインへ戻される', async ({ browser }, testInfo) => {
  // URL に他人の ID を取る口が無い画面（権限差なし・本人専用）。
  for (const creds of [USER, OTHER_TENANT_ADMIN]) {
    const { context, page } = await newLoggedInPage(browser, creds)
    try {
      const prefRes = page.waitForResponse((r) => /preferences/.test(r.url()) && r.request().method() === 'GET', { timeout: 30_000 })
      await gotoAndSettle(page, '/settings/notifications')
      await expect(page.getByRole('heading', { name: NOTIFICATION_TITLE.ja })).toBeVisible({ timeout: 30_000 })
      const res = await prefRes
      await note(testInfo, `${creds.email} の通知設定 API`, { url: res.url(), status: res.status(), body: (await res.text()).slice(0, 300) })
      await shot(page, testInfo, `notifications-own-${creds.email.split('@')[0]}`)
      expect(res.status()).toBe(200)
    } finally {
      await context.close()
    }
  }
  const anon = await browser.newContext({ locale: 'ja-JP' })
  const anonPage = await anon.newPage()
  try {
    await anonPage.goto('/settings/notifications', { waitUntil: 'domcontentloaded' })
    await anonPage.waitForURL(/\/login/, { timeout: 30_000 })
    await note(testInfo, '未ログインで直打ち', anonPage.url())
  } finally {
    await anon.close()
  }
})
