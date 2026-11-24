// @vitest-environment happy-dom
/**
 * 試練: アンケート作成ダイアログの「公開して作成」。
 *
 * - CMP-261021-0656 AC4: 設問0件のまま「公開して作成」を押したとき、公開できない理由をダイアログ内に表示する
 *   （現状はトーストだけで、ダイアログには何も残らない）。
 * - CMP-261021-0655: 「公開して作成」を押しても公開 API を呼ばず下書きのまま残る（隣接行。describe を分ける）。
 *
 * ダイアログ本体・i18n（ja 実ロケール）は実物。API・通知・下書き自動保存・認証の境界だけ差し替える。
 *
 * 新設 data-testid:
 *  - `survey-create-error` … ダイアログ内に常設表示する検証・公開失敗の理由
 * 文言は既存キー `surveys.create.validation.questionsRequired` を使う前提。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import SurveyCreateDialog from './SurveyCreateDialog.vue'
import SurveyQuestionEditor, { type QuestionDraft } from './SurveyQuestionEditor.vue'
import jaSurveys from '~/locales/ja/surveys.json'
import type { SurveyResponse } from '~/types/survey'

const mocks = vi.hoisted(() => ({
  createSurvey: vi.fn(),
  publishSurvey: vi.fn(),
  addQuestion: vi.fn(),
  showError: vi.fn(),
  showSuccess: vi.fn(),
  handleApiError: vi.fn(),
}))

mockNuxtImport('useSurveyApi', () => () => ({
  createSurvey: mocks.createSurvey,
  publishSurvey: mocks.publishSurvey,
  addQuestion: mocks.addQuestion,
}))
mockNuxtImport('useNotification', () => () => ({
  error: mocks.showError,
  success: mocks.showSuccess,
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
mockNuxtImport('useAuthStore', () => () => ({ currentUser: { id: 1 }, user: { id: 1 } }))
mockNuxtImport('useFormDraft', () => () => ({
  clear: vi.fn(),
  restore: () => null,
  savedFlash: ref(false),
}))

const QUESTIONS_REQUIRED = jaSurveys.surveys.create.validation.questionsRequired

const createdSurvey: SurveyResponse = {
  id: 99,
  status: 'DRAFT',
  scope: { scopeType: 'TEAM', scopeId: '5' },
  content: { title: '新しいアンケート', description: null },
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

/** Dialog は teleport させず、本文とフッターをその場に描く（「ダイアログ内」を DOM で判定するため）。 */
const DialogStub = defineComponent({
  props: { visible: { type: Boolean, default: false } },
  template:
    '<div v-if="visible" data-testid="dialog-root"><div data-testid="dialog-body"><slot /></div><div data-testid="dialog-footer"><slot name="footer" /></div></div>',
})

const InputTextStub = defineComponent({
  props: { modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  template:
    '<input v-bind="$attrs" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
})

function mountDialog(): VueWrapper {
  return mount(SurveyCreateDialog, {
    props: { scopeType: 'TEAM', scopeId: '5', visible: true },
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
        Dialog: DialogStub,
        Button: ButtonStub,
        InputText: InputTextStub,
        Textarea: true,
        Checkbox: true,
        Select: true,
        DatePicker: true,
      },
    },
  })
}

async function fillTitle(wrapper: VueWrapper, title: string): Promise<void> {
  await wrapper.get('[data-testid="survey-create-title"]').setValue(title)
}

async function setQuestions(wrapper: VueWrapper, questions: QuestionDraft[]): Promise<void> {
  wrapper.getComponent(SurveyQuestionEditor).vm.$emit('update:modelValue', questions)
  await flushPromises()
}

async function clickPublishAndCreate(wrapper: VueWrapper): Promise<void> {
  await wrapper.get('[data-testid="survey-create-submit"]').trigger('click')
  await flushPromises()
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.createSurvey.mockResolvedValue({ data: createdSurvey })
  mocks.publishSurvey.mockResolvedValue({ data: { ...createdSurvey, status: 'PUBLISHED' } })
})

afterEach(() => {
  vi.clearAllMocks()
})

describe('アンケート作成ダイアログ: 設問0件の「公開して作成」（CMP-261021-0656 AC4）', () => {
  it('設問0件のまま「公開して作成」を押すと、公開できない理由がダイアログ内に表示される', async () => {
    const wrapper = mountDialog()
    await fillTitle(wrapper, '新しいアンケート')

    await clickPublishAndCreate(wrapper)

    // 作成も公開もしない
    expect(mocks.createSurvey).not.toHaveBeenCalled()
    expect(mocks.publishSurvey).not.toHaveBeenCalled()
    // ダイアログは開いたまま、理由がダイアログ内に残る（トーストだけで消えない）
    const dialog = wrapper.get('[data-testid="dialog-root"]')
    const error = dialog.find('[data-testid="survey-create-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).toContain(QUESTIONS_REQUIRED)
  })
})

describe('アンケート作成ダイアログ: 「公開して作成」で公開まで行う（CMP-261021-0655）', () => {
  it('設問ありで「公開して作成」を押すと、作成したアンケートに公開 API を呼ぶ', async () => {
    const wrapper = mountDialog()
    await fillTitle(wrapper, '新しいアンケート')
    await setQuestions(wrapper, [
      { questionText: '参加できますか？', questionType: 'TEXT', isRequired: true, sortOrder: 1, options: [] },
    ])

    await clickPublishAndCreate(wrapper)

    expect(mocks.createSurvey).toHaveBeenCalledTimes(1)
    expect(mocks.publishSurvey).toHaveBeenCalledTimes(1)
    expect(mocks.publishSurvey).toHaveBeenCalledWith('TEAM', '5', 99)
  })
})
