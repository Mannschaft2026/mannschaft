import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import CommitteeDetailPage from '~/pages/committees/[id]/index.vue'

/**
 * CMP-261028-0513 受け入れテスト（試練）。
 *
 * 可視性 NAME_ONLY の委員会を、組織所属・委員会非所属の利用者が開くと、
 * 詳細 API（GET /committees/{id}）は 200 だが、メンバー限定 API
 * （GET /committees/{id}/members）は 403 になる。従来は loadData が
 * Promise.all で両方を待つため、403 一発で画面全体が loadFailed（エラー状態）
 * に落ち、見えてよい詳細まで表示されなかった。
 *
 * BE 認可は拡張しない（members は委員会メンバー限定のまま）。FE は
 * 詳細レスポンスの myRole（委員会の現役所属ロール。非所属なら null）で
 * 所属を判定し、非所属なら members API を呼ばずメンバー節を出さない。
 *
 * 実装役への data-testid 契約:
 *   committee-detail-members        … メンバー節（SectionCard）。委員会所属時のみ描画
 *   committee-detail-members-error  … members 取得失敗時、メンバー節内に出すエラー表示
 *   committee-detail-error-state    … 詳細取得失敗時の全体エラー（既存）
 * i18n キー案: committee.detail.members_load_failed（6言語）
 */

const getCommittee = vi.fn()
const listMembers = vi.fn()
const listInvitations = vi.fn()
const handleApiError = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { id: '1' } }))
mockNuxtImport('useCommitteeApi', () => () => ({
  getCommittee,
  listMembers,
  transitionStatus: vi.fn(),
  removeMember: vi.fn(),
  leaveCommittee: vi.fn(),
}))
mockNuxtImport('useCommitteeInvitationApi', () => () => ({
  listInvitations,
  sendInvitations: vi.fn(),
  cancelInvitation: vi.fn(),
}))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))

function committeeData(myRole: string | null) {
  return {
    id: 1,
    organizationId: 100,
    name: '名前のみ公開委員会',
    description: '委員会の説明文',
    status: 'ACTIVE',
    purposeTag: null,
    startDate: null,
    endDate: null,
    visibilityToOrg: 'NAME_ONLY',
    myRole,
  }
}

const memberRows = [
  { userId: 10, displayName: '山田太郎', avatarUrl: null, role: 'CHAIR', joinedAt: '2026-10-01T00:00:00', leftAt: null, invitedBy: null },
  { userId: 11, displayName: '佐藤花子', avatarUrl: null, role: 'MEMBER', joinedAt: '2026-10-01T00:00:00', leftAt: null, invitedBy: 10 },
]

/** ofetch の FetchError 相当（statusCode / status を持つ）。 */
function forbidden() {
  return Object.assign(new Error('403 Forbidden'), {
    statusCode: 403,
    status: 403,
    data: { error: { code: 'COMMON_002', message: 'Forbidden' } },
  })
}

async function flushMicrotasks() {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
  }
}

beforeAll(async () => {
  getCommittee.mockResolvedValue({ data: committeeData('MEMBER') })
  listMembers.mockResolvedValue({ data: [] })
  listInvitations.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(CommitteeDetailPage)
  warmup.unmount()
})

