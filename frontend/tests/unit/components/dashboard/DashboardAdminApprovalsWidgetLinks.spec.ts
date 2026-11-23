// CMP-261107-1833 PR3 N4（j）: 管理者レンズの承認待ちウィジェットの内訳行を、各ドメインの一覧（listRoute）へのリンクにする（全ドメイン）。
// INVITE_REISSUE（再招待の依頼）の内訳行は …/invites#reissue-requests へ遷移する。試練。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent } from 'vue'
import DashboardAdminApprovalsWidget from '~/components/dashboard/DashboardAdminApprovalsWidget.vue'

const mocks = vi.hoisted(() => ({ getAdminActionRequired: vi.fn() }))
mockNuxtImport('useScopeTabApi', () => () => ({ getAdminActionRequired: mocks.getAdminActionRequired }))

class ImmediateObserver {
  constructor(private readonly cb: (entries: Array<{ isIntersecting: boolean }>) => void) {}
  observe() {
    this.cb([{ isIntersecting: true }])
  }
  disconnect() {}
  unobserve() {}
}

const stubs = {
  SectionCard: defineComponent({ props: ['title'], template: '<section><h2>{{ title }}</h2><slot /></section>' }),
  Message: defineComponent({ template: '<div><slot /></div>' }),
  NuxtLink: defineComponent({ props: ['to'], template: '<a :href="String(to)" v-bind="$attrs"><slot /></a>' }),
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('IntersectionObserver', ImmediateObserver)
})

describe('DashboardAdminApprovalsWidget の内訳行（N4）', () => {
  it('DAW-01: 各ドメインの内訳行が listRoute へのリンクになる（INVITE_REISSUE は …/invites#reissue-requests）', async () => {
    mocks.getAdminActionRequired.mockResolvedValue({
      scopeType: 'TEAM',
      scopeId: 1,
      totalPending: 3,
      domains: [
        { domain: 'RESERVATION', pendingCount: 1, degraded: false, listRoute: '/teams/fc/admin/reservations?status=PENDING', items: [] },
        { domain: 'INVITE_REISSUE', pendingCount: 2, degraded: false, listRoute: '/teams/fc/invites#reissue-requests', items: [] },
      ],
    })
    const wrapper = await mountSuspended(DashboardAdminApprovalsWidget, {
      props: { scopeType: 'TEAM', slug: 'fc' },
      global: { stubs },
    })
    await flushPromises()

    const items = wrapper.findAll('li')
    expect(items).toHaveLength(2)
    const hrefs = items.map(li => li.find('a').exists() ? li.find('a').attributes('href') : null)
    expect(hrefs).toEqual(['/teams/fc/admin/reservations?status=PENDING', '/teams/fc/invites#reissue-requests'])
    expect(items[1]!.text()).toContain('2')
  })
})
