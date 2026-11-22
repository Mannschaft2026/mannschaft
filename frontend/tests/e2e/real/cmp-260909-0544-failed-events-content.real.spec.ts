/**
 * CMP-260909-0544 実機 E2E（AC-1） — 失敗イベント画面に SQL・テーブル名・カラム名・例外クラス名・生 enum が出ない
 *
 * 前提データについて（殿の許可: 使い捨てのテスト DB への投入）
 *   失敗イベントを作る正当な操作が画面・API に無いため、`shift_budget_failed_events` に
 *   BE が実際に記録する形（ShiftBudgetConsumptionRecordListener#recordFailureSafe が作る
 *   「例外クラス名: Hibernate メッセージ（SQL 全文）」）の行を beforeAll で直接投入する。
 *   これは前提データの作成のみ。対象操作（画面の閲覧・ダイアログ操作）は実ブラウザで行う。
 *   投入は組織 11 の RUN_TAG 付き行だけ。afterAll で RUN_TAG の行だけ削除し、件数 0 を確認する。
 *   未知の種別は DB の CHECK 制約（chk_sbfe_event_type）が拒否するため、実 DB では投入できない
 *   （FE の未知種別の分岐は単体テストの範囲）。
 *
 * 前提: feature.shift-budget.enabled=true の BE（8081）+ FE（3002）、docker の mannschaft-mysql
 */