describe('CMP-261028-0513 委員会詳細の部分表示（pages/committees/[id]/index.vue）', () => {
  beforeEach(() => {
    getCommittee.mockReset()
    listMembers.mockReset()
    listInvitations.mockReset()
    handleApiError.mockReset()
    listInvitations.mockResolvedValue({ data: [] })
  })

  it('H1: 組織所属・委員会非所属（myRole=null）なら詳細を表示し、エラー状態にならない', async () => {
    getCommittee.mockResolvedValue({ data: committeeData(null) })
    // 現状の BE 挙動を再現: 非所属の members は 403
    listMembers.mockRejectedValue(forbidden())
    const wrapper = await mountSuspended(CommitteeDetailPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="committee-detail-error-state"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('名前のみ公開委員会')
    expect(wrapper.text()).toContain('委員会の説明文')
  })

  it('H1: 委員会非所属（myRole=null）なら members API を呼ばない', async () => {
    getCommittee.mockResolvedValue({ data: committeeData(null) })
    listMembers.mockRejectedValue(forbidden())
    await mountSuspended(CommitteeDetailPage)
    await flushMicrotasks()

    expect(getCommittee).toHaveBeenCalledTimes(1)
    expect(listMembers).not.toHaveBeenCalled()
  })

  it('H1: 委員会非所属（myRole=null）ならメンバー節を描画しない（エラー表示も出さない）', async () => {
    getCommittee.mockResolvedValue({ data: committeeData(null) })
    listMembers.mockRejectedValue(forbidden())
    const wrapper = await mountSuspended(CommitteeDetailPage)
    await flushMicrotasks()

    expect(wrapper.text()).toContain('名前のみ公開委員会')
    expect(wrapper.find('[data-testid="committee-detail-members"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="committee-detail-members-error"]').exists()).toBe(false)
    expect(handleApiError).not.toHaveBeenCalled()
  })

  it('H2: 委員会所属（myRole=MEMBER）なら従来どおり members を取得しメンバー節に表示する', async () => {
    getCommittee.mockResolvedValue({ data: committeeData('MEMBER') })
    listMembers.mockResolvedValue({ data: memberRows })
    const wrapper = await mountSuspended(CommitteeDetailPage)
    await flushMicrotasks()

    expect(listMembers).toHaveBeenCalledTimes(1)
    const section = wrapper.find('[data-testid="committee-detail-members"]')
    expect(section.exists()).toBe(true)
    expect(section.text()).toContain('山田太郎')
    expect(section.text()).toContain('佐藤花子')
    expect(wrapper.find('[data-testid="committee-detail-members-error"]').exists()).toBe(false)
  })

  it('H3: 所属判定後に members が403（途中退会等）なら、詳細は表示を維持しメンバー節だけエラー表示する', async () => {
    getCommittee.mockResolvedValue({ data: committeeData('MEMBER') })
    listMembers.mockRejectedValue(forbidden())
    const wrapper = await mountSuspended(CommitteeDetailPage)
    await flushMicrotasks()

    // 全体エラーに落とさない
    expect(wrapper.find('[data-testid="committee-detail-error-state"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('名前のみ公開委員会')
    expect(wrapper.text()).toContain('委員会の説明文')
    // メンバー節だけエラー
    const section = wrapper.find('[data-testid="committee-detail-members"]')
    expect(section.exists()).toBe(true)
    expect(section.find('[data-testid="committee-detail-members-error"]').exists()).toBe(true)
  })

  it('H3: members の403を空一覧（0件）として握りつぶさない', async () => {
    getCommittee.mockResolvedValue({ data: committeeData('MEMBER') })
    listMembers.mockRejectedValue(forbidden())
    const wrapper = await mountSuspended(CommitteeDetailPage)
    await flushMicrotasks()

    const section = wrapper.find('[data-testid="committee-detail-members"]')
    expect(section.exists()).toBe(true)
    // 「メンバー（0）」のような 0 件表示にしない
    expect(section.text()).not.toMatch(/[（(]0[）)]/)
    expect(section.find('[data-testid="committee-detail-members-error"]').exists()).toBe(true)
  })

  it('H4: 詳細API自体が403（他テナント等）なら全体エラー状態のまま、members API は呼ばない', async () => {
    getCommittee.mockRejectedValue(forbidden())
    listMembers.mockRejectedValue(forbidden())
    const wrapper = await mountSuspended(CommitteeDetailPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="committee-detail-error-state"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('名前のみ公開委員会')
    expect(listMembers).not.toHaveBeenCalled()
  })
})

describe('CMP-261028-0513 H5: メンバー節エラー文言の i18n（6言語）', () => {
  const locales = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
  const read = (lc: string) =>
    JSON.parse(readFileSync(resolve(process.cwd(), `app/locales/${lc}/committee.json`), 'utf-8')) as {
      committee?: { detail?: Record<string, unknown> }
    }

  for (const lc of locales) {
    it(`H5: ${lc} に committee.detail.members_load_failed が非空文字列で存在する`, () => {
      const value = read(lc).committee?.detail?.members_load_failed
      expect(typeof value).toBe('string')
      expect((value as string).trim().length).toBeGreaterThan(0)
    })
  }

  it('H5: ja 以外の members_load_failed に日本語（かな）を置かない', () => {
    for (const lc of locales.filter((l) => l !== 'ja')) {
      const value = String(read(lc).committee?.detail?.members_load_failed ?? '')
      expect(value, lc).not.toMatch(/[぀-ヿ]/)
    }
  })
})
