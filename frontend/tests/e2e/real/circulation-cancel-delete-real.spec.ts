import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  ACCOUNTS,
  ORG_SCOPE,
  addOrgMember,
  removeOrgMemberIfAdded,
  TEAM_SCOPE,
  api,
  cleanupCirculations,
  createCirculation,
  dismissPermissionSetupDialogIfShown,
  getStatus,
  openSession,
  type Scope,
  type Session,
} from '../helpers/circulation-real'

/**
 * 実機E2E: CMP-260930-1537 回覧の取消・削除 UI（チーム版・組織版）
 *
 * モックなし・実BE・実FE。取消・削除は必ず画面（一覧のボタン→確認ダイアログ→実行）で行い、
 * API は前提データ作成・状態確認・後始末にだけ使う。
 *  - ADMIN: 取消（DRAFT/ACTIVE）・削除（DRAFT/CANCELLED）ボタンが出る。状態条件（ACTIVE に削除は出ない）。
 *  - MEMBER: ボタンが出ない。
 *  - 他チーム／他組織 ADMIN・非所属: 画面で一覧に出ず、API 直接操作も 403 で何も変わらない。
 * 前提データは RUN_TAG 付きで作り、afterAll で自分が作った分だけ消す。
 */
const RUN_TAG = `R${Date.now().toString(36)}`

type Target = {
  label: string
  scope: Scope
  path: string
  admin: string
  member: string
  otherAdmin: string
}

const TARGETS: Target[] = [
  {
    label: 'チーム',
    scope: TEAM_SCOPE,
    path: '/teams/fc-u-18/circulation',
    admin: ACCOUNTS.teamAdmin,
    member: ACCOUNTS.teamMemberOrgAdmin,
    otherAdmin: ACCOUNTS.otherTeamAdmin,
  },
  {
    label: '組織',
    scope: ORG_SCOPE,
    path: '/organizations/e2e/circulation',
    admin: ACCOUNTS.teamMemberOrgAdmin,
    member: ACCOUNTS.orgMember,
    otherAdmin: ACCOUNTS.otherOrgAdmin,
  },
]

