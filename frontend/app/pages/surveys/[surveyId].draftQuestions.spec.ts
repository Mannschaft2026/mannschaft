// @vitest-environment happy-dom
/**
 * 試練（CMP-261021-0656）: アンケート下書き詳細の保存済み設問表示と、公開時の重複追加・設問0件公開の案内。
 *
 * BE は正常（DRAFT でも設問を保存・返却し、設問0件の公開は SURVEY_012 = NO_QUESTIONS で 409 拒否）。
 * 不具合は FE の詳細画面にある:
 *  - 下書き詳細のエディタを保存済み設問（survey.questions）から初期化しないため「設問がありません」と出る
 *  - 入力中の設問を無条件に addQuestion してから公開するため、保存済みと同じ設問を入れると重複する
 *  - 「設問がなくても公開できます」と案内するが、BE は設問0件の公開を拒否する
 *
 * 画面・設問エディタ・i18n（ja 実ロケール）は実物。API・通知・権限・ルーティングの境界だけ差し替える。
 *
 * 新設 i18n キー（実装側で ja/en/zh/ko/es/de に追加する前提）:
 *  - `surveys.detail.publishNoQuestions` … 設問0件で公開できない理由（画面内に常設表示する）
 * 新設 data-testid:
 *  - `survey-publish-blocked-reason` … 上記理由を表示する要素（トーストではなく画面内に残る）
 */
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import Page from './[surveyId].vue'
import SurveyQuestionEditor, {
  type QuestionDraft,
} from '~/components/survey/SurveyQuestionEditor.vue'
import jaSurveys from '~/locales/ja/surveys.json'
import type { SurveyDetailResponse, SurveyQuestion } from '~/types/survey'

const mocks = vi.hoisted(() => ({
  getSurvey: vi.fn(),
  publishSurvey: vi.fn(),
  closeSurvey: vi.fn(),
  deleteSurvey: vi.fn(),
  addQuestion: vi.fn(),
  getSurveyThread: vi.fn(),
  showError: vi.fn(),
  showSuccess: vi.fn(),
  navigateTo: vi.fn(),
}))

