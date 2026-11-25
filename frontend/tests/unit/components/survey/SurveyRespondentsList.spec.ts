import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { VueWrapper } from '@vue/test-utils'
import dayjs from 'dayjs'
import SurveyRespondentsList from '~/components/survey/SurveyRespondentsList.vue'
import type { RespondentItem, SurveyStatus } from '~/types/survey'

/**
 * SurveyRespondentsList.vue ユニットテスト — 督促の残り回数・送信可否の常時表示（CMP-261125-2221）
 *
 * 観点（受け入れ条件 AC4〜AC10）:
 *   AC4: 権限あり・PUBLISHED・送信1回・待機なし → 「残り 2/3 回」を出しボタン有効
 *   AC5: 上限到達 → ボタン無効・上限の理由（待機時刻があっても上限を優先）
 *   AC6: nextRemindableAt が未来 → ボタン無効・その時刻を含む待機の理由
 *   AC7: PUBLISHED でない → ボタン無効・公開中でない理由
 *   AC8: 権限なし → 残り回数を出さず自動通知の案内だけ
 *   AC9: 送信成功 → 親へ再取得を通知（emit 'reminded'）
 *   AC10: BE エラー → 従来どおりエラー通知（emit しない）
 *
 * 親（pages/surveys/[surveyId].vue）から渡す形（承認済み設計）:
 *   - canRemind: 督促の「権限」だけを表す（PUBLISHED かどうかは含めない。AC7 で理由を出すため）
 *   - surveyStatus: アンケートの状態
 *   - distribution: 詳細応答の distribution（manualRemindCount / remindMaxCount / nextRemindableAt / lastRemindedAt）
 * 上限回数は FE で定数を持たず、BE が返す remindMaxCount を使う。
 */

const mockGetRespondents = vi.fn()
const mockRemindRespondents = vi.fn()
const mockShowError = vi.fn()
const mockShowSuccess = vi.fn()

mockNuxtImport('useSurveyApi', () => () => ({
  getRespondents: mockGetRespondents,
  remindRespondents: mockRemindRespondents,
}))

mockNuxtImport('useNotification', () => () => ({
  success: mockShowSuccess,
  info: vi.fn(),
  warn: vi.fn(),
  error: mockShowError,
}))

const RESPONDENTS: RespondentItem[] = [
  { userId: 1, fullName: '回答 太郎', avatarUrl: null, hasResponded: true, respondedAt: null },
  { userId: 2, fullName: '未回答 花子', avatarUrl: null, hasResponded: false, respondedAt: null },
]

interface RemindDistribution {
  distributionMode: 'ALL' | 'TARGETED'
  autoPostToTimeline: boolean
  seriesId: string | null
  remindBeforeHours: string | null
  includeSupporters: boolean
  manualRemindCount: number
  remindMaxCount: number
  lastRemindedAt: string | null
  nextRemindableAt: string | null
}

function distribution(overrides: Partial<RemindDistribution> = {}): RemindDistribution {
  return {
    distributionMode: 'ALL',
    autoPostToTimeline: false,
    seriesId: null,
    remindBeforeHours: null,
    includeSupporters: false,
    manualRemindCount: 0,
    remindMaxCount: 3,
    lastRemindedAt: null,
    nextRemindableAt: null,
    ...overrides,
  }
}

interface MountOptions {
  canRemind?: boolean
  surveyStatus?: SurveyStatus
  distribution?: RemindDistribution
}

async function flush() {
  await new Promise((r) => setTimeout(r, 0))
  await new Promise((r) => setTimeout(r, 0))
}

function t(key: string, params?: Record<string, unknown>): string {
  const { $i18n } = useNuxtApp()
  return params ? $i18n.t(key, params) : $i18n.t(key)
}

/** 未回答タブを開いた状態でマウントする（督促 UI は未回答タブに出る）。 */
async function mountList(options: MountOptions = {}): Promise<VueWrapper> {
  const wrapper = await mountSuspended(SurveyRespondentsList, {
    props: {
      scopeType: 'TEAM',
      scopeId: '10',
      surveyId: 99,
      canRemind: options.canRemind ?? true,
      surveyStatus: options.surveyStatus ?? 'PUBLISHED',
      distribution: options.distribution ?? distribution(),
    },
  })
  await flush()
  const tabs = wrapper.find('[data-testid="respondents-filter"]').findAll('button')
  expect(tabs.length).toBeGreaterThanOrEqual(2)
  await tabs[1]!.trigger('click')
  await flush()
  return wrapper
}

function remindButton(wrapper: VueWrapper) {
  return wrapper.find('[data-testid="respondents-remind-button"]')
}

function quotaText(wrapper: VueWrapper): string {
  return wrapper.find('[data-testid="respondents-remind-quota"]').text()
}

