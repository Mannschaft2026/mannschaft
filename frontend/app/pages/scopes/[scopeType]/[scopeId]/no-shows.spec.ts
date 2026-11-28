import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import NoShowsPage from './no-shows.vue'

/**
 * CMP-261005-2103 I2/I3: 無断欠席（NO_SHOW）管理画面の空表示と権限ゲート。
 */
const api = vi.hoisted(() => ({
  getNoShowsByScope: vi.fn(),
  resolveDispute: vi.fn(),
}))

mockNuxtImport('useRoute', () => () => ({ params: { scopeType: 'TEAM', scopeId: '42' } }))
mockNuxtImport('useRecruitmentApi', () => () => api)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

const stubs = {
  PageHeader: true,
  Dialog: true,
  Paginator: true,
  Button: {
    props: ['label'],
    template: '<button>{{ label }}</button>',
  },
  Tag: {
    props: ['value'],
    template: '<span>{{ value }}</span>',
  },
}

async function mountPage() {
  const wrapper = await mountSuspended(NoShowsPage, { global: { stubs } })
  await flushPromises()
  return wrapper
}

describe('無断欠席管理画面（CMP-261005-2103）', () => {
  beforeEach(() => {
    api.getNoShowsByScope.mockReset()
    api.resolveDispute.mockReset()
  })

  it('I2: 無断欠席0件のときは専用の文言を出し、募集一覧の「募集はありません」を流用しない', async () => {
    api.getNoShowsByScope.mockResolvedValue({ data: [], meta: { total: 0 } })
    const wrapper = await mountPage()
    try {
      expect(api.getNoShowsByScope).toHaveBeenCalledWith('TEAM', '42', 0, 20)
      expect(wrapper.text()).toContain('recruitment.noShow.empty')
      expect(wrapper.text()).not.toContain('recruitment.label.noListings')
    }
    finally {
      wrapper.unmount()
    }
  })

  it('I3: 非管理者がURL直打ちしてBEが403を返したら、権限なしを表示し0件表示や操作部品を出さない', async () => {
    api.getNoShowsByScope.mockRejectedValue(
      Object.assign(new Error('Forbidden'), { statusCode: 403, data: { error: { code: 'COMMON_002' } } }),
    )
    const wrapper = await mountPage()
    try {
      const errorState = wrapper.find('[data-testid="load-error-state"]')
      expect(errorState.exists()).toBe(true)
      expect(errorState.text()).toContain('loadErrorState.states.forbidden.title')
      // 権限なしを「0件」と取り違えさせない
      expect(wrapper.text()).not.toContain('recruitment.noShow.empty')
      expect(wrapper.text()).not.toContain('recruitment.label.noListings')
      expect(wrapper.text()).not.toContain('recruitment.noShow.resolveDialog.title')
      expect(api.resolveDispute).not.toHaveBeenCalled()
    }
    finally {
      wrapper.unmount()
    }
  })
})
