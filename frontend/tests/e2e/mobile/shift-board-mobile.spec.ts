import { expect, test, type Locator, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { mockFeatureFlags } from '../helpers/feature-flags'
import {
  TEAM_ID,
  SCHEDULE_ID,
  buildSchedule,
  buildSlot,
  mockCatchAllApis,
  mockSchedule,
  mockSlots,
} from '../shifts/_helpers'

/**
 * CMP-261125-1336: シフトボード（/teams/:slug/shifts/:id/board）と概要カレンダー（/shift/:id）のスマホ表示。
 *
 * API 境界だけ stub した viewport 試験（モックなしの実認可・実タッチは /実機 で別途確認する）。
 * 観点:
 *  - 360 / 390 / 767 / 768px で、ヘッダー（見出し・ボタン）が縦書き状に折れない
 *  - ページ本体が横にはみ出さない（表の内側コンテナは横スクロールしてよい）。ボード本体・概要カレンダーとも
 *  - 各枠に「〇 / 〇人」、満員は「満員」。ステータスは i18n（PUBLISHED → 確定済み）
 *  - ADMIN（機能フラグ OFF で操作ボタン最大）は追加・外すが 44px 以上。一般 MEMBER は閲覧のみ
 *  - 追加ダイアログ: 既にいる人は出ず、時間が重なる人は理由付きで選べない。二度押しでも PATCH は 1 回
 *  - en ロケールでも崩れない
 */
const BOARD_URL = `/teams/${TEAM_ID}/shifts/${SCHEDULE_ID}/board`
const OVERVIEW_URL = `/shift/${SCHEDULE_ID}`
const LONG_POSITION = 'とても長い名前のポジション・レジ応援（繁忙時間帯）'
const LONG_NAME = '佐々木ミドルネーム・ヴェリーロングネーム太郎'

const members = [
  { userId: 11, displayName: LONG_NAME, avatarUrl: null, roleName: 'MEMBER' },
  { userId: 12, displayName: '鈴木 一郎', avatarUrl: null, roleName: 'MEMBER' },
  { userId: 13, displayName: '伊藤 さくら', avatarUrl: null, roleName: 'MEMBER' },
  { userId: 14, displayName: '高橋 優', avatarUrl: null, roleName: 'MEMBER' },
]

// 枠: A=受付 09-13（11 が入る・必要3）、B=長い名前のポジション 09-13（満員 1/1）、
//     C=ポジションなし 12-15（高橋 14 が入る・必要2）→ 14 は A に追加しようとすると時間が重なる
const slots = [
  buildSlot(501, { slotDate: '2027-03-09', startTime: '09:00:00', endTime: '13:00:00', positionId: 1, positionName: '受付', requiredCount: 3, assignedUserIds: [11] }),
  buildSlot(502, { slotDate: '2027-03-09', startTime: '09:00:00', endTime: '13:00:00', positionId: 2, positionName: LONG_POSITION, requiredCount: 1, assignedUserIds: [12] }),
  buildSlot(503, { slotDate: '2027-03-09', startTime: '12:00:00', endTime: '15:00:00', positionId: null, positionName: null, requiredCount: 2, assignedUserIds: [14] }),
]

const positions = [
  { id: 1, teamId: TEAM_ID, name: '受付', color: null, sortOrder: 0 },
  { id: 2, teamId: TEAM_ID, name: LONG_POSITION, color: null, sortOrder: 1 },
]

type Role = 'ADMIN' | 'MEMBER'

async function setup(page: Page, role: Role, locale: 'ja' | 'en' = 'ja', slotList = slots) {
  await page.addInitScript(([teamId, loc]) => {
    localStorage.setItem('currentUser', JSON.stringify({
      id: 1, email: 'board-mobile@example.invalid', fullName: 'ボード試験',
      profileImageUrl: null, systemRole: 'USER', timezone: 'Asia/Tokyo', locale: loc,
    }))
    localStorage.setItem('tokenExpiresAt', String(Date.now() + 60 * 60 * 1000))
    localStorage.setItem('currentScope', JSON.stringify({ type: 'team', id: String(teamId), name: 'ボード試験チーム' }))
    localStorage.setItem('i18n_redirected', loc)
  }, [TEAM_ID, locale] as const)
  await mockCatchAllApis(page)
  await mockFeatureFlags(page) // 自動割当フラグは OFF（停止中の表示込みで操作ボタンが最大数）
  const team = { id: TEAM_ID, slug: String(TEAM_ID), name: 'ボード試験チーム', nickname1: null, role }
  await page.route('**/api/v1/me/teams', route => route.fulfill({ json: { data: [team] } }))
  await page.route(`**/api/v1/teams/${TEAM_ID}`, route => route.fulfill({ json: { data: { ...team, numericId: TEAM_ID } } }))
  await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, route => route.fulfill({
    json: { data: role === 'ADMIN'
      ? { roleName: 'ADMIN', permissions: [], scopeAdmin: true }
      : { roleName: 'MEMBER', permissions: [], scopeAdmin: false } },
  }))
  await page.route('**/api/v1/shifts/positions?**', route => route.fulfill({ json: { data: positions } }))
  await mockSchedule(page, buildSchedule({ status: 'PUBLISHED', title: '来月シフト表（長いタイトルでも折り返す確認用）', startDate: '2027-03-08', endDate: '2027-03-14' }))
  await mockSlots(page, slotList)
  await page.route(`**/api/v1/teams/${TEAM_ID}/members**`, route => route.fulfill({ json: { data: members } }))
}

