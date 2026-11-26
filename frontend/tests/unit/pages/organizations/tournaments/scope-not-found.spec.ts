import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useScopeNotFound } from '~/composables/useScopeNotFound'
import StandingsPage from '~/pages/organizations/[slug]/tournaments/[tId]/standings.vue'
import MatrixPage from '~/pages/organizations/[slug]/tournaments/[tId]/matrix.vue'
import RankingsPage from '~/pages/organizations/[slug]/tournaments/[tId]/rankings.vue'
import ScoreEntryPage from '~/pages/organizations/[slug]/tournaments/[tId]/score-entry.vue'

/**
 * CMP-261125-1108 g4: 組織の大会子ページ（順位表・対戦表・個人ランキング・スコア入力）。
 *
 *   TSN-1 主 API が 403 → notFound を立て、エラートースト・独自の権限なし表示（pi-lock）を出さない
 *   TSN-2 主 API が 404（不可視大会）→ 同上
 *   TSN-3 主 API が 500 → 従来どおり取得失敗表示（pi-exclamation-triangle）、notFound は立てない
 *   TSN-4 スコア入力: 非管理者は API を呼ばず notFound を立てる（FE ガード。権限の文言は出さない）
 */

const getTournament = vi.fn()
const getDivisions = vi.fn()
const getStandings = vi.fn()
const getMatrix = vi.fn()
const getRankings = vi.fn()
const notifyError = vi.fn()
const access = vi.hoisted(() => ({ admin: true }))

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-a', tId: '7' }, query: {} }))
mockNuxtImport('useTournamentApi', () => () => ({ getTournament, getDivisions }))
mockNuxtImport('useTournamentBracket', () => () => ({
  getStandings,
  getMatrix,
  getRankings,
  getIndividualRankings: vi.fn(),
  getRankingsPdf: vi.fn(),
  getStandingsPdf: vi.fn(),
  getMatrixPdf: vi.fn(),
  recalculateStandings: vi.fn(),
}))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdminOrDeputy: computed(() => access.admin),
  loadPermissions: vi.fn().mockResolvedValue({ ok: true }),
}))
mockNuxtImport('useNotification', () => () => ({
  success: vi.fn(),
  error: notifyError,
  warn: vi.fn(),
  info: vi.fn(),
}))

async function flush() {
  for (let i = 0; i < 8; i++) await Promise.resolve()
  await nextTick()
}

beforeEach(() => {
  for (const f of [getTournament, getDivisions, getStandings, getMatrix, getRankings, notifyError]) f.mockReset()
  access.admin = true
  useScopeNotFound().reset()
})
afterEach(() => {
  useScopeNotFound().reset()
})

const pages = [
  { name: 'standings', page: StandingsPage },
  { name: 'matrix', page: MatrixPage },
  { name: 'rankings', page: RankingsPage },
  { name: 'score-entry', page: ScoreEntryPage },
] as const

describe.each(pages)('organizations/[slug]/tournaments/[tId]/$name — 非所属・権限なし（CMP-261125-1108）', ({ page }) => {
  for (const status of [403, 404] as const) {
    it(`TSN-${status === 403 ? 1 : 2}: 主 API が ${status} なら notFound を立て、トーストも権限なし表示も出さない`, async () => {
      getTournament.mockRejectedValue({ statusCode: status, response: { status } })
      getDivisions.mockRejectedValue({ statusCode: status, response: { status } })
      getRankings.mockRejectedValue({ statusCode: status, response: { status } })

      const wrapper = await mountSuspended(page)
      await flush()

      expect(useScopeNotFound().notFound.value).toBe(true)
      expect(notifyError).not.toHaveBeenCalled()
      expect(wrapper.find('.pi-lock').exists()).toBe(false)
      expect(wrapper.find('.pi-exclamation-triangle').exists()).toBe(false)
      wrapper.unmount()
    })
  }

  it('TSN-3: 主 API が 500 なら従来どおり取得失敗を表示し、notFound は立てない', async () => {
    getTournament.mockRejectedValue({ statusCode: 500, response: { status: 500 } })
    getDivisions.mockRejectedValue({ statusCode: 500, response: { status: 500 } })
    getRankings.mockRejectedValue({ statusCode: 500, response: { status: 500 } })

    const wrapper = await mountSuspended(page)
    await flush()

    expect(useScopeNotFound().notFound.value).toBe(false)
    expect(wrapper.find('.pi-exclamation-triangle').exists()).toBe(true)
    wrapper.unmount()
  })
})

describe('organizations/[slug]/tournaments/[tId]/score-entry — 非管理者（CMP-261125-1108）', () => {
  it('TSN-4: 非管理者は API を呼ばず notFound を立て、権限なし表示を出さない', async () => {
    access.admin = false

    const wrapper = await mountSuspended(ScoreEntryPage)
    await flush()

    expect(getTournament).not.toHaveBeenCalled()
    expect(useScopeNotFound().notFound.value).toBe(true)
    expect(notifyError).not.toHaveBeenCalled()
    expect(wrapper.find('.pi-lock').exists()).toBe(false)
    wrapper.unmount()
  })
})
