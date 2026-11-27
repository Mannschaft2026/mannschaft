import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'

/**
 * CMP-261118-0900 ALIC-1: 参加大会カード本体のリンクは組織 slug が正準（/organizations/{organizationSlug}/tournaments/{id}）。
 * 数値の組織 ID で組むと遷移先の GET /api/v1/organizations/{id} が 404 になり行き止まりになる。
 * slug が取れないとき（削除済み組織など）はカード本体をリンクにしない。
 */

const mocks = vi.hoisted(() => ({
  getTeamTournamentHistory: vi.fn(),
  getTeamTournamentStats: vi.fn(),
  showError: vi.fn(),
  route: { params: { slug: 'team-a' }, query: {}, path: '/teams/team-a/tournaments' },
}))

mockNuxtImport('useRoute', () => () => mocks.route)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key, locale: { value: 'ja' } }))
mockNuxtImport('useNotification', () => () => ({ showError: mocks.showError, showSuccess: vi.fn(), error: vi.fn(), success: vi.fn() }))
mockNuxtImport('useTournamentApi', () => () => ({
  getTeamTournamentHistory: mocks.getTeamTournamentHistory,
  getTeamTournamentStats: mocks.getTeamTournamentStats,
}))

const stubs = { PageHeader: true, PageLoading: true, DashboardEmptyState: true, SectionCard: { template: '<section><slot /></section>' } }

function entry(tournamentId: number, divisionId: number, divisionName: string, organizationSlug: string | null = 'org-slug-a') {
  return {
    organizationId: 3,
    organizationSlug,
    meta: { tournamentName: `大会${tournamentId}`, season: '2026', divisionName, finalRank: null },
    identifiers: { tournamentId, divisionId, participantId: divisionId * 10 },
    record: { played: 1, wins: 1, draws: 0, losses: 0, points: 3 },
  }
}

async function open() {
  const page = await import('~/pages/teams/[slug]/tournaments/index.vue')
  const wrapper = await mountSuspended(page.default, { global: { stubs, mocks: { $t: (key: string) => key } } })
  await flushPromises()
  return wrapper
}

function hrefs(wrapper: Awaited<ReturnType<typeof open>>) {
  return wrapper.findAll('a').map(a => a.attributes('href'))
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.getTeamTournamentStats.mockResolvedValue({ data: { teamId: 1, totalTournaments: 1 } })
})

describe('参加大会カード本体のリンク（ALIC-1）', () => {
  it('カード本体は /organizations/{organizationSlug}/tournaments/{id} を指し、数値の組織 ID を使わない', async () => {
    mocks.getTeamTournamentHistory.mockResolvedValue({ data: { teamId: 1, history: [entry(5, 11, 'D1部')] } })
    const wrapper = await open()

    expect(hrefs(wrapper)).toContain('/organizations/org-slug-a/tournaments/5')
    expect(hrefs(wrapper).some(h => h?.startsWith('/organizations/3/'))).toBe(false)
  })

  it('organizationSlug が null のカードは本体をリンクにしない（行き止まりリンクを出さない）', async () => {
    mocks.getTeamTournamentHistory.mockResolvedValue({ data: { teamId: 1, history: [entry(5, 11, 'D1部', null)] } })
    const wrapper = await open()

    expect(hrefs(wrapper).some(h => h?.startsWith('/organizations/'))).toBe(false)
    expect(wrapper.text()).toContain('大会5')
  })
})
