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
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
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
  resolveMessage: vi.fn((code: string, fallback?: string) => fallback ?? code),
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
mockNuxtImport('useErrorHandler', () => () => ({
  handleApiError: mocks.handleApiError,
  resolveMessage: mocks.resolveMessage,
}))
mockNuxtImport('useAuthStore', () => () => ({ currentUser: { id: 1 }, user: { id: 1 } }))
mockNuxtImport('useFormDraft', () => () => ({
  clear: vi.fn(),
  restore: () => null,
  savedFlash: ref(false),
}))

// i18n プラグインが import した JSON を AST 化するため、期待文言は raw 読みに変更（期待値は不変）
const rawJaSurveys = JSON.parse(
  readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../../locales/ja/surveys.json'), 'utf8'),
) as Record<string, unknown>
const QUESTIONS_REQUIRED = (
  rawJaSurveys as { surveys: { create: { validation: { questionsRequired: string } } } }
).surveys.create.validation.questionsRequired

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
  mocks.resolveMessage.mockImplementation((code: string, fallback?: string) => fallback ?? code)
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

// === 検分差し戻し（M1 / R1 / R2）: 公開失敗後の下書き残留 ===

const TEXT_Q: QuestionDraft = {
  questionText: '参加できますか？',
  questionType: 'TEXT',
  isRequired: true,
  sortOrder: 1,
  options: [],
}

function publishError(): Error & { statusCode: number; data: { error: { code: string; message: string } } } {
  return Object.assign(new Error('HTTP 409'), {
    statusCode: 409,
    data: { error: { code: 'SURVEY_012', message: '設問が1つも登録されていないアンケートは公開できません' } },
  })
}

async function click(wrapper: VueWrapper, testid: string): Promise<void> {
  await wrapper.get(`[data-testid="${testid}"]`).trigger('click')
  await flushPromises()
  await flushPromises()
}

/** 作成は成功・公開は失敗した状態まで進める。 */
async function reachPublishFailed(wrapper: VueWrapper): Promise<void> {
  mocks.publishSurvey.mockRejectedValueOnce(new Error('HTTP 500'))
  await fillTitle(wrapper, '新しいアンケート')
  await setQuestions(wrapper, [TEXT_Q])
  await clickPublishAndCreate(wrapper)
}

describe('アンケート作成ダイアログ: 公開失敗後は再試行と下書きで閉じるだけに限定する（M1-A）', () => {
  it('公開失敗後は「下書きで保存」を出さず、入力欄を操作不能にし、下書きのまま閉じる旨を表示する', async () => {
    const wrapper = mountDialog()
    await reachPublishFailed(wrapper)

    expect(wrapper.find('[data-testid="survey-create-save-draft"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="survey-create-form-fields"]').attributes('inert')).toBeDefined()
    expect(wrapper.find('[data-testid="survey-create-locked-note"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="survey-create-close-as-draft"]').exists()).toBe(true)
  })

  it('公開失敗後は成功トーストを出さず、「公開を再試行」は createSurvey を呼び直さず公開だけ再実行する', async () => {
    const wrapper = mountDialog()
    await reachPublishFailed(wrapper)

    expect(mocks.showSuccess).not.toHaveBeenCalled()
    expect(mocks.createSurvey).toHaveBeenCalledTimes(1)

    await click(wrapper, 'survey-create-submit')

    expect(mocks.createSurvey).toHaveBeenCalledTimes(1)
    expect(mocks.publishSurvey).toHaveBeenCalledTimes(2)
    expect(mocks.publishSurvey).toHaveBeenLastCalledWith('TEAM', '5', 99)
    expect(mocks.showSuccess).toHaveBeenCalledTimes(1)
  })

  it('公開失敗後に「下書きのまま閉じる」を押すと公開 API は再度呼ばれずダイアログが閉じる', async () => {
    const wrapper = mountDialog()
    await reachPublishFailed(wrapper)

    await click(wrapper, 'survey-create-close-as-draft')

    expect(mocks.publishSurvey).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('update:visible')?.at(-1)).toEqual([false])
  })
})

describe('アンケート作成ダイアログ: 閉じて開き直すと状態をリセットする（M1-B）', () => {
  it('公開失敗後に閉じて再オープンすると、エラー・ロックが消え、新しい入力の「公開して作成」は新規作成から行う', async () => {
    const wrapper = mountDialog()
    await reachPublishFailed(wrapper)

    await wrapper.setProps({ visible: false })
    await flushPromises()
    await wrapper.setProps({ visible: true })
    await flushPromises()

    expect(wrapper.find('[data-testid="survey-create-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="survey-create-locked-note"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="survey-create-form-fields"]').attributes('inert')).toBeUndefined()

    mocks.createSurvey.mockResolvedValueOnce({ data: { ...createdSurvey, id: 100 } })
    await fillTitle(wrapper, '別のアンケート')
    await setQuestions(wrapper, [TEXT_Q])
    await clickPublishAndCreate(wrapper)

    expect(mocks.createSurvey).toHaveBeenCalledTimes(2)
    expect(mocks.publishSurvey).toHaveBeenLastCalledWith('TEAM', '5', 100)
  })
})

describe('アンケート作成ダイアログ: 公開失敗の理由を表示する（R1）', () => {
  it('BE エラーコード付きで公開に失敗すると、BE の理由が survey-create-error に表示される', async () => {
    mocks.publishSurvey.mockRejectedValueOnce(publishError())
    const wrapper = mountDialog()
    await fillTitle(wrapper, '新しいアンケート')
    await setQuestions(wrapper, [TEXT_Q])
    await clickPublishAndCreate(wrapper)

    const error = wrapper.get('[data-testid="survey-create-error"]')
    expect(error.text()).toContain('設問が1つも登録されていないアンケートは公開できません')
    expect(mocks.showSuccess).not.toHaveBeenCalled()
  })
})

describe('アンケート作成ダイアログ: created の emit は1回だけ（R2）', () => {
  it('公開失敗→再試行成功でも created は合計1回', async () => {
    const wrapper = mountDialog()
    await reachPublishFailed(wrapper)
    await click(wrapper, 'survey-create-submit')

    expect(wrapper.emitted('created')).toHaveLength(1)
  })

  it('公開失敗→下書きのまま閉じるでも created は合計1回', async () => {
    const wrapper = mountDialog()
    await reachPublishFailed(wrapper)
    await click(wrapper, 'survey-create-close-as-draft')
    await wrapper.setProps({ visible: false })
    await flushPromises()

    expect(wrapper.emitted('created')).toHaveLength(1)
  })

  it('公開が一度で成功した場合も created は1回', async () => {
    const wrapper = mountDialog()
    await fillTitle(wrapper, '新しいアンケート')
    await setQuestions(wrapper, [TEXT_Q])
    await clickPublishAndCreate(wrapper)

    expect(wrapper.emitted('created')).toHaveLength(1)
  })
})