async function openBoard(page: Page) {
  await page.goto(BOARD_URL)
  await waitForHydration(page)
  await expect(page.getByTestId('shift-board-table')).toBeVisible()
}

/**
 * はみ出し・切り取りの検査。ボード root は `h-screen overflow-hidden` のため documentElement の scrollWidth では
 * 切り取りを検出できない。ヘッダー（見出し・ボタン・バッジ）と表の外枠の「右端が viewport 内」と、
 * ヘッダーが自分の幅に収まる（scrollWidth <= clientWidth）ことを実測する。空配列なら問題なし。
 */
const clippingProblems = (page: Page) =>
  page.evaluate(() => {
    const problems: string[] = []
    const vw = window.innerWidth
    const doc = document.documentElement
    if (doc.scrollWidth > doc.clientWidth) problems.push(`document scrollWidth ${doc.scrollWidth} > clientWidth ${doc.clientWidth}`)
    const within = (el: Element, label: string) => {
      const r = el.getBoundingClientRect()
      if (r.width === 0 && r.height === 0) return
      if (r.right > vw + 0.5) problems.push(`${label} の右端 ${r.right.toFixed(1)} > viewport ${vw}`)
      if (r.left < -0.5) problems.push(`${label} の左端 ${r.left.toFixed(1)} < 0`)
    }
    const header = document.querySelector('[data-testid="shift-board-header"]')
    if (header) {
      within(header, 'ヘッダー')
      if (header.scrollWidth > header.clientWidth) problems.push(`ヘッダー scrollWidth ${header.scrollWidth} > clientWidth ${header.clientWidth}`)
      header.querySelectorAll('button, a, h1, [class*="p-tag"], [class*="badge"]').forEach((el, i) => within(el, `ヘッダー内 ${el.tagName.toLowerCase()}#${i}`))
    }
    const scroller = document.querySelector('[data-testid="shift-board-scroll"]')
    if (scroller) within(scroller, '表の外枠')
    return problems
  })

async function box(target: Locator) {
  await expect(target).toBeVisible()
  const b = await target.boundingBox()
  expect(b).not.toBeNull()
  return b!
}

const VIEWPORTS = [360, 390, 767, 768] as const

