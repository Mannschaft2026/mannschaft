import { test, expect, type BrowserContext, type Page } from '@playwright/test'
import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

// L1-M（Modulith イベント基盤）の実機E2E。検体の基盤接続だけを検証する。C/F実業務採用・監査保持方針の証明には使わない。
// 前提: 試験用検体入り BE を 8081 で、FE を 3001 で起動しておく。手順の正本は
//   backend/src/test/java/com/mannschaft/app/moduleevent/e2e/README.md。要点:
//   - BE は Gradle 配下（bootRunModuleEventE2e）ではなく、`bootJar moduleEventE2eFixtureJar` の後に jar を
//     PropertiesLauncher で `java` 直接起動し、シェルから切り離す（Gradle 配下はブラウザ負荷下で JVM が 0xC0000005 で落ちた。ホストのメモリ逼迫時は直接起動でも落ちうる）。
//     引数に `--server.port=8081 --server.address=::`、環境に MANNSCHAFT_EMAIL_SIMULATE=true と
//     MANNSCHAFT_ALLOWED_ORIGINS=http://localhost:3001,http://localhost:8081、本陣と同じ暗号鍵・R2 の環境変数が必須。
//   - FE は `NUXT_PUBLIC_API_BASE=http://localhost:8081 npm run dev -- --port 3001`。
//   BE が検体 actor 11名を生成して実行ディレクトリ（既定 backend/build/l1m-real-e2e、L1M_E2E_RUN_DIR で上書き）の
//   actors.json に書き出し、state.json に検体状態を出し続ける。spec は controls/ に制御 command を置いて検体を進める。
// 実行: L1M_E2E=1 BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081 npx playwright test
//   tests/e2e/real/l1m-module-events.real.spec.ts --config=playwright-real.config.ts --project=chromium-real --no-deps
// L1M_E2E=1 が無い時は playwright-real.config.ts が本 spec を除外する（検体入り BE 無しの全real一括実行で必ず落ちるため）。
// 検体シナリオは BE 起動ごとに1回きり。再実行時は BE を再起動する。
// 認証は既存Cookie経路（fixtures/auth の loginViaApi）。手書token、shared.auth、HTTP mockは使用しない。
type Actor = { code: string; userId: number; email: string; password: string; locale: string }
type Phase = 'DELAYED' | 'QUARANTINED' | 'RECOVERED'
type Workflow = {
  operationId: string; publications: string[]; pending: number; completed: number
  entered: boolean; recovered: number; noticeCount: number
  deliveries: Partial<Record<Phase, Record<string, number>>>
}
type State = { delayed: Workflow; organization: Workflow; fast: Workflow; quarantine: Workflow; auditCount: number }
const locales = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const messages: Record<(typeof locales)[number], Record<Phase, readonly [string, string]>> = {
  "ja": {
    "DELAYED": [
      "処理の反映が遅れています",
      "一部の処理の反映に時間がかかっています。完了後にお知らせします。"
    ],
    "QUARANTINED": [
      "一部の処理が停止しています",
      "エラーにより一部の処理が停止しています。システム管理者による確認が必要です。復旧後にお知らせします。"
    ],
    "RECOVERED": [
      "遅れていた処理が完了しました",
      "停止または遅延していた処理が完了しました。"
    ]
  },
  "en": {
    "DELAYED": [
      "Processing is delayed",
      "Some processing is taking longer than expected. We will notify you when it is complete."
    ],
    "QUARANTINED": [
      "Some processing has stopped",
      "An error has stopped some processing. A system administrator needs to investigate. We will notify you when it is resolved."
    ],
    "RECOVERED": [
      "Delayed processing is complete",
      "The processing that was stopped or delayed is now complete."
    ]
  },
  "zh": {
    "DELAYED": [
      "处理出现延迟",
      "部分处理耗时较长。完成后我们会通知您。"
    ],
    "QUARANTINED": [
      "部分处理已停止",
      "错误导致部分处理停止，需要系统管理员检查。恢复后我们会通知您。"
    ],
    "RECOVERED": [
      "延迟的处理已完成",
      "此前停止或延迟的处理现已完成。"
    ]
  },
  "ko": {
    "DELAYED": [
      "처리가 지연되고 있습니다",
      "일부 처리가 예상보다 오래 걸리고 있습니다. 완료되면 알려드리겠습니다."
    ],
    "QUARANTINED": [
      "일부 처리가 중지되었습니다",
      "오류로 일부 처리가 중지되어 시스템 관리자의 확인이 필요합니다. 복구되면 알려드리겠습니다."
    ],
    "RECOVERED": [
      "지연된 처리가 완료되었습니다",
      "중지되거나 지연되었던 처리가 완료되었습니다."
    ]
  },
  "es": {
    "DELAYED": [
      "El procesamiento se está retrasando",
      "Parte del procesamiento está tardando más de lo previsto. Le avisaremos cuando termine."
    ],
    "QUARANTINED": [
      "Parte del procesamiento se ha detenido",
      "Un error ha detenido parte del procesamiento. Un administrador del sistema debe revisarlo. Le avisaremos cuando se resuelva."
    ],
    "RECOVERED": [
      "El procesamiento retrasado ha finalizado",
      "El procesamiento que estaba detenido o retrasado ha finalizado."
    ]
  },
  "de": {
    "DELAYED": [
      "Die Verarbeitung verzögert sich",
      "Ein Teil der Verarbeitung dauert länger als erwartet. Wir benachrichtigen Sie, sobald die Verarbeitung abgeschlossen ist."
    ],
    "QUARANTINED": [
      "Ein Teil der Verarbeitung wurde angehalten",
      "Ein Fehler hat einen Teil der Verarbeitung angehalten. Ein Systemadministrator muss dies prüfen. Wir benachrichtigen Sie nach der Behebung."
    ],
    "RECOVERED": [
      "Die verzögerte Verarbeitung ist abgeschlossen",
      "Die angehaltene oder verzögerte Verarbeitung ist jetzt abgeschlossen."
    ]
  }
}

