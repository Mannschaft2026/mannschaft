/**
 * F06.4.1 PR-4: チームの頑張りの共有履歴 `/teams/{slug}/effort-records`。
 * E24 slug 解決（取得失敗はエラー+再試行、該当なしだけ 404）/ E12 一覧・0件・絞り込み / E25 詳細 404 の画面内表示 /
 * 管理者非表示（ADMIN・DEPUTY_ADMIN だけ）/ 権限なし（403）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import EffortRecordsPage from '~/pages/teams/[slug]/effort-records.vue'

vi.setConfig({ testTimeout: 120000, hookTimeout: 120000 })

const m = await vi.hoisted(async () => {
  const { ref } = await import('vue')
  return {
  resolveTeamId: vi.fn(),
  listTeamRecords: vi.fn(),
  getTeamRecord: vi.fn(),
  moderate: vi.fn(),
  listTeamDefinitions: vi.fn(),
  showError: vi.fn(),
  confirmRequire: vi.fn(),
  isAdminOrDeputy: ref(false),
  }
})
mockNuxtImport('useEffortTeamId', () => () => ({ resolveTeamId: m.resolveTeamId }))
mockNuxtImport('useEffortShareApi', () => () => ({
  listTeamRecords: m.listTeamRecords,
  getTeamRecord: m.getTeamRecord,
  moderate: m.moderate,
  listTeamDefinitions: m.listTeamDefinitions,
}))
mockNuxtImport('showError', () => m.showError)
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'tigers' }, path: '/teams/tigers/effort-records', query: {} }))
mockNuxtImport('useConfirm', () => () => ({ require: m.confirmRequire }))
mockNuxtImport('useRoleAccess', () => () => ({ isAdminOrDeputy: m.isAdminOrDeputy, loadPermissions: vi.fn() }))

const httpError = (status: number) => Object.assign(new Error(`HTTP ${status}`), { status, data: { error: {} } })
const share = (id: string) => ({
  id, version: 2, teamId: 7, author: { userId: 3, displayName: '山田' },
  recordedOn: '2026-11-24', templateName: '週次報告',
  fields: [{ fieldKey: 'f1', label: '勉強時間', displayValue: '30 分', value: '30.00' }],
  interactions: {},
})
const page = (data: unknown[], hasNext = false) => ({ data, meta: { hasNext, nextCursor: hasNext ? 'c1' : null, limit: 20 } })

async function mountPage() {
  const w = await mountSuspended(EffortRecordsPage, { route: '/teams/tigers/effort-records' })
  await flushPromises()
  return w
}

describe('/teams/{slug}/effort-records', () => {
  beforeEach(() => {
    Object.values(m).forEach((fn) => { if (typeof fn === 'function' && 'mockReset' in fn) fn.mockReset() })
    m.isAdminOrDeputy.value = false
    m.resolveTeamId.mockResolvedValue(7)
    m.listTeamDefinitions.mockResolvedValue({ data: [{ definitionUuid: 'd1', templateName: '週次報告', isRetired: false }] })
    m.listTeamRecords.mockResolvedValue(page([share('s1')]))
  })

  it('所属者には共有カードを表示し、反応・コメントなどの操作列は出さない', async () => {
    const w = await mountPage()
    expect(m.resolveTeamId).toHaveBeenCalledWith('tigers')
    expect(m.listTeamRecords).toHaveBeenCalledWith(7, expect.objectContaining({ limit: 20 }))
    const card = w.find('[data-testid="effort-team-card-s1"]')
    expect(card.text()).toContain('山田')
    expect(card.text()).toContain('30 分')
    expect(card.find('[data-testid="team-timeline-like"]').exists()).toBe(false)
    expect(card.find('[data-testid="team-timeline-reply-btn"]').exists()).toBe(false)
  })

  it('チームの解決が失敗（通信障害）したら 404 にせずエラーと再試行を出し、再試行で復帰する', async () => {
    m.resolveTeamId.mockRejectedValueOnce(httpError(500)).mockResolvedValueOnce(7)
    const w = await mountPage()
    expect(m.showError).not.toHaveBeenCalled()
    expect(w.find('[data-testid="effort-team-resolve-error"]').exists()).toBe(true)
    expect(m.listTeamRecords).not.toHaveBeenCalled()
    await w.find('[data-testid="effort-team-resolve-error"] button').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="effort-team-card-s1"]').exists()).toBe(true)
  })

  it('取得は成功したが該当チームが無い（非所属・別テナント・所属0件・存在しない slug）ときだけ 404 を出し、API は呼ばない', async () => {
    m.resolveTeamId.mockResolvedValue(null)
    await mountPage()
    expect(m.showError).toHaveBeenCalledTimes(1)
    expect(m.showError.mock.calls[0]![0]).toMatchObject({ statusCode: 404 })
    expect(m.listTeamRecords).not.toHaveBeenCalled()
  })

  it('共有が0件のときは空状態を出す（絞り込み中は別の文言）', async () => {
    m.listTeamRecords.mockResolvedValue(page([]))
    const w = await mountPage()
    expect(w.text()).toContain('No records have been shared yet')
    expect(w.find('[data-testid="effort-team-list"]').exists()).toBe(false)
  })

  it('一覧の取得失敗は空一覧にせずエラーと再試行を出す', async () => {
    m.listTeamRecords.mockRejectedValueOnce(httpError(503)).mockResolvedValueOnce(page([share('s1')]))
    const w = await mountPage()
    expect(w.find('[data-testid="effort-team-load-error"]').exists()).toBe(true)
    await w.find('[data-testid="effort-team-load-error"] button').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="effort-team-card-s1"]').exists()).toBe(true)
  })

  it('権限なし（403: 脱退後など）は権限なし表示を出す', async () => {
    m.listTeamRecords.mockRejectedValue(httpError(403))
    const w = await mountPage()
    expect(w.find('[data-testid="effort-team-forbidden"]').text()).toBe("You do not have permission to view this team's shared history")
    expect(w.find('[data-testid="effort-team-list"]').exists()).toBe(false)
  })

  it('次ページは cursor で続きを読み、重複は id で排除する', async () => {
    m.listTeamRecords.mockResolvedValueOnce(page([share('s1')], true)).mockResolvedValueOnce(page([share('s1'), share('s2')]))
    const w = await mountPage()
    await w.find('[data-testid="effort-team-more"]').trigger('click')
    await flushPromises()
    expect(m.listTeamRecords).toHaveBeenLastCalledWith(7, expect.objectContaining({ cursor: 'c1' }))
    expect(w.findAll('[data-testid^="effort-team-card-"]')).toHaveLength(2)
  })

  it('人で絞り込むと user_id を付けて取得し直す', async () => {
    const w = await mountPage()
    m.listTeamRecords.mockClear()
    await (w.findComponent('[data-testid="effort-filter-person"]') as unknown as VueWrapper).vm.$emit('update:modelValue', 3)
    await flushPromises()
    expect(m.listTeamRecords).toHaveBeenLastCalledWith(7, expect.objectContaining({ userId: 3 }))
  })

  it('詳細が 404（取消・非表示・脱退で見えなくなった）なら画面内に「見つかりません」を出す', async () => {
    m.getTeamRecord.mockRejectedValue(httpError(404))
    const w = await mountPage()
    await w.find('[data-testid="effort-team-detail-s1"]').trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[data-testid="effort-team-detail-notfound"]')?.textContent).toBe('This shared record was not found')
  })

  it('非表示ボタンは ADMIN・DEPUTY_ADMIN だけに出す。確認後に非表示 API を呼び一覧から消す', async () => {
    const member = await mountPage()
    expect(member.find('[data-testid="effort-team-moderate-s1"]').exists()).toBe(false)

    m.isAdminOrDeputy.value = true
    m.confirmRequire.mockImplementation((o: { accept?: () => void }) => o.accept?.())
    m.moderate.mockResolvedValue({ data: { id: 's1', state: 'MODERATED', version: 3 } })
    const admin = await mountPage()
    await admin.find('[data-testid="effort-team-moderate-s1"]').trigger('click')
    await flushPromises()
    expect(m.moderate).toHaveBeenCalledWith(7, 's1', 2, expect.stringMatching(/^[0-9a-f-]{36}$/))
    expect(admin.find('[data-testid="effort-team-card-s1"]').exists()).toBe(false)
  })
})