for (const width of VIEWPORTS) {
  test.describe(`${width}px ADMIN`, () => {
    test.use({ viewport: { width, height: 800 } })

    test('ヘッダーが折れず、ページ本体が横にはみ出さず、各枠に「〇 / 〇人」が出る', async ({ page }) => {
      await setup(page, 'ADMIN')
      await openBoard(page)

      // 見出し: 縦書き状に折れない（1 行）
      const h1 = await box(page.getByRole('heading', { name: 'シフトボード', exact: true }))
      expect(h1.height, '見出しが 1 行').toBeLessThanOrEqual(48)
      expect(h1.width).toBeGreaterThan(80)

      // ステータスは i18n 表示（生の PUBLISHED を出さない）
      const header = page.getByTestId('shift-board-header')
      await expect(header.getByText('確定済み', { exact: true })).toBeVisible()
      await expect(header).not.toContainText('PUBLISHED')

      // ボタン群: 各ボタンが 1 行相当の高さ（縦書き状に折れていない）
      const buttons = header.getByRole('button')
      const count = await buttons.count()
      expect(count).toBeGreaterThanOrEqual(3)
      for (let i = 0; i < count; i++) {
        const button = buttons.nth(i)
        if (!(await button.isVisible())) continue
        const b = (await button.boundingBox())!
        expect(b.height, `ヘッダーのボタン ${i} が 1 行`).toBeLessThanOrEqual(60)
        expect(b.width, `ヘッダーのボタン ${i} が縦書き状に細くない`).toBeGreaterThan(30)
      }

      // ページ本体は横にはみ出さない。表の内側コンテナは横スクロールしてよい
      expect(await clippingProblems(page), 'はみ出し・切り取りが無い').toEqual([])

      // 各枠に「〇 / 〇人」。満員は「満員」
      const counts = page.getByTestId('shift-cell-count')
      await expect(counts).toHaveCount(3)
      await expect(counts.nth(0)).toContainText('1 / 3人')
      await expect(counts.nth(1)).toContainText('1 / 1人')
      await expect(counts.nth(1)).toContainText('満員')
      await expect(page.getByTestId('shift-board-col-none')).toHaveText('ポジションなし')
      await expect(page.getByTestId('shift-cell-503')).toBeVisible()
    })

    test('スマホ幅では表だけが横スクロールし、日付・時間帯の列は左に固定される', async ({ page }) => {
      test.skip(width > 390, '横スクロールが必要になるのはスマホ幅（表の最小幅が画面幅を超えるとき）だけ')
      await setup(page, 'ADMIN')
      await openBoard(page)
      const scroller = page.getByTestId('shift-board-scroll')
      const overflow = await scroller.evaluate(el => ({ sw: el.scrollWidth, cw: el.clientWidth }))
      expect(overflow.sw, '表が内側コンテナで横スクロールできる').toBeGreaterThan(overflow.cw)
      expect(await clippingProblems(page), 'はみ出し・切り取りが無い').toEqual([])
      await scroller.evaluate(el => { el.scrollLeft = el.scrollWidth })
      const dateCell = page.locator('tbody tr').first().locator('td').first()
      const [d, s] = [await box(dateCell), await box(scroller)]
      expect(Math.abs(d.x - s.x), '日付列が左端に固定されている').toBeLessThanOrEqual(2)
    })

    test('md 未満は追加・外す・ダイアログの選択肢が 44px 以上、md 以上はプールが常時表示', async ({ page }) => {
      await setup(page, 'ADMIN')
      await openBoard(page)
      const mobile = width < 768

      if (mobile) {
        // プールは常時表示せず、ヘッダーのボタンで開く
        await expect(page.getByTestId('member-pool-aside')).toBeHidden()
        const opener = page.getByTestId('member-pool-open')
        await expect(opener).toBeVisible()
        for (const target of [page.getByTestId('shift-cell-add').first(), page.getByTestId('shift-chip-remove').first()]) {
          const b = await box(target)
          expect.soft(b.width, 'タップ領域の幅').toBeGreaterThanOrEqual(44)
          expect.soft(b.height, 'タップ領域の高さ').toBeGreaterThanOrEqual(44)
        }
        await opener.click()
        const drawer = page.getByTestId('member-pool-drawer')
        await expect(drawer).toBeVisible()
        await expect(drawer.getByText('鈴木 一郎')).toHaveCount(0) // 鈴木は枠に入っているので未割当ではない
        await expect(drawer.getByText('伊藤 さくら')).toBeVisible()
        expect(await clippingProblems(page), 'はみ出し・切り取りが無い').toEqual([])
        await page.keyboard.press('Escape')
      } else {
        await expect(page.getByTestId('member-pool-aside')).toBeVisible()
        await expect(page.getByTestId('member-pool-open')).toBeHidden()
      }

      // 追加ダイアログ: 幅 min(90vw, 360px)、この枠にいる人は出ない、重なる人は理由付きで無効
      await page.getByTestId('shift-cell-add').first().click()
      const dialog = page.getByTestId('shift-add-member-dialog')
      await expect(dialog).toBeVisible()
      expect((await box(dialog)).width).toBeLessThanOrEqual(Math.min(width * 0.9, 360) + 1)
      await expect(dialog.getByText('メンバーを追加', { exact: true })).toBeVisible()
      await expect(page.getByTestId('shift-add-member-option-11')).toHaveCount(0)
      const overlap = page.getByTestId('shift-add-member-option-14')
      await expect(overlap).toBeDisabled()
      await expect(overlap.getByTestId('shift-add-member-overlap-reason')).toContainText('時間が重なる')
      const option = page.getByTestId('shift-add-member-option-13')
      await expect(option).toBeEnabled()
      if (mobile) expect((await box(option)).height).toBeGreaterThanOrEqual(44)
      expect(await clippingProblems(page), 'はみ出し・切り取りが無い').toEqual([])
    })
  })
}

