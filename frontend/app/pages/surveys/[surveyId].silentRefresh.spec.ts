// @vitest-environment happy-dom
/**
 * 検分対応（CMP-261125-2221 S2）: 督促送信後の裏取得（fetchDetail(silent=true)）が失敗しても、
 * ページ全体をエラー表示に替えず（回答者一覧を消さず）古い表示を残し、失敗は通知で知らせる。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import Page from './[surveyId].vue'
import jaSurveys from '~/locales/ja/surveys.json'
import type { SurveyDetailResponse } from '~/types/survey'

const mocks = vi.hoisted(() => ({
  getSurvey: vi.fn(),
  showError: vi.fn(),
  showSuccess: vi.fn(),
}))

mockNuxtImport('definePageMeta', () => () => undefined)
mockNuxtImport('useRoute', () => () => ({
  params: { surveyId: '42' },
  query: { scope: 'TEAM', scopeId: '5' },
}))
mockNuxtImport('navigateTo', () => vi.fn())
mockNuxtImport('useSurveyApi', () => () => ({
  getSurvey: mocks.getSurvey,
  publishSurvey: vi.fn(),
  closeSurvey: vi.fn(),
  deleteSurvey: vi.fn(),
  addQuestion: vi.fn(),
}))
mockNuxtImport('useSurveyBulletinThread', () => () => ({
  getSurveyThread: vi.fn().mockResolvedValue(null),
}))
mockNuxtImport('useNotification', () => () => ({
  error: mocks.showError,
  success: mocks.showSuccess,
}))
mockNuxtImport('useErrorHandler', () => () => ({
  handleApiError: vi.fn(),
  resolveMessage: vi.fn((code: string, fallback?: string) => fallback ?? code),
}))
mockNuxtImport('useConfirmDialog', () => () => ({ confirmAction: vi.fn() }))
mockNuxtImport('useAuthStore', () => () => ({ currentUser: { id: 1 }, user: { id: 1 } }))
mockNuxtImport('useTeamApi', () => () => ({
  getTeam: vi.fn().mockResolvedValue({ data: { basicInfo: { name: 'テストチーム' } } }),
}))
mockNuxtImport('useOrganizationApi', () => () => ({
  getOrganization: vi.fn().mockResolvedValue({ data: { basicInfo: { name: 'テスト組織' } } }),
}))
mockNuxtImport('useRoleAccess', () => () => ({
  permissions: ref<string[]>([]),
  roleName: ref<string | null>('ADMIN'),
  loadPermissions: vi.fn().mockResolvedValue(undefined),
}))

type SurveyDetail = SurveyDetailResponse['data']

function publishedSurvey(): SurveyDetail {
  return {
    id: 42,
    status: 'PUBLISHED',
    scope: { scopeType: 'TEAM', scopeId: '5' },
    content: { title: '公開中アンケート', description: null },
    policy: {
      isAnonymous: false,
      allowMultipleSubmissions: false,
      resultsVisibility: 'RESPONDENTS',
      unrespondedVisibility: 'CREATOR_AND_ADMIN',
    },
    distribution: {
      distributionMode: 'ALL',
      autoPostToTimeline: null,
      seriesId: null,
      remindBeforeHours: null,
      manualRemindCount: 1,
      remindMaxCount: 3,
      lastRemindedAt: null,
      nextRemindableAt: null,
    },
    schedule: { startsAt: null, expiresAt: null, publishedAt: null, closedAt: null },
    stats: { responseCount: 0, targetCount: null },
    audit: { version: 0, createdBy: 1, createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z' },
    questions: [],
    hasResponded: false,
    viewerCanViewResults: false,
    viewerCanManage: true,
    viewerCanViewTeamBreakdown: false,
  } as SurveyDetail
}

const RespondentsStub = defineComponent({
  name: 'SurveyRespondentsList',
  emits: ['reminded', 'refresh'],
  template: '<div data-testid="respondents-stub" />',
})

const PassThrough = defineComponent({
  template: '<div><slot name="title" /><slot name="content" /><slot /></div>',
})

const ButtonStub = defineComponent({
  props: { label: { type: String, default: '' } },
  emits: ['click'],
  template: '<button v-bind="$attrs" @click="$emit(\'click\')">{{ label }}</button>',
})

async function mountPage(): Promise<VueWrapper> {
  const wrapper = mount(
    defineComponent({
      setup: () => ({ page: Page }),
      template: '<Suspense><component :is="page" /></Suspense>',
    }),
    {
      global: {
        plugins: [
          createI18n({
            legacy: false,
            locale: 'ja',
            missingWarn: false,
            fallbackWarn: false,
            messages: { ja: jaSurveys, en: {}, zh: {}, ko: {}, es: {}, de: {} },
          }),
        ],
        stubs: {
          PageLoading: true,
          PageHeader: true,
          SurveyScopeContext: true,
          Badge: true,
          NuxtLink: true,
          SurveyResultsPanel: true,
          SurveyResponseForm: true,
          SurveyTeamBreakdownPanel: true,
          SurveyRespondentsList: RespondentsStub,
          Card: PassThrough,
          Button: ButtonStub,
          InputText: true,
          Select: true,
          Checkbox: true,
          ToggleSwitch: true,
        },
      },
    },
  )
  await flushPromises()
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('アンケート詳細: 督促後の裏取得が失敗した場合（CMP-261125-2221 S2）', () => {
  it('裏取得に失敗してもエラー全画面に替えず、回答者一覧を残して失敗を通知する', async () => {
    mocks.getSurvey.mockResolvedValueOnce({ data: publishedSurvey() })
    const wrapper = await mountPage()

    await wrapper.get('[data-testid="survey-respondents-toggle"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="respondents-stub"]').exists()).toBe(true)

    mocks.getSurvey.mockRejectedValueOnce(new Error('HTTP 500'))
    wrapper.getComponent(RespondentsStub).vm.$emit('reminded')
    await flushPromises()
    await flushPromises()

    expect(mocks.getSurvey).toHaveBeenCalledTimes(2)
    // 一覧は残り、全画面エラーには替わらない
    expect(wrapper.find('[data-testid="respondents-stub"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="survey-respondents-section"]').exists()).toBe(true)
    // 失敗は握りつぶさず通知する
    expect(mocks.showError).toHaveBeenCalledTimes(1)
  })
})

describe('アンケート詳細: 督促の失敗時・再読込時の取り直し（CMP-261125-2221）', () => {
  it('回答者一覧から refresh が来たら、全画面ローディングにせず詳細を裏で取り直す', async () => {
    mocks.getSurvey.mockResolvedValueOnce({ data: publishedSurvey() })
    const wrapper = await mountPage()

    await wrapper.get('[data-testid="survey-respondents-toggle"]').trigger('click')
    await flushPromises()

    mocks.getSurvey.mockResolvedValueOnce({ data: publishedSurvey() })
    wrapper.getComponent(RespondentsStub).vm.$emit('refresh')
    await flushPromises()

    expect(mocks.getSurvey).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="respondents-stub"]').exists()).toBe(true)
  })
})