mockNuxtImport('definePageMeta', () => () => undefined)
mockNuxtImport('useRoute', () => () => ({
  params: { surveyId: '42' },
  query: { scope: 'TEAM', scopeId: '5' },
}))
mockNuxtImport('navigateTo', () => mocks.navigateTo)
mockNuxtImport('useSurveyApi', () => () => ({
  getSurvey: mocks.getSurvey,
  publishSurvey: mocks.publishSurvey,
  closeSurvey: mocks.closeSurvey,
  deleteSurvey: mocks.deleteSurvey,
  addQuestion: mocks.addQuestion,
}))
mockNuxtImport('useSurveyBulletinThread', () => () => ({ getSurveyThread: mocks.getSurveyThread }))
mockNuxtImport('useNotification', () => () => ({
  error: mocks.showError,
  success: mocks.showSuccess,
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

// i18n プラグインが import した JSON を AST 化するため、期待文言は raw 読みに変更（期待値は不変）
const rawJaSurveys = JSON.parse(
  readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../../locales/ja/surveys.json'), 'utf8'),
) as Record<string, unknown>

/** ja ロケールの値をドット区切りキーで引く（無ければ undefined）。 */
function jaText(key: string): string | undefined {
  let node: unknown = rawJaSurveys
  for (const part of key.split('.')) {
    if (node === null || typeof node !== 'object') return undefined
    node = (node as Record<string, unknown>)[part]
  }
  return typeof node === 'string' ? node : undefined
}

const SAVED_QUESTIONS: SurveyQuestion[] = [
  {
    id: 101,
    questionText: '好きな果物は？',
    questionType: 'SINGLE_CHOICE',
    isRequired: true,
    sortOrder: 1,
    options: [
      { id: 1001, optionText: 'りんご', sortOrder: 1 },
      { id: 1002, optionText: 'みかん', sortOrder: 2 },
    ],
  },
  {
    id: 102,
    questionText: '感想を教えてください',
    questionType: 'TEXT',
    isRequired: false,
    sortOrder: 2,
    options: [],
  },
]

function draftSurvey(questions: SurveyQuestion[]): SurveyDetail {
  return {
    id: 42,
    status: 'DRAFT',
    scope: { scopeType: 'TEAM', scopeId: '5' },
    content: { title: '下書きアンケート', description: null },
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
      manualRemindCount: null,
    },
    schedule: { startsAt: null, expiresAt: null, publishedAt: null, closedAt: null },
    stats: { responseCount: 0, targetCount: null },
    audit: { version: 0, createdBy: 1, createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z' },
    questions,
    hasResponded: false,
    viewerCanViewResults: false,
    viewerCanManage: true,
    viewerCanViewTeamBreakdown: false,
  }
}

/** BE の NO_QUESTIONS（SURVEY_012 / 409）相当のエラー。 */
function noQuestionsError(): Error & { statusCode: number; data: { error: { code: string; message: string } } } {
  return Object.assign(new Error('HTTP 409'), {
    statusCode: 409,
    data: {
      error: { code: 'SURVEY_012', message: '設問が1つも登録されていないアンケートは公開できません' },
    },
  })
}

const ButtonStub = defineComponent({
  props: {
    label: { type: String, default: '' },
    disabled: { type: Boolean, default: false },
    loading: { type: Boolean, default: false },
  },
  emits: ['click'],
  template:
    '<button v-bind="$attrs" :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
})

/** 設問エディタ内の入力欄。値が画面に見えるかを判定できるよう input として描く。 */
const InputTextStub = defineComponent({
  props: { modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  template:
    '<input v-bind="$attrs" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
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
          Card: true,
          NuxtLink: true,
          SurveyRespondentsList: true,
          SurveyResultsPanel: true,
          SurveyResponseForm: true,
          SurveyTeamBreakdownPanel: true,
          Button: ButtonStub,
          InputText: InputTextStub,
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

/** 画面に見えている文字（テキストと入力欄の値）を集める。設問はエディタの入力欄に出る実装もあり得るため。 */
function visibleContent(wrapper: VueWrapper): string {
  const values = wrapper
    .findAll('input, textarea')
    .map((w) => (w.element as HTMLInputElement | HTMLTextAreaElement).value)
  return [wrapper.text(), ...values].join('\n')
}

/** 下書き詳細の「公開する」主操作を押す（設問付き公開ボタンがあればそれ、無ければ通常の公開ボタン）。 */
async function clickPrimaryPublish(wrapper: VueWrapper): Promise<void> {
  const withQuestions = wrapper.find('[data-testid="survey-publish-with-questions-button"]')
  const target = withQuestions.exists()
    ? withQuestions
    : wrapper.get('[data-testid="survey-publish-button"]')
  await target.trigger('click')
  await flushPromises()
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.getSurveyThread.mockResolvedValue(null)
  mocks.addQuestion.mockResolvedValue({ data: {} })
  mocks.publishSurvey.mockResolvedValue({ data: {} })
})

afterEach(() => {
  vi.clearAllMocks()
})

describe('アンケート下書き詳細: 保存済み設問の表示（CMP-261021-0656 AC1）', () => {
  it('設問を保存済みの下書き詳細を開くと、保存済み設問が表示され「設問がありません」は出ない', async () => {
    mocks.getSurvey.mockResolvedValue({ data: draftSurvey(SAVED_QUESTIONS) })
    const wrapper = await mountPage()

    expect(wrapper.find('[data-testid="survey-mode-draft"]').exists()).toBe(true)
    const content = visibleContent(wrapper)
    expect(content).toContain('好きな果物は？')
    expect(content).toContain('感想を教えてください')
    expect(wrapper.text()).not.toContain(jaText('surveys.create.questionEditor.empty'))
  })
})

describe('アンケート下書き詳細: 公開時に保存済み設問を再送しない（CMP-261021-0656 AC2）', () => {
  it('保存済み設問がある下書きを追加入力なしで公開すると、addQuestion は呼ばれず公開 API だけが呼ばれる', async () => {
    mocks.getSurvey.mockResolvedValue({ data: draftSurvey(SAVED_QUESTIONS) })
    const wrapper = await mountPage()

    await clickPrimaryPublish(wrapper)

    expect(mocks.addQuestion).not.toHaveBeenCalled()
    expect(mocks.publishSurvey).toHaveBeenCalledTimes(1)
    expect(mocks.publishSurvey).toHaveBeenCalledWith('TEAM', '5', 42)
  })

  it('新たに追加入力した設問だけが addQuestion され、保存済み設問は再送されない', async () => {
    mocks.getSurvey.mockResolvedValue({ data: draftSurvey(SAVED_QUESTIONS) })
    const wrapper = await mountPage()

    // 利用者がエディタで設問を1つ追加した状態を作る（エディタの v-model 契約どおり、現在の一覧の末尾に足す）
    const editor = wrapper.getComponent(SurveyQuestionEditor)
    const current = editor.props('modelValue') as QuestionDraft[]
    const added: QuestionDraft = {
      questionText: '参加できる曜日は？',
      questionType: 'TEXT',
      isRequired: false,
      sortOrder: current.length + 1,
      options: [],
    }
    editor.vm.$emit('update:modelValue', [...current, added])
    await flushPromises()

    await clickPrimaryPublish(wrapper)

    expect(mocks.addQuestion).toHaveBeenCalledTimes(1)
    const sentTexts = mocks.addQuestion.mock.calls.map(
      (call) => (call[3] as { questionText: string }).questionText,
    )
    expect(sentTexts).toEqual(['参加できる曜日は？'])
    expect(sentTexts).not.toContain('好きな果物は？')
    expect(sentTexts).not.toContain('感想を教えてください')
    expect(mocks.publishSurvey).toHaveBeenCalledWith('TEAM', '5', 42)
  })
})

describe('アンケート下書き詳細: 設問0件の公開案内（CMP-261021-0656 AC3）', () => {
  it('設問0件の下書きで「設問がなくても公開できます」という誤った案内を出さない', async () => {
    mocks.getSurvey.mockResolvedValue({ data: draftSurvey([]) })
    const wrapper = await mountPage()

    expect(wrapper.find('[data-testid="survey-mode-draft"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('設問がなくても公開できます')
  })

  it('設問0件の下書きで公開しようとすると、公開できない理由が画面内に表示され続ける（トーストだけで消えない）', async () => {
    // 新設キーは実装側で ja ロケールに追加する
    const reason = jaText('surveys.detail.publishNoQuestions')
    expect(reason, 'ja/surveys.json に surveys.detail.publishNoQuestions が無い').toBeTypeOf('string')
    expect(reason).not.toBe('')

    mocks.getSurvey.mockResolvedValue({ data: draftSurvey([]) })
    mocks.publishSurvey.mockRejectedValue(noQuestionsError())
    const wrapper = await mountPage()

    // 公開ボタンが押せる実装なら押す（押せない実装なら、ボタン自体の位置で理由を示していればよい）
    const publishButton = wrapper.find('[data-testid="survey-publish-button"]')
    if (publishButton.exists() && !(publishButton.element as HTMLButtonElement).disabled) {
      await publishButton.trigger('click')
      await flushPromises()
      await flushPromises()
    }

    const blocked = wrapper.find('[data-testid="survey-publish-blocked-reason"]')
    expect(blocked.exists()).toBe(true)
    expect(blocked.text()).toContain(reason as string)
    // 公開は成立していない（成功トーストを出さない）
    expect(mocks.showSuccess).not.toHaveBeenCalled()
  })
})

describe('アンケート下書き詳細: 公開失敗後の再試行で追加済み設問を再送しない（CMP-261021-0656）', () => {
  it('addQuestion 成功後に公開が失敗しても、再試行で同じ設問を addQuestion し直さず、保存済みとして表示する', async () => {
    mocks.getSurvey.mockResolvedValue({ data: draftSurvey(SAVED_QUESTIONS) })
    mocks.publishSurvey.mockRejectedValueOnce(new Error('HTTP 500'))
    const wrapper = await mountPage()

    const editor = wrapper.getComponent(SurveyQuestionEditor)
    const current = editor.props('modelValue') as QuestionDraft[]
    const added: QuestionDraft = {
      questionText: '参加できる曜日は？',
      questionType: 'TEXT',
      isRequired: false,
      sortOrder: current.length + 1,
      options: [],
    }
    editor.vm.$emit('update:modelValue', [...current, added])
    await flushPromises()

    // 1回目: 設問の追加は成功し、公開だけ失敗する
    await clickPrimaryPublish(wrapper)
    expect(mocks.addQuestion).toHaveBeenCalledTimes(1)
    expect(mocks.publishSurvey).toHaveBeenCalledTimes(1)
    // 追加済みの設問は保存済み一覧へ移っている
    expect(visibleContent(wrapper)).toContain('参加できる曜日は？')

    // 2回目（再試行）: 追加分を再送せず、公開 API だけが呼ばれる
    await clickPrimaryPublish(wrapper)
    expect(mocks.addQuestion).toHaveBeenCalledTimes(1)
    expect(mocks.publishSurvey).toHaveBeenCalledTimes(2)
  })
})