test.describe('390px 一般 MEMBER（閲覧のみ）', () => {
  test.use({ viewport: { width: 390, height: 800 } })

  test('追加・外す・プール・自動割当を出さず、人数と名札は見える', async ({ page }) => {
    await setup(page, 'MEMBER')
    await openBoard(page)
    await expect(page.getByTestId('shift-cell-add')).toHaveCount(0)
    await expect(page.getByTestId('shift-chip-remove')).toHaveCount(0)
    await expect(page.getByTestId('member-pool-open')).toHaveCount(0)
    await expect(page.getByTestId('member-pool-aside')).toHaveCount(0)
    await expect(page.getByTestId('auto-assign-button')).toHaveCount(0)
    await expect(page.locator('[draggable="true"]')).toHaveCount(0)
    await expect(page.getByTestId('shift-cell-count').first()).toContainText('/ 3人')
    await expect(page.getByTestId('shift-cell-501')).toContainText(LONG_NAME) // 長い氏名は切らずに折り返す
    expect(await clippingProblems(page), 'はみ出し・切り取りが無い').toEqual([])
  })

  test('割当を伏せた枠は人数を出さず中立表示にする', async ({ page }) => {
    await setup(page, 'MEMBER', 'ja', [
      buildSlot(501, { positionId: 1, positionName: '受付', requiredCount: 3, assignmentMasked: true }),
    ])
    await openBoard(page)
    await expect(page.getByTestId('shift-cell-masked')).toHaveText('割当は非公開')
    await expect(page.getByTestId('shift-cell-501')).not.toContainText('/ 3人')
  })
})

