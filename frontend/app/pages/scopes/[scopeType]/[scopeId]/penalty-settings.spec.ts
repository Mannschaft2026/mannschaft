import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '~/types/generated'
import PenaltySettingsPage from './penalty-settings.vue'

const api = vi.hoisted(() => ({
  getPenaltySetting: vi.fn(),
  getScopePenalties: vi.fn(),
  upsertPenaltySetting: vi.fn(),
  liftPenalty: vi.fn(),
}))

mockNuxtImport('useRoute', () => () => ({ params: { scopeType: 'TEAM', scopeId: '42' } }))
mockNuxtImport('useRecruitmentApi', () => () => api)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

describe('ペナルティ設定の解除操作', () => {
  it('APIのactiveとliftedAtから状態と解除ボタンを表示する', async () => {
    const activePenalty = {
      id: 1,
      userId: 21,
      scopeType: 'TEAM',
      scopeId: 42,
      penaltyType: 'NO_SHOW',
      startedAt: '2026-11-20T12:00:00',
      expiresAt: '2026-12-20T12:00:00',
      active: true,
    } satisfies components['schemas']['RecruitmentUserPenaltyResponse']
    const penalties = [
      activePenalty,
      { ...activePenalty, id: 2, userId: 22, active: false },
      { ...activePenalty, id: 3, userId: 23, active: false, liftedAt: '2026-11-21T12:00:00' },
    ]
    api.getPenaltySetting.mockResolvedValue({
      data: {
        isEnabled: true,
        thresholdCount: 3,
        thresholdPeriodDays: 90,
        penaltyDurationDays: 30,
        autoNoShowDetection: false,
        disputeAllowedDays: 7,
      },
    })
    api.getScopePenalties.mockResolvedValue({ data: penalties, meta: { total: penalties.length } })

    const wrapper = await mountSuspended(PenaltySettingsPage, {
      global: {
        stubs: {
          PageHeader: true,
          SectionCard: true,
          Dialog: true,
          Button: {
            props: ['label'],
            template: '<button>{{ label }}</button>',
          },
          Tag: {
            props: ['value'],
            template: '<span data-testid="penalty-status">{{ value }}</span>',
          },
        },
      },
    })
    try {
      await flushPromises()
      expect(api.getScopePenalties).toHaveBeenCalledWith('TEAM', '42', 0, 20)
      const liftButtons = wrapper.findAll('button').filter(button =>
        button.text() === 'recruitment.penalty.liftButton',
      )
      expect(liftButtons).toHaveLength(1)
      expect(wrapper.findAll('[data-testid="penalty-status"]').map(tag => tag.text())).toEqual([
        'recruitment.penalty.status.active',
        'recruitment.penalty.status.expired',
        'recruitment.penalty.status.lifted',
      ])
    }
    finally {
      wrapper.unmount()
    }
  })
})

// ---------------------------------------------------------------------------
// CMP-261005-2103 I2/I3: 空表示・未設定表示の専用文言と、非管理者の直打ちに対する権限ゲート
// ---------------------------------------------------------------------------

const gateStubs = {
  PageHeader: true,
  Dialog: true,
  Paginator: true,
  SectionCard: { template: '<div><slot /></div>' },
  InputSwitch: { template: '<input data-testid="penalty-form-control" type="checkbox" />' },
  InputNumber: { template: '<input data-testid="penalty-form-control" type="number" />' },
  Button: {
    props: ['label'],
    template: '<button>{{ label }}</button>',
  },
  Tag: {
    props: ['value'],
    template: '<span>{{ value }}</span>',
  },
}

const savedSetting = {
  id: 5,
  scopeType: 'TEAM',
  scopeId: '42',
  isEnabled: true,
  thresholdCount: 3,
  thresholdPeriodDays: 90,
  penaltyDurationDays: 30,
  applyScope: 'THIS_SCOPE_ONLY',
  autoNoShowDetection: false,
  disputeAllowedDays: 7,
  createdAt: '2026-11-01T00:00:00',
  updatedAt: '2026-11-01T00:00:00',
}

async function mountGatePage() {
  const wrapper = await mountSuspended(PenaltySettingsPage, { global: { stubs: gateStubs } })
  await flushPromises()
  return wrapper
}

describe('ペナルティ設定画面の文言と権限ゲート（CMP-261005-2103）', () => {
  beforeEach(() => {
    api.getPenaltySetting.mockReset()
    api.getScopePenalties.mockReset()
    api.upsertPenaltySetting.mockReset()
    api.liftPenalty.mockReset()
  })

  it('I2: ペナルティ0件のときは専用の文言を出し、募集一覧の「募集はありません」を流用しない', async () => {
    api.getPenaltySetting.mockResolvedValue({ data: savedSetting })
    api.getScopePenalties.mockResolvedValue({ data: [], meta: { total: 0 } })
    const wrapper = await mountGatePage()
    try {
      expect(wrapper.text()).toContain('recruitment.penalty.emptyPenalties')
      expect(wrapper.text()).not.toContain('recruitment.label.noListings')
      // 保存済みのポリシーがあるときは「未設定」を出さない
      expect(wrapper.text()).not.toContain('recruitment.penalty.notConfigured')
    }
    finally {
      wrapper.unmount()
    }
  })

  it('I2: ポリシー未設定（BE が既定値を id なしで返す）のときは未設定の専用文言を出す', async () => {
    api.getPenaltySetting.mockResolvedValue({
      data: { ...savedSetting, id: null, isEnabled: false, createdAt: null, updatedAt: null },
    })
    api.getScopePenalties.mockResolvedValue({ data: [], meta: { total: 0 } })
    const wrapper = await mountGatePage()
    try {
      expect(wrapper.text()).toContain('recruitment.penalty.notConfigured')
      expect(wrapper.text()).not.toContain('recruitment.label.noListings')
    }
    finally {
      wrapper.unmount()
    }
  })

  it('I3: 非管理者がURL直打ちしてBEが403を返したら、権限なしを表示し設定フォーム・保存ボタンを出さない', async () => {
    const forbidden = () => Object.assign(new Error('Forbidden'), {
      statusCode: 403,
      data: { error: { code: 'COMMON_002' } },
    })
    api.getPenaltySetting.mockRejectedValue(forbidden())
    api.getScopePenalties.mockRejectedValue(forbidden())
    const wrapper = await mountGatePage()
    try {
      const errorState = wrapper.find('[data-testid="load-error-state"]')
      expect(errorState.exists()).toBe(true)
      expect(errorState.text()).toContain('loadErrorState.states.forbidden.title')
      expect(wrapper.find('[data-testid="penalty-form-control"]').exists()).toBe(false)
      const operationLabels = ['recruitment.penalty.saveButton', 'recruitment.penalty.liftButton']
      expect(wrapper.findAll('button').filter(b => operationLabels.includes(b.text()))).toHaveLength(0)
      // 権限なしを「0件」と取り違えさせない
      expect(wrapper.text()).not.toContain('recruitment.penalty.emptyPenalties')
      expect(wrapper.text()).not.toContain('recruitment.label.noListings')
      expect(api.upsertPenaltySetting).not.toHaveBeenCalled()
    }
    finally {
      wrapper.unmount()
    }
  })
})