function blockedReason(wrapper: VueWrapper) {
  return wrapper.find('[data-testid="respondents-remind-blocked-reason"]')
}

beforeEach(() => {
  mockGetRespondents.mockReset()
  mockRemindRespondents.mockReset()
  mockShowError.mockReset()
  mockShowSuccess.mockReset()
  mockGetRespondents.mockResolvedValue({ data: RESPONDENTS })
})

describe('SurveyRespondentsList.vue — 督促の残り回数・送信可否（CMP-261125-2221）', () => {
  it('AC4: 権限あり・PUBLISHED・送信1回・待機なしなら「残り 2/3 回」を出しボタンが有効', async () => {
    const wrapper = await mountList({
      distribution: distribution({
        manualRemindCount: 1,
        lastRemindedAt: dayjs().subtract(2, 'day').format('YYYY-MM-DDTHH:mm:ss'),
        nextRemindableAt: null,
      }),
    })

    expect(quotaText(wrapper)).toContain(
      t('surveys.respondents.remindQuota', { remaining: 2, max: 3 }),
    )
    expect(remindButton(wrapper).exists()).toBe(true)
    expect(remindButton(wrapper).attributes('disabled')).toBeUndefined()
    // 送れるときは理由を出さない
    expect(blockedReason(wrapper).exists()).toBe(false)
  }, 30_000)

  it('AC4: 上限値は FE の定数ではなく BE の remindMaxCount を使う', async () => {
    const wrapper = await mountList({
      distribution: distribution({ manualRemindCount: 1, remindMaxCount: 5 }),
    })

    expect(quotaText(wrapper)).toContain(
      t('surveys.respondents.remindQuota', { remaining: 4, max: 5 }),
    )
    expect(remindButton(wrapper).attributes('disabled')).toBeUndefined()
  }, 30_000)

  it('AC5: 上限到達ならボタン無効・上限の理由を出す（待機時刻があっても上限を優先）', async () => {
    const next = dayjs().add(5, 'hour').format('YYYY-MM-DDTHH:mm:ss')
    const wrapper = await mountList({
      distribution: distribution({
        manualRemindCount: 3,
        lastRemindedAt: dayjs().subtract(19, 'hour').format('YYYY-MM-DDTHH:mm:ss'),
        nextRemindableAt: next,
      }),
    })

    expect(quotaText(wrapper)).toContain(
      t('surveys.respondents.remindQuota', { remaining: 0, max: 3 }),
    )
    expect(remindButton(wrapper).attributes('disabled')).toBeDefined()
    expect(blockedReason(wrapper).exists()).toBe(true)
    expect(blockedReason(wrapper).text()).toContain(t('surveys.respondents.remindBlockedQuota'))
    // 待機の理由は出さない（待っても送れないため）
    const { formatDateTime } = useDatetime()
    expect(blockedReason(wrapper).text()).not.toContain(
      t('surveys.respondents.remindBlockedCooldown', { at: formatDateTime(next) }),
    )
  }, 30_000)

  it('AC6: nextRemindableAt が未来ならボタン無効・その時刻を含む待機の理由を出す', async () => {
    const next = dayjs().add(5, 'hour').format('YYYY-MM-DDTHH:mm:ss')
    const wrapper = await mountList({
      distribution: distribution({
        manualRemindCount: 1,
        lastRemindedAt: dayjs().subtract(19, 'hour').format('YYYY-MM-DDTHH:mm:ss'),
        nextRemindableAt: next,
      }),
    })

    const { formatDateTime } = useDatetime()
    expect(quotaText(wrapper)).toContain(
      t('surveys.respondents.remindQuota', { remaining: 2, max: 3 }),
    )
    expect(remindButton(wrapper).attributes('disabled')).toBeDefined()
    expect(blockedReason(wrapper).exists()).toBe(true)
    expect(blockedReason(wrapper).text()).toContain(
      t('surveys.respondents.remindBlockedCooldown', { at: formatDateTime(next) }),
    )
  }, 30_000)

  it('AC7: PUBLISHED でないならボタン無効・公開中でない理由を出す', async () => {
    const wrapper = await mountList({
      surveyStatus: 'CLOSED',
      distribution: distribution({ manualRemindCount: 1 }),
    })

    expect(remindButton(wrapper).exists()).toBe(true)
    expect(remindButton(wrapper).attributes('disabled')).toBeDefined()
    expect(blockedReason(wrapper).exists()).toBe(true)
    expect(blockedReason(wrapper).text()).toContain(t('surveys.respondents.remindBlockedNotPublished'))
  }, 30_000)

  it('AC8: 権限が無ければ残り回数を出さず、自動通知の案内だけを出す', async () => {
    const wrapper = await mountList({
      canRemind: false,
      distribution: distribution({ manualRemindCount: 1 }),
    })

    expect(wrapper.find('[data-testid="respondents-remind-quota"]').exists()).toBe(false)
    expect(remindButton(wrapper).exists()).toBe(false)
    expect(blockedReason(wrapper).exists()).toBe(false)
    expect(wrapper.text()).toContain(t('surveys.respondents.remindAutoNotice'))
  }, 30_000)

  it('AC9: 送信が成功したら親へ再取得を通知する（emit reminded）', async () => {
    mockRemindRespondents.mockResolvedValue({
      data: { surveyId: 99, remindedCount: 1, remainingRemindQuota: 1, message: 'ok' },
    })
    const wrapper = await mountList({
      distribution: distribution({ manualRemindCount: 1 }),
    })

    await remindButton(wrapper).trigger('click')
    await flush()

    expect(mockRemindRespondents).toHaveBeenCalledWith(99)
    expect(wrapper.emitted('reminded')).toBeTruthy()
    expect(wrapper.emitted('reminded')).toHaveLength(1)
    expect(mockShowError).not.toHaveBeenCalled()
  }, 30_000)

  it('AC10: BE がエラーを返したら従来どおりエラーを通知し、再取得は通知しない', async () => {
    mockRemindRespondents.mockRejectedValue(new Error('REMIND_COOLDOWN_NOT_ELAPSED'))
    const wrapper = await mountList({
      distribution: distribution({ manualRemindCount: 1 }),
    })

    // 前提: 表示上は送れる状態（表示と BE の判定が食い違うケース）
    expect(remindButton(wrapper).attributes('disabled')).toBeUndefined()

    await remindButton(wrapper).trigger('click')
    await flush()

    expect(mockShowError).toHaveBeenCalledTimes(1)
    expect(mockShowError).toHaveBeenCalledWith(
      t('surveys.respondents.remindFailed'),
      t('surveys.respondents.remindFailedDetail'),
    )
    expect(wrapper.emitted('reminded')).toBeFalsy()
  }, 30_000)
})

