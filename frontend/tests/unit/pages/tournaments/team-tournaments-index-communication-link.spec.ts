import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'

/**
 * CMP-261118-0900 X5（設計書 F08.7.1 01_communication.md §10.5「参加大会カードからの導線」）:
 * 参加大会カードに連絡スペースへのリンク（/teams/{slug}/tournaments/{tournamentId}/communication）を置く。
 * カード全体の NuxtLink の中に入れると a の入れ子（不正な HTML）になるため、入れ子にしない。
 * カードは部門ごとに 1 枚出るため、同じ大会で複数部門に出ていればリンクは重複してよい（リンク先は大会単位で同一）。
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

function entry(tournamentId: number, divisionId: number, divisionName: string) {
  return {
    organizationId: 3,
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

describe('参加大会カードの連絡スペース導線', () => {
  it('X5 / AC-P23: カードに /teams/{slug}/tournaments/{tournamentId}/communication へのリンクを出す', async () => {
    mocks.getTeamTournamentHistory.mockResolvedValue({ data: { teamId: 1, history: [entry(5, 11, 'D1部')] } })
    const wrapper = await open()

    expect(hrefs(wrapper)).toContain('/teams/team-a/tournaments/5/communication')
    const link = wrapper.findAll('a').find(a => a.attributes('href') === '/teams/team-a/tournaments/5/communication')!
    expect(link.text()).toContain('tournament.communication.participant.open_link')
  })

  it('X5: リンクを別リンクの中に入れ子にしない（a の中に a が無い）', async () => {
    mocks.getTeamTournamentHistory.mockResolvedValue({ data: { teamId: 1, history: [entry(5, 11, 'D1部')] } })
    const wrapper = await open()

    expect(wrapper.findAll('a a')).toHaveLength(0)
    expect(hrefs(wrapper)).toContain('/teams/team-a/tournaments/5/communication')
  })

  it('X5: 同じ大会で複数部門に出ている場合、各カードのリンク先は大会単位で同一', async () => {
    mocks.getTeamTournamentHistory.mockResolvedValue({
      data: { teamId: 1, history: [entry(5, 11, 'D1部'), entry(5, 12, 'D2部'), entry(8, 21, 'A部')] },
    })
    const wrapper = await open()

    const comm = hrefs(wrapper).filter(h => h?.endsWith('/communication'))
    expect(comm.filter(h => h === '/teams/team-a/tournaments/5/communication')).toHaveLength(2)
    expect(comm.filter(h => h === '/teams/team-a/tournaments/8/communication')).toHaveLength(1)
    expect(wrapper.findAll('a a')).toHaveLength(0)
  })
})
