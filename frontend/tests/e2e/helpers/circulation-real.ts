import type { Browser, BrowserContext, Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

/**
 * 回覧の実機E2E（CMP-261013-0821 / CMP-260930-1537）共通ヘルパー。
 *
 * API は「ログイン・前提データ作成・状態確認・後始末」にだけ使う。対象操作（表示・取消・削除）は必ず画面で行う。
 * seed アカウントの設定（言語・タイムゾーン等）は変更しない（共有環境）。
 */
export const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
export const PASSWORD = 'TestPass2026!'

/** seed の登場人物。ロールは backend/scripts/seed-e2e-data.js のとおり。 */
export const ACCOUNTS = {
  /** チーム fc-u-18（数値 ID 1）の ADMIN */
  teamAdmin: 'e2e-dummy-1@test.mannschaft.local',
  /** チーム fc-u-18 の MEMBER / 組織 e2e（数値 ID 11）の ADMIN */
  teamMemberOrgAdmin: 'e2e-user@test.mannschaft.local',
  /** どの組織にも未所属の seed ユーザー。組織 e2e の MEMBER として一時的に加え、終了時に外す（seed に組織 MEMBER が居ないため） */
  orgMember: 'e2e-dummy-20@test.mannschaft.local',
  /** 他チーム（fc-u-15）の ADMIN */
  otherTeamAdmin: 'e2e-dummy-6@test.mannschaft.local',
  /** 他組織（数値 ID 5）の ADMIN */
  otherOrgAdmin: 'e2e-dummy-10@test.mannschaft.local',
  /** どのチーム・組織にも非所属 */
  outsider: 'e2e-outsider@test.mannschaft.local',
} as const

export const TEAM = { slug: 'fc-u-18', numericId: 1 }
export const ORG = { slug: 'e2e', numericId: 11 }

export type Session = { ctx: BrowserContext, page: Page, userId: number, email: string }

export async function openSession(
  browser: Browser,
  email: string,
  viewport = { width: 1440, height: 900 },
): Promise<Session> {
  // ログインは email+IP で 1 分 10 回の制限があり、他の担当と共有なので 429 は待って再試行する。
  let lastError: unknown
  for (let attempt = 0; attempt < 6; attempt++) {
    const ctx = await browser.newContext({ viewport, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
    const page = await ctx.newPage()
    try {
      await loginViaApi(page, { email, password: PASSWORD }, { apiBaseUrl: API_BASE })
      const me = await page.request.get(`${API_BASE}/api/v1/users/me`)
      const userId = (await me.json()).data.id as number
      return { ctx, page, userId, email }
    }
    catch (e) {
      lastError = e
      await ctx.close()
      if (String(e).includes('429')) await new Promise(r => setTimeout(r, 20_000))
      else throw e
    }
  }
  throw lastError
}

export async function api(
  s: Session,
  method: 'GET' | 'POST' | 'DELETE',
  path: string,
  data?: unknown,
): Promise<{ status: number, body: { data?: unknown, error?: unknown } | null }> {
  const res = await s.page.request.fetch(`${API_BASE}${path}`, { method, data })
  const text = await res.text()
  let body: { data?: unknown, error?: unknown } | null = null
  try { body = text ? JSON.parse(text) : null } catch { body = { error: text } }
  return { status: res.status(), body }
}

/** レスポンスの data を型付きで取り出す。 */
export function dataOf<T>(r: { body: { data?: unknown } | null }): T {
  return r.body?.data as T
}

export type Scope = { kind: 'teams' | 'organizations', slug: string, numericId: number }
export const TEAM_SCOPE: Scope = { kind: 'teams', slug: TEAM.slug, numericId: TEAM.numericId }
export const ORG_SCOPE: Scope = { kind: 'organizations', slug: ORG.slug, numericId: ORG.numericId }

/** 回覧を API で作成する（前提データ）。activate=true で ACTIVE、false で DRAFT のまま。 */
export async function createCirculation(
  s: Session,
  scope: Scope,
  title: string,
  recipientUserIds: number[],
  activate: boolean,
): Promise<number> {
  const created = await api(s, 'POST', `/api/v1/${scope.kind}/${scope.slug}/circulations`, {
    title,
    body: 'E2E 前提データ',
    circulationMode: 'SEQUENTIAL',
    priority: 'NORMAL',
    recipients: recipientUserIds.map((userId, i) => ({ userId, sortOrder: i + 1 })),
  })
  if (created.status !== 201) throw new Error(`回覧作成失敗: ${created.status} ${JSON.stringify(created.body)}`)
  const id = dataOf<{ id: number }>(created).id
  if (activate) {
    const act = await api(s, 'POST', `/api/v1/${scope.kind}/${scope.slug}/circulations/${id}/activate`)
    if (act.status !== 200) throw new Error(`回覧開始失敗: ${act.status} ${JSON.stringify(act.body)}`)
  }
  return id
}

export async function getStatus(s: Session, scope: Scope, id: number): Promise<{ status: number, circulationStatus?: string }> {
  // 詳細 EP は CANCELLED の文書を ADMIN にも 403（VISIBILITY_001）で返す（別件・証拠文書に記録）ため、
  // 状態確認は一覧 EP で行う。一覧に無ければ 404 相当（削除済み）。
  const r = await api(s, 'GET', `/api/v1/${scope.kind}/${scope.numericId}/circulations?page=0&size=100`)
  if (r.status !== 200) return { status: r.status }
  const item = dataOf<Array<{ id: number, status: string }>>(r).find(d => d.id === id)
  return item ? { status: 200, circulationStatus: item.status } : { status: 404 }
}

/** 自分が作った回覧だけを後始末する（ACTIVE は取消してから削除）。既に無ければ何もしない。 */
export async function cleanupCirculations(s: Session, scope: Scope, ids: number[]): Promise<void> {
  for (const id of ids) {
    const cur = await getStatus(s, scope, id)
    if (cur.status === 404) continue
    if (cur.circulationStatus === 'ACTIVE') {
      await api(s, 'POST', `/api/v1/${scope.kind}/${scope.numericId}/circulations/${id}/cancel`)
    }
    await api(s, 'DELETE', `/api/v1/${scope.kind}/${scope.numericId}/circulations/${id}`)
  }
}

/**
 * 管理者が初めてスコープ画面を開くと「メンバーの権限を初期設定」ダイアログ（MemberPermissionSetupDialog）が出て
 * 画面を覆う。利用者と同じく「あとで決める」で閉じる（sessionStorage にだけ記録され、BE の設定は変わらない）。
 * 出ない場合（設定済み・MEMBER 等）は何もしない。
 */
export async function dismissPermissionSetupDialogIfShown(page: Page): Promise<void> {
  const later = page.getByRole('button', { name: 'あとで決める' })
  try {
    await later.waitFor({ state: 'visible', timeout: 4_000 })
  }
  catch (e) {
    if ((e as Error).name !== 'TimeoutError') throw e
    return
  }
  await later.click()
  await later.waitFor({ state: 'hidden' })
}

/**
 * 組織の MEMBER を用意する（seed に組織 e2e の MEMBER が居ないため）。招待トークン（roleId=4=MEMBER・1 回限り）で参加させる。
 * 既に所属している場合は何もしない。戻り値を removeOrgMemberIfAdded に渡して元に戻す。
 */
export async function addOrgMember(admin: Session, member: Session): Promise<{ added: boolean, inviteId?: number }> {
  const members = await api(admin, 'GET', `/api/v1/organizations/${ORG.slug}/members?size=200`)
  if (dataOf<Array<{ userId: number }>>(members).some(m => m.userId === member.userId)) return { added: false }
  const invite = await api(admin, 'POST', `/api/v1/organizations/${ORG.slug}/invite-tokens`, { roleId: 4, maxUses: 1, expiresIn: '1d' })
  if (invite.status !== 201) throw new Error(`招待トークン作成失敗: ${invite.status} ${JSON.stringify(invite.body)}`)
  const issued = dataOf<{ id: number, token: string }>(invite)
  const inviteId = issued.id
  const join = await api(member, 'POST', `/api/v1/invite/${issued.token}/join`)
  if (join.status !== 200) {
    await api(admin, 'DELETE', `/api/v1/organizations/${ORG.slug}/invite-tokens/${inviteId}`)
    throw new Error(`組織への参加失敗: ${join.status} ${JSON.stringify(join.body)}`)
  }
  return { added: true, inviteId }
}

export async function removeOrgMemberIfAdded(admin: Session, member: Session, added: { added: boolean, inviteId?: number }): Promise<void> {
  if (!added.added) return
  await api(admin, 'DELETE', `/api/v1/organizations/${ORG.slug}/members/${member.userId}`)
  if (added.inviteId) await api(admin, 'DELETE', `/api/v1/organizations/${ORG.slug}/invite-tokens/${added.inviteId}`)
}