describe('SurveyRespondentsList.vue — 検分対応（CMP-261125-2221）', () => {
  it('S1: 督促ヒントの上限回数は BE の remindMaxCount を反映する（「3回」を直書きしない）', async () => {
    const wrapper = await mountList({
      distribution: distribution({ manualRemindCount: 0, remindMaxCount: 5 }),
    })

    const hint = wrapper.find('[data-testid="respondents-remind-hint"]').text()
    expect(hint).toContain('5')
    expect(hint).not.toContain('3')
  }, 30_000)

  it('S1: 上限が未取得（remindMaxCount なし）のときは回数を含まないヒントを出す', async () => {
    const wrapper = await mountList({
      distribution: distribution({ remindMaxCount: undefined as unknown as number }),
    })

    const hint = wrapper.find('[data-testid="respondents-remind-hint"]').text()
    expect(hint).toBe(t('surveys.respondents.remindHintNoMax', { count: 1 }))
    expect(hint).not.toContain('3')
  }, 30_000)

  describe('S3: 待機終了の再評価', () => {
    afterEach(() => {
      vi.useRealTimers()
    })

    async function mountWithCooldown(): Promise<VueWrapper> {
      const next = new Date(Date.now() + 60_000).toISOString()
      const wrapper = await mountSuspended(SurveyRespondentsList, {
        props: {
          scopeType: 'TEAM',
          scopeId: '10',
          surveyId: 99,
          canRemind: true,
          surveyStatus: 'PUBLISHED',
          distribution: distribution({ manualRemindCount: 1, nextRemindableAt: next }),
        },
      })
      await vi.advanceTimersByTimeAsync(0)
      await vi.advanceTimersByTimeAsync(0)
      await wrapper.find('[data-testid="respondents-filter"]').findAll('button')[1]!.trigger('click')
      await vi.advanceTimersByTimeAsync(0)
      return wrapper
    }

    it('画面を開いたまま nextRemindableAt を過ぎると、ボタンが有効になり理由の表示が消える', async () => {
      vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
      const wrapper = await mountWithCooldown()

      expect(remindButton(wrapper).attributes('disabled')).toBeDefined()
      expect(blockedReason(wrapper).exists()).toBe(true)

      await vi.advanceTimersByTimeAsync(61_000)

      expect(remindButton(wrapper).attributes('disabled')).toBeUndefined()
      expect(blockedReason(wrapper).exists()).toBe(false)
      wrapper.unmount()
    }, 30_000)

    it('unmount すると待機終了のタイマーを解除する', async () => {
      vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
      const wrapper = await mountWithCooldown()
      const before = vi.getTimerCount()
      expect(before).toBeGreaterThan(0)
      wrapper.unmount()
      expect(vi.getTimerCount()).toBeLessThan(before)
    }, 30_000)
  })
})