test.describe('390px 追加の二度押し', () => {
  test.use({ viewport: { width: 390, height: 800 } })

  test('二度押ししても PATCH は 1 回で、409 の後は枠を再取得して次の操作が成功する', async ({ page }) => {
    await setup(page, 'ADMIN')
    let patches = 0
    let slotReads = 0
    let respondConflict = true
    await page.route(`**/api/v1/shifts/schedules/${SCHEDULE_ID}/slots**`, async (route) => {
      slotReads++
      await route.fulfill({ json: { data: slots } })
    })
    await page.route('**/api/v1/shifts/slots/501/assignments', async (route) => {
      patches++
      await new Promise(resolve => setTimeout(resolve, 400))
      if (respondConflict) {
        await route.fulfill({ status: 409, json: { error: { code: 'SHIFT_999', message: '競合' } } })
      } else {
        await route.fulfill({ json: { data: { ...slots[0], version: 1, assignedUserIds: [11, 13] } } })
      }
    })
    await openBoard(page)
    const readsBefore = slotReads

    await page.getByTestId('shift-cell-add').first().click()
    await page.getByTestId('shift-add-member-option-13').dblclick()
    await expect.poll(() => patches).toBe(1)
    // 409 の後は枠を再取得する
    await expect.poll(() => slotReads).toBeGreaterThan(readsBefore)

    respondConflict = false
    const option = page.getByTestId('shift-add-member-option-13')
    await expect(option).toBeEnabled()
    await option.click()
    await expect.poll(() => patches).toBe(2)
    await expect(page.getByTestId('shift-add-member-dialog')).toBeHidden()
  })
})

test.describe('en ロケール', () => {
  test.use({ viewport: { width: 360, height: 800 } })

  test('英語表示でもヘッダーが折れず、はみ出さない', async ({ page }) => {
    await setup(page, 'ADMIN', 'en')
    await openBoard(page)
    const header = page.getByTestId('shift-board-header')
    await expect(header.getByText('Published', { exact: true })).toBeVisible()
    expect(await clippingProblems(page), 'はみ出し・切り取りが無い').toEqual([])
    const buttons = header.getByRole('button')
    for (let i = 0; i < await buttons.count(); i++) {
      if (!(await buttons.nth(i).isVisible())) continue
      expect((await buttons.nth(i).boundingBox())!.height).toBeLessThanOrEqual(60)
    }
    await expect(page.getByTestId('shift-cell-count').first()).toContainText('1 / 3')
    await expect(page.getByTestId('shift-board-table').locator('thead th').first()).toHaveText('Date / time')
    await expect(page.getByTestId('shift-board-col-none')).toHaveText('No position')
  })
})

for (const width of VIEWPORTS) {
  test.describe(`${width}px 概要カレンダー`, () => {
    test.use({ viewport: { width, height: 800 } })

    test('長いポジション名でも札（1/3 等）が切れず、ページ・カレンダー内側ともはみ出さない', async ({ page }) => {
      await setup(page, 'ADMIN')
      await page.goto(OVERVIEW_URL)
      await waitForHydration(page)
      const calendar = page.getByTestId('shift-overview-calendar')
      await expect(calendar).toBeVisible()
      expect(await clippingProblems(page), 'はみ出し・切り取りが無い').toEqual([])
      const inner = await calendar.evaluate(el => ({ sw: el.scrollWidth, cw: el.clientWidth }))
      expect(inner.sw, 'カレンダー内側コンテナも横にはみ出さない').toBeLessThanOrEqual(inner.cw)
      // 札はコンテナ内に全体が収まる
      const area = await box(calendar)
      const badges = calendar.locator('a').getByText(/^\d+\/\d+$/)
      expect(await badges.count()).toBeGreaterThan(0)
      for (let i = 0; i < await badges.count(); i++) {
        const b = await box(badges.nth(i))
        expect.soft(b.x + b.width, `札 ${i} が右端で切れない`).toBeLessThanOrEqual(area.x + area.width + 1)
        expect.soft(b.x, `札 ${i} が左端で切れない`).toBeGreaterThanOrEqual(area.x - 1)
      }
    })
  })
}