async function openList(page: Page, path: string) {
  await page.goto(path, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await expect(page.getByRole('heading', { name: '回覧板', exact: true })).toBeVisible()
  await dismissPermissionSetupDialogIfShown(page)
}

/** 確認ダイアログを開いて承認する。タイトル・本文に対象の回覧名が出ることも確認する。 */
async function confirmDialog(page: Page, header: string, accept: string, title: string) {
  const dialog = page.getByRole('alertdialog').or(page.getByRole('dialog')).filter({ hasText: header })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText(title)
  await dialog.getByRole('button', { name: accept }).click()
}

for (const T of TARGETS) {
  test.describe.serial(`CMP-260930-1537 ${T.label}版 回覧の取消・削除（実機）`, () => {
    let admin: Session
    let member: Session
    let otherAdmin: Session
    let outsider: Session
    const created: number[] = []
    let orgMembership: { added: boolean, inviteId?: number } = { added: false }
    let activeId: number
    let draftId: number
    let draft2Id: number
    const titleActive = `${RUN_TAG}-${T.label}-ACTIVE`
    const titleDraft = `${RUN_TAG}-${T.label}-DRAFT`
    const titleDraft2 = `${RUN_TAG}-${T.label}-DRAFT2`
    const scopePath = `/api/v1/${T.scope.kind}/${T.scope.numericId}/circulations`

    test.beforeAll(async ({ browser }) => {
      admin = await openSession(browser, T.admin)
      member = await openSession(browser, T.member)
      otherAdmin = await openSession(browser, T.otherAdmin)
      outsider = await openSession(browser, ACCOUNTS.outsider)
      if (T.scope === ORG_SCOPE) orgMembership = await addOrgMember(admin, member)
      activeId = await createCirculation(admin, T.scope, titleActive, [admin.userId, member.userId], true)
      draftId = await createCirculation(admin, T.scope, titleDraft, [admin.userId, member.userId], false)
      draft2Id = await createCirculation(admin, T.scope, titleDraft2, [admin.userId, member.userId], false)
      created.push(activeId, draftId, draft2Id)
    })

    test.afterAll(async () => {
      try {
        await cleanupCirculations(admin, T.scope, created)
        await removeOrgMemberIfAdded(admin, member, orgMembership)
      }
      finally {
        for (const s of [admin, member, otherAdmin, outsider]) await s?.ctx.close()
      }
    })

    test(`C-ADMIN-状態条件: ADMIN の一覧で ACTIVE は取消のみ、DRAFT は取消と削除が出る`, async () => {
      await openList(admin.page, T.path)
      await expect(admin.page.getByText(titleActive, { exact: true })).toBeVisible()
      await expect(admin.page.getByTestId(`circulation-cancel-${activeId}`)).toBeVisible()
      await expect(admin.page.getByTestId(`circulation-delete-${activeId}`)).toHaveCount(0)
      await expect(admin.page.getByTestId(`circulation-cancel-${draftId}`)).toBeVisible()
      await expect(admin.page.getByTestId(`circulation-delete-${draftId}`)).toBeVisible()
    })

    test(`C-ADMIN-取消→削除: ACTIVE を画面で取消すると CANCELLED になり、続けて画面で削除すると一覧から消える`, async () => {
      const p = admin.page
      await openList(p, T.path)
      const row = p.locator('div.rounded-xl').filter({ hasText: titleActive })
      await expect(row).toContainText('回覧中')

      // 取消: ボタン → 確認ダイアログ（キャンセル側でまず何も変わらない）→ 実行
      await p.getByTestId(`circulation-cancel-${activeId}`).click()
      const dlg = p.getByRole('alertdialog').or(p.getByRole('dialog')).filter({ hasText: '回覧を取り消しますか？' })
      await expect(dlg).toBeVisible()
      await dlg.getByRole('button', { name: '戻る' }).click()
      await expect(dlg).toBeHidden()
      expect((await getStatus(admin, T.scope, activeId)).circulationStatus).toBe('ACTIVE')

      await p.getByTestId(`circulation-cancel-${activeId}`).click()
      const cancelResponse = p.waitForResponse(
        r => r.url().includes(`/circulations/${activeId}/cancel`) && r.request().method() === 'POST',
      )
      await confirmDialog(p, '回覧を取り消しますか？', '取り消す', titleActive)
      expect((await cancelResponse).status()).toBe(200)

      // 一覧が更新されて「キャンセル」表示になり、取消ボタンは消え、削除ボタンが出る
      await expect(row).toContainText('キャンセル')
      await expect(p.getByTestId(`circulation-cancel-${activeId}`)).toHaveCount(0)
      await expect(p.getByTestId(`circulation-delete-${activeId}`)).toBeVisible()
      expect((await getStatus(admin, T.scope, activeId)).circulationStatus).toBe('CANCELLED')

      // 削除: CANCELLED を画面で削除 → 一覧から消え、BE でも 404
      // トースト・ダイアログのフェード完了を待ってから証拠画像を撮る
      await expect(p.getByRole('dialog')).toHaveCount(0)
      await p.waitForTimeout(800)
      await p.screenshot({ path: `../docs/evidence/CMP-260930-1537/${T.scope.kind}-cancelled.png` })
      const deleteResponse = p.waitForResponse(
        r => r.url().includes(`/circulations/${activeId}`) && r.request().method() === 'DELETE',
      )
      await p.getByTestId(`circulation-delete-${activeId}`).click()
      await confirmDialog(p, '回覧を削除しますか？', '削除する', titleActive)
      expect((await deleteResponse).status()).toBe(204)
      await expect(p.getByText(titleActive, { exact: true })).toHaveCount(0)
      expect((await getStatus(admin, T.scope, activeId)).status).toBe(404)

      // 再読み込みしても戻らない（永続化）
      await openList(p, T.path)
      await expect(p.getByText(titleActive, { exact: true })).toHaveCount(0)
      await expect(p.getByText(titleDraft, { exact: true })).toBeVisible()
    })

    test(`C-ADMIN-DRAFT削除: DRAFT を画面で削除すると一覧から消える（同じ一覧の他の回覧は残る）`, async () => {
      const p = admin.page
      await openList(p, T.path)
      const deleteResponse = p.waitForResponse(
        r => r.url().includes(`/circulations/${draftId}`) && r.request().method() === 'DELETE',
      )
      await p.getByTestId(`circulation-delete-${draftId}`).click()
      await confirmDialog(p, '回覧を削除しますか？', '削除する', titleDraft)
      expect((await deleteResponse).status()).toBe(204)
      await expect(p.getByText(titleDraft, { exact: true })).toHaveCount(0)
      await expect(p.getByText(titleDraft2, { exact: true })).toBeVisible()
      expect((await getStatus(admin, T.scope, draftId)).status).toBe(404)
    })

    test(`C-ADMIN-DRAFT取消: DRAFT を画面で取消すると CANCELLED（削除のみ残る）`, async () => {
      const p = admin.page
      await openList(p, T.path)
      const row = p.locator('div.rounded-xl').filter({ hasText: titleDraft2 })
      await expect(row).toContainText('下書き')
      await p.getByTestId(`circulation-cancel-${draft2Id}`).click()
      await confirmDialog(p, '回覧を取り消しますか？', '取り消す', titleDraft2)
      await expect(row).toContainText('キャンセル')
      await expect(p.getByTestId(`circulation-cancel-${draft2Id}`)).toHaveCount(0)
      await expect(p.getByTestId(`circulation-delete-${draft2Id}`)).toBeVisible()
      expect((await getStatus(admin, T.scope, draft2Id)).circulationStatus).toBe('CANCELLED')
    })

    test(`C-MEMBER: MEMBER は回覧を見られるが取消・削除・作成の導線が出ず、API 直接でも 403`, async () => {
      // 前提: 取消・削除の対象になる回覧を 1 件作り直す（前のテストで消費したため）
      const memberTitle = `${RUN_TAG}-${T.label}-MEMBER確認`
      const id = await createCirculation(admin, T.scope, memberTitle, [admin.userId, member.userId], true)
      created.push(id)
      const p = member.page
      await openList(p, T.path)
      await expect(p.getByText(memberTitle, { exact: true })).toBeVisible()
      await expect(p.getByTestId(`circulation-cancel-${id}`)).toHaveCount(0)
      await expect(p.getByTestId(`circulation-delete-${id}`)).toHaveCount(0)
      await expect(p.getByRole('button', { name: '取消' })).toHaveCount(0)
      await expect(p.getByRole('button', { name: '削除' })).toHaveCount(0)
      await expect(p.getByRole('button', { name: '回覧作成' })).toHaveCount(0)
      await p.screenshot({ path: `../docs/evidence/CMP-260930-1537/${T.scope.kind}-member.png` })

      // URL 直打ち相当（FE の出し分けではなく BE が弾くこと）
      const c = await api(member, 'POST', `${scopePath}/${id}/cancel`)
      expect(c.status).toBe(403)
      const d = await api(member, 'DELETE', `${scopePath}/${id}`)
      expect(d.status).toBe(403)
      expect((await getStatus(admin, T.scope, id)).circulationStatus).toBe('ACTIVE')
    })

    for (const who of ['otherAdmin', 'outsider'] as const) {
      test(`C-OTHER-TENANT(${who}): 他${T.label}の ADMIN／非所属は一覧に出ず、API 直接の取消・削除も 403 で何も変わらない`, async () => {
        const s = who === 'otherAdmin' ? otherAdmin : outsider
        const id = await createCirculation(admin, T.scope, `${RUN_TAG}-${T.label}-他テナント-${who}`, [admin.userId, member.userId], true)
        created.push(id)

        // 画面: URL 直打ち → 一覧は取得できず（403）、回覧名もボタンも出ない
        const listResponse = s.page.waitForResponse(
          r => r.url().includes('/circulations?page=0&size=20') && r.request().method() === 'GET',
          { timeout: 60_000 },
        )
        await s.page.goto(T.path, { waitUntil: 'domcontentloaded' })
        expect((await listResponse).status()).toBe(403)
        await waitForHydration(s.page)
        await expect(s.page.getByText('情報を取得できませんでした')).toBeVisible()
        await expect(s.page.getByText(RUN_TAG)).toHaveCount(0)
        await expect(s.page.getByTestId(`circulation-cancel-${id}`)).toHaveCount(0)
        await expect(s.page.getByTestId(`circulation-delete-${id}`)).toHaveCount(0)
        if (who === 'outsider') await s.page.screenshot({ path: `../docs/evidence/CMP-260930-1537/${T.scope.kind}-${who}.png` })

        // API 直接: 取消・削除・詳細とも拒否され、状態は ACTIVE のまま
        expect((await api(s, 'POST', `${scopePath}/${id}/cancel`)).status).toBe(403)
        expect((await api(s, 'DELETE', `${scopePath}/${id}`)).status).toBe(403)
        expect((await api(s, 'GET', `${scopePath}/${id}`)).status).toBe(403)
        expect((await getStatus(admin, T.scope, id)).circulationStatus).toBe('ACTIVE')
      })
    }
  })
}
