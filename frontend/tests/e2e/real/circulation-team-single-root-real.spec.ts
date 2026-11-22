import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  ACCOUNTS,
  TEAM,
  TEAM_SCOPE,
  cleanupCirculations,
  createCirculation,
  dismissPermissionSetupDialogIfShown,
  openSession,
  type Session,
} from '../helpers/circulation-real'

/**
 * 実機E2E: CMP-261013-0821 チームの回覧画面の単一ルート（本文が表示される・Nuxt 警告が出ない）
 *
 * モックなし・実BE・実FE。チーム fc-u-18 の回覧画面 /teams/fc-u-18/circulation を、
 *  - ADMIN（e2e-dummy-1）: 1440 幅・390 幅
 *  - MEMBER（e2e-user）
 *  - 他チーム ADMIN（e2e-dummy-6）・非所属（e2e-outsider）: URL 直打ち
 * で開き、本文（一覧）が見えること・page-fade が opacity:0 で固着しないこと・
 * ブラウザコンソールに "single root node" 系の警告が出ないことを確認する。
 * 前提データ（回覧 1 件）は API で作り、afterAll で自分が作った分だけ消す。
 */
const RUN_TAG = `R${Date.now().toString(36)}`
const TITLE = `${RUN_TAG}-単一ルート確認`
const PATH = `/teams/${TEAM.slug}/circulation`
const ROOT_WARNING = /single root node|non-element root node|Extraneous non-props|Component inside <Transition>/i

test.describe.serial('CMP-261013-0821 チーム回覧画面の単一ルート（実機）', () => {
  let admin: Session
  let member: Session
  let outsider: Session
  let otherAdmin: Session
  let circulationId: number

  test.beforeAll(async ({ browser }) => {
    admin = await openSession(browser, ACCOUNTS.teamAdmin)
    member = await openSession(browser, ACCOUNTS.teamMemberOrgAdmin)
    outsider = await openSession(browser, ACCOUNTS.outsider)
    otherAdmin = await openSession(browser, ACCOUNTS.otherTeamAdmin)
    circulationId = await createCirculation(admin, TEAM_SCOPE, TITLE, [member.userId], true)
  })

  test.afterAll(async () => {
    try {
      if (circulationId) await cleanupCirculations(admin, TEAM_SCOPE, [circulationId])
    }
    finally {
      for (const s of [admin, member, outsider, otherAdmin]) await s?.ctx.close()
    }
  })

  /** 本文（見出し）から祖先を辿り、opacity が 0（page-fade の固着）の要素が無いことを確認する。 */
  async function expectNoFadeStuck(page: Page) {
    await expect.poll(async () => page.evaluate(() => {
      const h = Array.from(document.querySelectorAll('h1,h2,h3')).find(e => e.textContent?.includes('回覧板'))
      if (!h) return 'heading-missing'
      for (let el: Element | null = h; el; el = el.parentElement) {
        if (Number(getComputedStyle(el).opacity) < 0.99) return `opacity=${getComputedStyle(el).opacity} <${el.tagName.toLowerCase()} class="${el.className}">`
      }
      return 'ok'
    }), { timeout: 15_000 }).toBe('ok')
  }

  async function openAndCollect(s: Session, width: number, height: number) {
    const warnings: string[] = []
    s.page.on('console', (m) => {
      if (['warning', 'error'].includes(m.type())) warnings.push(`[${m.type()}] ${m.text()}`)
    })
    await s.page.setViewportSize({ width, height })
    await s.page.goto(PATH, { waitUntil: 'domcontentloaded' })
    await waitForHydration(s.page)
    await dismissPermissionSetupDialogIfShown(s.page)
    return warnings
  }

  for (const [label, width, height] of [['1440', 1440, 900], ['390', 390, 844]] as const) {
    test(`R1-ADMIN-${label}: ADMIN が ${label} 幅で本文（一覧）を見られ、単一ルート警告が出ない`, async () => {
      const warnings = await openAndCollect(admin, width, height)
      await expect(admin.page.getByRole('heading', { name: '回覧板', exact: true })).toBeVisible()
      await expect(admin.page.getByText(TITLE)).toBeVisible()
      await expectNoFadeStuck(admin.page)
      // 管理者の導線（回覧作成）も本文に含まれる（成功パス全体が描画されている）
      await expect(admin.page.getByRole('button', { name: '回覧作成' })).toBeVisible()
      // コンソール警告（一覧は固着していないので、再描画後も一定時間待って確認）
      await admin.page.waitForTimeout(1500)
      const rootWarnings = warnings.filter(w => ROOT_WARNING.test(w))
      expect(rootWarnings, `単一ルート警告: ${rootWarnings.join('\n')}`).toEqual([])
      await admin.page.screenshot({
        path: `../docs/evidence/CMP-261013-0821/admin-${label}.png`,
      })
      console.log(`[${label}] console warnings/errors (${warnings.length}):\n${warnings.join('\n') || '(none)'}`)
    })
  }

  test('R1-MEMBER: MEMBER は回覧一覧を見られ、管理操作（作成・取消・削除）は出ない', async () => {
    const warnings = await openAndCollect(member, 1440, 900)
    await expect(member.page.getByRole('heading', { name: '回覧板', exact: true })).toBeVisible()
    await expect(member.page.getByText(TITLE)).toBeVisible()
    await expectNoFadeStuck(member.page)
    await expect(member.page.getByRole('button', { name: '回覧作成' })).toHaveCount(0)
    await expect(member.page.getByTestId(`circulation-cancel-${circulationId}`)).toHaveCount(0)
    await expect(member.page.getByTestId(`circulation-delete-${circulationId}`)).toHaveCount(0)
    expect(warnings.filter(w => ROOT_WARNING.test(w))).toEqual([])
  })

  for (const [label, pick] of [
    ['R1-OTHER-TEAM-ADMIN', () => otherAdmin],
    ['R1-OUTSIDER', () => outsider],
  ] as const) {
    test(`${label}: 他チーム ADMIN／非所属は一覧に出ず、URL 直打ちでも本文が見えない`, async () => {
      const s = pick()
      const listResponse = s.page.waitForResponse(
        r => r.url().includes(`/api/v1/teams/${TEAM.slug}/circulations`) && r.request().method() === 'GET',
        { timeout: 60_000 },
      )
      await s.page.goto(PATH, { waitUntil: 'domcontentloaded' })
      const res = await listResponse
      expect(res.status()).toBe(403)
      await waitForHydration(s.page)
      await expect(s.page.getByText('情報を取得できませんでした')).toBeVisible()
      await expect(s.page.getByText(TITLE)).toHaveCount(0)
      await expect(s.page.getByRole('button', { name: '回覧作成' })).toHaveCount(0)
      console.log(`[${label}] 画面本文: ${(await s.page.locator('body').innerText()).replace(/\n+/g, ' | ').slice(-160)}`)
    })
  }
})