const RUN_DIR = process.env.L1M_E2E_RUN_DIR ?? path.resolve(process.cwd(), '../backend/build/l1m-real-e2e')
const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8081'
const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3001'

async function actorsFromRunDirectory(run: string): Promise<Actor[]> {
  let raw: string
  try {
    raw = await readFile(path.join(run, 'actors.json'), 'utf8')
  } catch (cause) {
    throw new Error(`検体入りBE（moduleevent/e2e/README.md の手順で起動）の actors.json が読めない: ${run}`, { cause })
  }
  const values: unknown = JSON.parse(raw)
  if (!Array.isArray(values) || values.length !== 11) throw new Error('専用actor集合が不正')
  return values.map((value: unknown) => {
    if (!value || typeof value !== 'object') throw new Error('actor形式が不正')
    const actor = value as Partial<Actor>
    if (typeof actor.code !== 'string' || typeof actor.userId !== 'number' || actor.userId <= 0
      || typeof actor.email !== 'string' || typeof actor.password !== 'string'
      || typeof actor.locale !== 'string') throw new Error('actor必須値が不正')
    return actor as Actor
  })
}

test('L1-M 管理API運用と既存batch・6言語通知bell（実検体）', async ({ browser }) => {
  test.setTimeout(9 * 60_000)
  const run = RUN_DIR
  const api = API_BASE_URL
  const baseURL = BASE_URL
  const actors = await actorsFromRunDirectory(run)
  const actor = (code: string): Actor => {
    const value = actors.find(item => item.code === code)
    if (!value) throw new Error(`専用actorが不足: ${code}`)
    return value
  }
  const contexts: BrowserContext[] = []
  const pages = new Map<string, Page>()
  const pageFor = async (code: string): Promise<Page> => {
    const existing = pages.get(code)
    if (existing) return existing
    const current = actor(code)
    const context = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] },
      locale: current.locale, timezoneId: 'Asia/Tokyo' })
    contexts.push(context)
    await context.addCookies([{ name: 'i18n_locale', value: current.locale, url: baseURL }])
    const page = await context.newPage()
    await loginViaApi(page, current, { apiBaseUrl: api })
    pages.set(code, page)
    return page
  }
  const snapshot = async (): Promise<State> => JSON.parse(await readFile(path.join(run, 'state.json'), 'utf8')) as State
  const command = async (name: string) => writeFile(path.join(run, 'controls', name), '', { flag: 'wx' })
  const delivered = (workflow: Workflow, phase: Phase, code: string) => workflow.deliveries[phase]?.[String(actor(code).userId)] ?? 0
  const waitState = async (check: (state: State) => boolean) => {
    await expect.poll(async () => check(await snapshot()), { timeout: 100_000, intervals: [500, 1000] }).toBe(true)
  }
  const endpoint = `${api}/api/v1/system-admin/module-events`
  try {
    await test.step('HTTP: 匿名401・認証済み非SYSTEM_ADMIN403（別tenantも秘匿）', async () => {
      const anonymous = await browser.newContext({ storageState: { cookies: [], origins: [] } })
      contexts.push(anonymous)
      const id = (await snapshot()).delayed.publications[0]
      expect(id).toBeTruthy()
      expect((await anonymous.request.get(endpoint)).status()).toBe(401)
      expect((await anonymous.request.post(`${endpoint}/${id}/resume`, { data: { reason: 'L1M' } })).status()).toBe(401)
      for (const code of ['member', 'admin-ja', 'org-admin', 'outsider']) {
        const page = await pageFor(code)
        expect((await page.request.get(endpoint)).status()).toBe(403)
        expect((await page.request.post(`${endpoint}/${id}/resume`, { data: { reason: 'L1M' } })).status()).toBe(403)
      }
    })
    await test.step('基盤: 短期正常成功0通知・継続遅延後の実listener完了', async () => {
      await waitState(state => state.fast.completed === 2 && state.fast.pending === 0)
      expect((await snapshot()).fast.noticeCount).toBe(0)
      await waitState(state => locales.every(locale => delivered(state.delayed, 'DELAYED', `admin-${locale}`) === 1)
        && delivered(state.delayed, 'DELAYED', 'member') === 1
        && delivered(state.organization, 'DELAYED', 'org-admin') === 1)
      expect((await snapshot()).delayed.recovered).toBe(0)
      await command('release-delayed')
      await waitState(state => state.delayed.pending === 0 && state.delayed.completed === 2
        && state.organization.pending === 0 && state.organization.completed === 2
        && locales.every(locale => delivered(state.delayed, 'RECOVERED', `admin-${locale}`) === 1))
    })
    await test.step('HTTP: 隔離ページ一覧・理由境界・1件再開・監査・実完了', async () => {
      await command('revoke-and-seed-quarantine')
      const system = await pageFor('system')
      let ids: string[] = []
      await waitState(state => {
        ids = state.quarantine.publications
        return ids.length === 2 && locales.every(locale => delivered(state.quarantine, 'QUARANTINED', `admin-${locale}`) === 1)
      })
      const first = await system.request.get(`${endpoint}?status=QUARANTINED&page=0&size=1`)
      const second = await system.request.get(`${endpoint}?status=QUARANTINED&page=1&size=1`)
      expect(first.status()).toBe(200); expect(second.status()).toBe(200)
      const firstText = await first.text(); const secondText = await second.text()
      expect(ids.filter(id => firstText.includes(id))).toHaveLength(1)
      expect(ids.filter(id => secondText.includes(id))).toHaveLength(1)
      expect(ids.find(id => firstText.includes(id))).not.toBe(ids.find(id => secondText.includes(id)))
      for (const text of [firstText, secondText]) {
        expect(text).not.toMatch(/serializedEvent|stackTrace|passwordHash|originalEmail/)
      }
      for (const query of ['size=0', 'size=101', 'page=-1', 'status=PENDING']) {
        expect((await system.request.get(`${endpoint}?${query}`)).status()).toBe(400)
      }
      for (const reason of [null, '', '   ', 'x'.repeat(501)]) {
        expect((await system.request.post(`${endpoint}/${ids[0]}/resume`, { data: { reason } })).status()).toBe(400)
      }
      expect((await system.request.post(`${endpoint}/00000000-0000-4000-8000-000000000001/resume`, { data: { reason: 'L1M' } })).status()).toBe(404)
      expect((await system.request.post(`${endpoint}/${ids[0]}/resume`, { data: { reason: '  x  ' } })).status()).toBe(204)
      await waitState(state => state.quarantine.completed === 1 && state.quarantine.pending === 1)
      expect((await snapshot()).quarantine.recovered).toBe(0)
      // 成功後はpublication/helperが削除されるため、完了済みIDは404。存在するIN_PROGRESSの競合409は後段で確認する。
      expect((await system.request.post(`${endpoint}/${ids[0]}/resume`, { data: { reason: 'L1M duplicate' } })).status()).toBe(404)
      expect((await system.request.post(`${endpoint}/${ids[1]}/resume`, { data: { reason: `  ${'x'.repeat(500)}  ` } })).status()).toBe(204)
      await waitState(state => state.quarantine.entered && state.quarantine.pending === 1)
      expect((await snapshot()).quarantine.recovered).toBe(0)
      expect((await snapshot()).quarantine.deliveries.RECOVERED ?? {}).toEqual({})
      expect((await system.request.post(`${endpoint}/${ids[1]}/resume`, { data: { reason: 'L1M duplicate' } })).status()).toBe(409)
      await command('release-last')
      await waitState(state => state.quarantine.completed === 2 && state.quarantine.pending === 0
        && state.auditCount === 2 && locales.every(locale => delivered(state.quarantine, 'RECOVERED', `admin-${locale}`) === 1)
        && delivered(state.quarantine, 'RECOVERED', 'member') === 1)
      const state = await snapshot()
      for (const code of ['revoked', 'outsider', 'org-admin']) {
        expect(delivered(state.quarantine, 'QUARANTINED', code)).toBe(0)
        expect(delivered(state.quarantine, 'RECOVERED', code)).toBe(0)
      }
      for (const phase of ['QUARANTINED', 'RECOVERED'] as const) {
        expect(delivered(state.quarantine, phase, 'member')).toBe(1)
        for (const locale of locales) expect(delivered(state.quarantine, phase, `admin-${locale}`)).toBe(1)
      }
    })
    await test.step('UI: 既存batch導線・詳細・再投入（個別再開の新画面なし）', async () => {
      const page = await pageFor('system')
      await page.goto('/'); await waitForHydration(page)
      const navigation = page.getByTestId('global-sidebar')
      await expect(navigation).toBeVisible()
      await navigation.locator('a[href="/system-admin"]').click()
      await expect(page).toHaveURL(/\/system-admin$/)
      // 一覧APIは登録バッチごとに直近ログを引くため数秒かかる（実測 約2.2秒、負荷下でexpect既定8秒を超えた）。
      // 固定待ちではなく、画面が実際に発行した一覧取得の完了に同期してから表を確認する。
      const listed = page.waitForResponse(value => value.request().method() === 'GET'
        && new URL(value.url()).pathname === '/api/v1/system-admin/batch')
      await page.locator('a[href="/system-admin/batches"]').first().click()
      await expect(page).toHaveURL(/\/system-admin\/batches/)
      expect((await listed).status()).toBe(200)
      await expect(page.locator('[data-test="batch-table"]')).toBeVisible()
      await page.locator('[data-test="detail-module-event-replay"]').click()
      await expect(page.getByRole('dialog')).toBeVisible()
      await page.getByRole('dialog').getByRole('button', { name: '閉じる', exact: true }).click()
      const response = page.waitForResponse(value => value.request().method() === 'POST'
        && value.url().includes('/system-admin/batch/module-event-replay/trigger'))
      await page.locator('[data-test="run-sync-module-event-replay"]').click()
      const result = await response
      expect([200, 202, 409]).toContain(result.status())
      const payload = await result.json() as { data?: { status?: string }; status?: string }
      const status = result.status() === 409 ? 'LOCKED' : payload.data?.status ?? payload.status
      expect(['LOCKED', 'ACCEPTED', 'COMPLETED']).toContain(status)
      const title = status === 'LOCKED' ? '他インスタンスがロック中です: module-event-replay'
        : status === 'ACCEPTED' ? 'バッチ起動を受け付けました: module-event-replay'
          : 'バッチ実行が完了しました: module-event-replay'
      await expect(page.getByText(title, { exact: true }).first()).toBeVisible()
      await page.screenshot({ path: test.info().outputPath('batch-ui.png'), fullPage: true })
    })
    await test.step('UI: 非SYSTEM_ADMIN・他tenantは管理入口なし、URL直打ちも拒否', async () => {
      // 常時描画のGlobalSidebarで正のケースと同じ入口を確認する。閉じたメニューの不在判定ではない。
      // system-admin middlewareの既存契約は403ページではなく/dashboardへの遷移と拒否トースト。
      for (const code of ['member', 'admin-ja', 'org-admin', 'outsider']) {
        const page = await pageFor(code)
        await page.goto('/'); await waitForHydration(page)
        const navigation = page.getByTestId('global-sidebar')
        await expect(navigation).toBeVisible()
        await expect(navigation.locator('a[href="/system-admin"]')).toHaveCount(0)
        await expect(page.locator('a[href="/system-admin/batches"]')).toHaveCount(0)
        await page.screenshot({ path: test.info().outputPath(`batch-entry-hidden-${code}.png`), fullPage: true })
        for (const target of ['/system-admin', '/system-admin/batches']) {
          await page.goto(target)
          await expect(page).toHaveURL(/\/dashboard$/)
          await expect(page.getByText('アクセスできません', { exact: true }).first()).toBeVisible()
          await expect(page.getByText('管理者権限が必要です', { exact: true }).first()).toBeVisible()
          await expect(page.locator('[data-test="batch-table"]')).toHaveCount(0)
          await expect(page.locator('[data-test="detail-module-event-replay"]')).toHaveCount(0)
          await expect(page.locator('[data-test="run-sync-module-event-replay"]')).toHaveCount(0)
          await page.screenshot({ path: test.info().outputPath(`batch-denied-${code}-${target.endsWith('batches') ? 'batches' : 'index'}.png`), fullPage: true })
        }
      }
    })
    await test.step('UI: 匿名で管理画面URLを直打ちするとログイン画面へ遷移する', async () => {
      // 未ログインは auth middleware が system-admin middleware より先に /login?redirect=... へ送る既存契約。
      const anonymous = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] },
        locale: 'ja', timezoneId: 'Asia/Tokyo' })
      contexts.push(anonymous)
      await anonymous.addCookies([{ name: 'i18n_locale', value: 'ja', url: baseURL }])
      const page = await anonymous.newPage()
      for (const target of ['/system-admin', '/system-admin/batches']) {
        await page.goto(target)
        await expect(page).toHaveURL(/\/login(?:\?|$)/, { timeout: 30_000 })
        expect(new URL(page.url()).searchParams.get('redirect')).toBe(target)
        await expect(page.getByRole('button', { name: 'ログイン', exact: true })).toBeVisible()
        await expect(page.locator('[data-test="batch-table"]')).toHaveCount(0)
        await expect(page.locator('[data-test="run-sync-module-event-replay"]')).toHaveCount(0)
        await page.screenshot({ path: test.info().outputPath(`batch-anonymous-${target.endsWith('batches') ? 'batches' : 'index'}.png`), fullPage: true })
      }
    })
    await test.step('UI: 既存bellクリックから通知一覧・6言語の永続配送文面', async () => {
      for (const locale of locales) {
        const page = await pageFor(`admin-${locale}`)
        await page.goto('/'); await waitForHydration(page)
        await page.locator('button').filter({ has: page.locator('.pi-bell') }).first().click()
        await page.locator('button.notif-row').filter({ has: page.locator('.pi-bell') }).click()
        await expect(page).toHaveURL(/\/notifications(?:\?.*)?$/)
        for (const phase of ['DELAYED', 'QUARANTINED', 'RECOVERED'] as const) {
          for (const message of messages[locale][phase]) await expect(page.getByText(message, { exact: true }).first()).toBeVisible()
        }
        await page.reload(); await waitForHydration(page)
        await expect(page.getByText(messages[locale].RECOVERED[0], { exact: true }).first()).toBeVisible()
        await page.screenshot({ path: test.info().outputPath(`bell-${locale}.png`), fullPage: true })
      }
    })
    await test.step('UI: 他tenantのbell・通知一覧に検体通知が混入しない', async () => {
      const page = await pageFor('outsider')
      await page.goto('/'); await waitForHydration(page)
      await page.locator('button').filter({ has: page.locator('.pi-bell') }).first().click()
      const loaded = page.waitForResponse(value => value.request().method() === 'GET'
        && new URL(value.url()).pathname === '/api/v1/notifications'
        && new URL(value.url()).searchParams.get('size') === '20')
      await page.locator('button.notif-row').filter({ has: page.locator('.pi-bell') }).click()
      expect((await loaded).status()).toBe(200)
      await expect(page).toHaveURL(/\/notifications(?:\?.*)?$/)
      await expect(page.getByText('通知はありません', { exact: true })).toBeVisible()
      await expect(page.locator('[data-notification-id]')).toHaveCount(0)
      for (const phase of ['DELAYED', 'QUARANTINED', 'RECOVERED'] as const) {
        for (const message of messages.ja[phase]) await expect(page.getByText(message, { exact: true })).toHaveCount(0)
      }
      await page.screenshot({ path: test.info().outputPath('bell-outsider.png'), fullPage: true })
    })
    await test.info().attach('fixture-final-state', { body: JSON.stringify(await snapshot()), contentType: 'application/json' })
  } finally {
    // tracesには生成資格情報（検体actorの使い捨てパスワード）が含まれ得る。公開しない。
    for (const context of contexts) await context.close()
  }
})