import { execFileSync } from 'node:child_process'
import { test, expect, request as pwRequest, type APIRequestContext, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const BE_API = `${API_BASE_URL}/api/v1`
const PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const ORG_ID = 11
const ORG_NAME = 'E2Eテストマンション管理組合'
const ADMIN = { email: 'e2e-user@test.mannschaft.local', password: PASSWORD }
const RUN_TAG = `R${Date.now().toString(36)}`
const SHOT = process.env.EVIDENCE_DIR ?? '../docs/evidence/CMP-260909-0544'

test.describe.configure({ mode: 'serial' })
test.setTimeout(120_000)

function mysql(sql: string): string {
  return execFileSync(
    'docker',
    ['exec', '-i', 'mannschaft-mysql', 'mysql', '-uroot', '-proot', '-N', '--default-character-set=utf8mb4', 'mannschaft'],
    { input: sql, encoding: 'utf8', stdio: ['pipe', 'pipe', 'ignore'] },
  ).trim()
}

const SQL_INSERT = 'insert into shift_budget_consumptions (allocation_id,amount,hours,hourly_rate,shift_id,slot_id,status,user_id,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?)'
const HIBERNATE_MSG = `org.springframework.dao.DataIntegrityViolationException: could not execute statement [Duplicate entry '5-9' for key 'shift_budget_consumptions.uq_sbc_slot_user'] [${SQL_INSERT}]; SQL [${SQL_INSERT}]; constraint [uq_sbc_slot_user]`

const SEED: { type: string, status: string, retry: number, msg: string }[] = [
  { type: 'CONSUMPTION_RECORD', status: 'PENDING', retry: 0, msg: HIBERNATE_MSG },
  { type: 'CONSUMPTION_RECORD', status: 'EXHAUSTED', retry: 3, msg: 'Auto-retry not supported for CONSUMPTION_RECORD; manual reconciliation required.' },
  { type: 'CONSUMPTION_CANCEL', status: 'PENDING', retry: 0, msg: `org.hibernate.exception.ConstraintViolationException: could not execute statement [update shift_budget_allocations set consumed_amount=consumed_amount-? where id=?]` },
  { type: 'THRESHOLD_ALERT', status: 'RETRYING', retry: 1, msg: 'java.lang.IllegalStateException: select a1_0.id,a1_0.allocated_amount from shift_budget_allocations a1_0 where a1_0.id=?' },
  { type: 'WORKFLOW_START', status: 'PENDING', retry: 0, msg: 'jakarta.persistence.PersistenceException: workflow_templates.over_limit_workflow_id not found' },
  { type: 'NOTIFICATION_SEND', status: 'MANUAL_RESOLVED', retry: 2, msg: 'java.net.SocketTimeoutException: Read timed out (notifications INSERT)' },
]

/** 画面本文に出てはならない内部情報。 */
const FORBIDDEN: [string, RegExp][] = [
  ['SQL 文', /\b(select|insert into|update|delete from)\b[^<]{0,80}\b(from|values|set|into)\b/i],
  ['テーブル名', /shift_budget_|workflow_templates|budget_categories|notifications/],
  ['カラム名', /allocated_amount|consumed_amount|allocation_id|hourly_rate|over_limit_workflow_id|uq_sbc_/],
  ['例外クラス名', /Exception|org\.springframework|org\.hibernate|jakarta\.|java\.(lang|net|sql)/],
  ['生の enum コード', /CONSUMPTION_RECORD|CONSUMPTION_CANCEL|THRESHOLD_ALERT|WORKFLOW_START|NOTIFICATION_SEND|\bPENDING\b|\bRETRYING\b|\bEXHAUSTED\b|MANUAL_RESOLVED|\bUNKNOWN\b/],
  ['Duplicate entry', /Duplicate entry/],
]

let api: APIRequestContext
let page: Page
let adminToken = ''
let seededIds: number[] = []

test.beforeAll(async ({ browser }) => {
  api = await pwRequest.newContext()
  const login = await api.post(`${BE_API}/auth/login`, { data: ADMIN })
  expect(login.status()).toBe(200)
  adminToken = ((await login.json()) as { data: { accessToken: string } }).data.accessToken

  expect(mysql(`select count(*) from shift_budget_failed_events where error_message like '%${RUN_TAG}%'`)).toBe('0')
  for (const [i, s] of SEED.entries()) {
    const msg = `${s.msg} /*${RUN_TAG}*/`.replace(/'/g, "''")
    mysql(`insert into shift_budget_failed_events (organization_id,event_type,source_id,payload,error_message,retry_count,status) values (${ORG_ID},'${s.type}',${9000 + i},'{"run_tag":"${RUN_TAG}"}','${msg}',${s.retry},'${s.status}')`)
  }
  seededIds = mysql(`select id from shift_budget_failed_events where error_message like '%${RUN_TAG}%' order by id`).split('\n').map(Number)
  expect(seededIds).toHaveLength(SEED.length)

  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  page = await context.newPage()
  await page.addInitScript(
    (d) => { localStorage.setItem('currentScope', JSON.stringify({ type: 'organization', id: String(d.id), name: d.name })) },
    { id: ORG_ID, name: ORG_NAME },
  )
  await loginViaApi(page, ADMIN, { apiBaseUrl: API_BASE_URL })
})

test.afterAll(async () => {
  mysql(`delete from shift_budget_failed_events where organization_id=${ORG_ID} and error_message like '%${RUN_TAG}%'`)
  const left = mysql(`select count(*) from shift_budget_failed_events where error_message like '%${RUN_TAG}%'`)
  console.log(`[後始末] RUN_TAG=${RUN_TAG} の残件数: ${left}`)
  await page?.context().close()
  await api?.dispose()
  expect(left, '自分が投入した行は全て削除されている').toBe('0')
})

/** スクリプト・スタイルを除いた DOM 全体（属性の title / aria-label などを含む）。 */
async function pageMarkup(p: Page): Promise<string> {
  return p.evaluate(() => {
    const root = document.querySelector('#__nuxt')!.cloneNode(true) as HTMLElement
    root.querySelectorAll('script,style').forEach(n => n.remove())
    // PrimeVue のオーバーレイ（ダイアログ・ドロップダウン）は body 直下に出る
    const overlays = [...document.body.children].filter(c => c.id !== '__nuxt' && c.tagName !== 'SCRIPT' && c.tagName !== 'STYLE')
    return root.outerHTML + overlays.map(o => (o as HTMLElement).outerHTML).join('')
  })
}

/** 先頭列（ID 列）のセル。再試行回数など他の列の数字と区別する。 */
function idCell(id: number) {
  return page.locator('tbody tr td:first-child').filter({ hasText: new RegExp(`^\\s*${id}\\s*$`) })
}

function rowOf(id: number) {
  return page.locator('tbody tr').filter({ has: page.locator('td:first-child').filter({ hasText: new RegExp(`^\\s*${id}\\s*$`) }) })
}

function expectClean(markup: string, where: string) {
  for (const [label, re] of FORBIDDEN) {
    const m = markup.match(re)
    expect(m, `${where}: ${label} が画面に出ていない（検出: ${m?.[0]}）`).toBeNull()
  }
}

test('FAILED-AC1-01 (事実): 実 BE の API 応答には error_message が含まれる（BE 側の残務 CMP-261121-2129）', async () => {
  const res = await api.get(`${BE_API}/shift-budget/failed-events?page=0&size=100`, {
    headers: { Authorization: `Bearer ${adminToken}`, 'X-Organization-Id': String(ORG_ID) },
  })
  expect(res.status()).toBe(200)
  const items = ((await res.json()) as { data: { id: number, error_message: string }[] }).data
  const mine = items.filter(i => seededIds.includes(i.id))
  expect(mine).toHaveLength(SEED.length)
  expect(mine.some(i => /insert into shift_budget_consumptions/.test(i.error_message))).toBe(true)
  console.log(`[事実] API 応答の error_message に SQL 全文が含まれる: ${mine.find(i => /insert into/.test(i.error_message))!.error_message.slice(0, 200)}...`)
})

test('FAILED-AC1-02 (AC-1・権限あり): 表の DOM に SQL・テーブル名・カラム名・例外クラス名・生 enum が出ず、種別ごとの説明と ID が出る', async () => {
  await page.goto('/admin/shift-budget/failed-events')
  await waitForHydration(page)
  for (const id of seededIds) {
    await expect(idCell(id), `ID ${id} の行`).toBeVisible({ timeout: 30_000 })
  }

  const labels = ['消費実績の記録', '消費実績の取消', 'しきい値アラート', '承認フローの開始', '通知の送信']
  for (const l of labels) await expect(page.getByText(l, { exact: true }).first()).toBeVisible()
  await expect(page.getByText('お問い合わせの際は ID をお伝えください').first(), '種別ごとの説明と ID の案内').toBeVisible()
  await page.screenshot({ path: SHOT + '/failed-ac1-table.png' })

  expectClean(await pageMarkup(page), '表')
})

test('FAILED-AC1-03 (AC-1): 状態フィルタ・再実行/補正済ダイアログを開いた DOM にも内部情報が出ない', async () => {
  // フィルタのドロップダウン
  await page.getByRole('combobox').first().click()
  expectClean(await pageMarkup(page), 'フィルタ展開')
  await page.keyboard.press('Escape')

  // 再実行ダイアログ（再実行できる種別の行）。確定はせず閉じる。
  await page.getByRole('button', { name: '再実行' }).first().click()
  await expect(page.getByRole('dialog')).toBeVisible()
  expectClean(await pageMarkup(page), '再実行ダイアログ')
  await page.getByRole('dialog').getByRole('button', { name: 'キャンセル' }).click()
  await expect(page.getByRole('dialog')).toBeHidden()

  await page.getByRole('button', { name: '補正済マーク' }).first().click()
  await expect(page.getByRole('dialog')).toBeVisible()
  expectClean(await pageMarkup(page), '補正済ダイアログ')
  await page.getByRole('dialog').getByRole('button', { name: 'キャンセル' }).click()
})

test('FAILED-AC1-04 (AC-1): 再実行できない種別（消費実績の記録・取消）の行には「再実行」が出ない', async () => {
  for (const id of seededIds.slice(0, 3)) {
    const row = rowOf(id)
    await expect(row, `ID ${id} の行が存在する`).toHaveCount(1)
    await expect(row.getByRole('button', { name: '再実行' }), `ID ${id}`).toHaveCount(0)
  }
  const alertRow = rowOf(seededIds[3]!)
  await expect(alertRow.getByRole('button', { name: '再実行' })).toHaveCount(1)
})
