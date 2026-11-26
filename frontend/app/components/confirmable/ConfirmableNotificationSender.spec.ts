import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, type PropType } from 'vue'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import ConfirmableNotificationSender from './ConfirmableNotificationSender.vue'
import type {
  ConfirmableNotificationDraftDetail,
  ConfirmableNotificationDraftSaveRequest,
  ConfirmableNotificationDraftSummary,
  ConfirmableNotificationDraftUpdateRequest,
  ConfirmableNotificationTemplate,
  ConfirmableRecipientGroup,
  ConfirmableTarget,
  CreateConfirmableNotificationRequest,
} from '~/types/confirmable'

/**
 * 試練: 確認通知の作成フォームの下書き（DB 保存・複数件・上限10件。CMP-261126-1558 FE）。
 *
 * フォーム本体と i18n は実物を使い、API（useConfirmableNotificationApi）・トースト・確認ダイアログ・エラー処理の境界だけ差し替える。
 * 下書き API は「BE と同じふるまいをする小さな偽サーバ」（下記 server）で受ける。
 * 一覧を再取得する実装でも、手元で一覧を書き換える実装でも同じ結果になるよう、表示と呼び出しの結果で検証する。
 *
 * 実装側が合わせる契約:
 *  - API: useConfirmableNotificationApi() の listDrafts / createDraft / getDraft / updateDraft / deleteDraft
 *    （引数は (scopeType, scopeId[, draftId][, body])。updateDraft の body は baseVersion 必須）
 *  - 送信: sendNotification の request に開いている下書きの `draftId` を入れる
 *  - 自動保存の待ち時間: 入力が止まってから 3000ms（debounce）
 *  - 初期状態（開いた直後）は「新しく書く」と同じ新規状態。最初の保存で POST、以後はその id へ PUT
 *  - data-testid:
 *      confirmable-draft-list（一覧）/ confirmable-draft-row（行。属性 data-draft-id に下書き id）
 *      行の中: confirmable-draft-open（開く）・confirmable-draft-delete（削除）・
 *              <time data-testid="confirmable-draft-updated-at" :datetime="updatedAt">（更新日時）
 *      confirmable-draft-count（「N/10」）/ confirmable-draft-empty（0件の空表示）/ confirmable-draft-new（新しく書く）
 *      confirmable-draft-limit（上限の案内）/ confirmable-draft-conflict（競合の案内）
 *      confirmable-draft-reload-latest（最新を読み込む）/ confirmable-draft-overwrite（この内容で上書き保存）
 *  - 削除の確認は useConfirm().require({ message, accept, ... })
 *  - 文言は i18n キー `confirmable.draft.*`（画面に生のキーを出さない）
 */

const LIMIT = 10
const DEBOUNCE_MS = 3000

interface ServerDraft {
  id: string
  title: string
  body: string
  priority: 'NORMAL' | 'HIGH' | 'URGENT'
  deadlineAt: string | null
  audienceMode: 'DEFAULT' | 'TARGETS' | 'GROUP'
  selectedTargets: ConfirmableTarget[]
  selectedGroupId: string | null
  selectedTemplateId: number | null
  version: number
  createdAt: string
  updatedAt: string
}

const server = {
  drafts: new Map<string, ServerDraft>(),
  clock: 0,
  seq: 0,
}

function tick(): string {
  server.clock += 1
  return new Date(Date.UTC(2026, 10, 20, 0, server.clock)).toISOString()
}

function apiError(status: number, code: string): Error {
  return Object.assign(new Error(code), { status, statusCode: status, data: { error: { code, message: code } } })
}
const NOT_FOUND = 'CONFIRMABLE_NOTIFICATION_DRAFT_NOT_FOUND'
const LIMIT_EXCEEDED = 'CONFIRMABLE_NOTIFICATION_DRAFT_LIMIT_EXCEEDED'
const VERSION_CONFLICT = 'CONFIRMABLE_NOTIFICATION_DRAFT_VERSION_CONFLICT'

function seed(overrides: Partial<ServerDraft> & { id: string }): ServerDraft {
  const at = tick()
  const draft: ServerDraft = {
    title: `件名${overrides.id}`,
    body: `本文${overrides.id}`,
    priority: 'NORMAL',
    deadlineAt: null,
    audienceMode: 'DEFAULT',
    selectedTargets: [],
    selectedGroupId: null,
    selectedTemplateId: null,
    version: 0,
    createdAt: at,
    updatedAt: at,
    ...overrides,
  }
  server.drafts.set(draft.id, draft)
  return draft
}

function summaries(): ConfirmableNotificationDraftSummary[] {
  return [...server.drafts.values()]
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
    .map(d => ({ id: d.id, title: d.title, bodyExcerpt: d.body.slice(0, 100), updatedAt: d.updatedAt }))
}

function detail(d: ServerDraft): ConfirmableNotificationDraftDetail {
  return {
    id: d.id,
    title: d.title,
    body: d.body,
    priority: d.priority,
    deadlineAt: d.deadlineAt ?? undefined,
    audienceMode: d.audienceMode,
    selectedTargets: structuredClone(d.selectedTargets),
    selectedGroupId: d.selectedGroupId ?? undefined,
    selectedTemplateId: d.selectedTemplateId ?? undefined,
    version: d.version,
    createdAt: d.createdAt,
    updatedAt: d.updatedAt,
  }
}

function applyFields(d: ServerDraft, req: ConfirmableNotificationDraftSaveRequest): boolean {
  const next = {
    title: req.title ?? '',
    body: req.body ?? '',
    priority: req.priority ?? 'NORMAL',
    deadlineAt: req.deadlineAt ?? null,
    audienceMode: req.audienceMode ?? 'DEFAULT',
    selectedTargets: structuredClone(req.selectedTargets ?? []),
    selectedGroupId: req.selectedGroupId ?? null,
    selectedTemplateId: req.selectedTemplateId ?? null,
  }
  const before = JSON.stringify({ ...d, id: undefined, version: undefined, createdAt: undefined, updatedAt: undefined })
  Object.assign(d, next)
  const after = JSON.stringify({ ...d, id: undefined, version: undefined, createdAt: undefined, updatedAt: undefined })
  return before !== after
}

const mocks = vi.hoisted(() => ({
  sendNotification: vi.fn(),
  previewRecipients: vi.fn(),
  listTemplates: vi.fn(),
  listRecipientGroups: vi.fn(),
  listDrafts: vi.fn(),
  createDraft: vi.fn(),
  getDraft: vi.fn(),
  updateDraft: vi.fn(),
  deleteDraft: vi.fn(),
  handleApiError: vi.fn(),
  toastAdd: vi.fn(),
  confirmRequire: vi.fn(),
}))
mockNuxtImport('useConfirmableNotificationApi', () => () => ({
  sendNotification: mocks.sendNotification,
  previewRecipients: mocks.previewRecipients,
  listTemplates: mocks.listTemplates,
  listRecipientGroups: mocks.listRecipientGroups,
  listDrafts: mocks.listDrafts,
  createDraft: mocks.createDraft,
  getDraft: mocks.getDraft,
  updateDraft: mocks.updateDraft,
  deleteDraft: mocks.deleteDraft,
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
mockNuxtImport('useToast', () => () => ({ add: mocks.toastAdd }))
mockNuxtImport('useConfirm', () => () => ({ require: mocks.confirmRequire, close: vi.fn() }))

function installServer() {
  mocks.listDrafts.mockImplementation(async () => ({ data: summaries() }))
  mocks.createDraft.mockImplementation(async (_t: string, _s: string, req: ConfirmableNotificationDraftSaveRequest) => {
    if (server.drafts.size >= LIMIT) throw apiError(409, LIMIT_EXCEEDED)
    server.seq += 1
    const d = seed({ id: `new-${server.seq}` })
    applyFields(d, req)
    return { data: detail(d) }
  })
  mocks.getDraft.mockImplementation(async (_t: string, _s: string, id: string) => {
    const d = server.drafts.get(id)
    if (!d) throw apiError(404, NOT_FOUND)
    return { data: detail(d) }
  })
  mocks.updateDraft.mockImplementation(async (_t: string, _s: string, id: string, req: ConfirmableNotificationDraftUpdateRequest) => {
    const d = server.drafts.get(id)
    if (!d) throw apiError(404, NOT_FOUND)
    if (req.baseVersion !== d.version) throw apiError(409, VERSION_CONFLICT)
    if (applyFields(d, req)) {
      d.version += 1
      d.updatedAt = tick()
    }
    return { data: detail(d) }
  })
  mocks.deleteDraft.mockImplementation(async (_t: string, _s: string, id: string) => {
    if (!server.drafts.delete(id)) throw apiError(404, NOT_FOUND)
  })
  mocks.sendNotification.mockImplementation(async (_t: string, _s: string, req: CreateConfirmableNotificationRequest) => {
    if (req.draftId) server.drafts.delete(req.draftId)
    return { data: { estimatedRecipientCount: 3 } }
  })
}

interface Option { label: string; value: unknown }

const ModelStub = (name: string, tag = 'input') => defineComponent({
  name,
  inheritAttrs: false,
  props: { modelValue: { type: null as unknown as PropType<unknown>, default: null } },
  emits: ['update:modelValue'],
  template: `<${tag} v-bind="$attrs" />`,
})
const InputTextStub = ModelStub('InputTextStub')
const TextareaStub = ModelStub('TextareaStub', 'textarea')
const DatePickerStub = ModelStub('DatePickerStub')
const TargetPickerStub = ModelStub('TargetPickerStub', 'div')
const SelectStub = defineComponent({
  name: 'SelectStub',
  inheritAttrs: false,
  props: {
    modelValue: { type: null as unknown as PropType<unknown>, default: null },
    options: { type: Array as PropType<Option[]>, default: () => [] },
  },
  emits: ['update:modelValue', 'change'],
  template: '<div v-bind="$attrs" />',
})
const ButtonStub = defineComponent({
  name: 'ButtonStub',
  props: {
    label: { type: String, default: '' },
    icon: { type: String, default: '' },
    disabled: { type: Boolean, default: false },
    loading: { type: Boolean, default: false },
  },
  emits: ['click'],
  template: '<button :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}<slot /></button>',
})
const SectionCardStub = defineComponent({ template: '<section><slot name="header-actions" /><slot /></section>' })
const MessageStub = defineComponent({ template: '<div class="message-stub" v-bind="$attrs"><slot /></div>' })

const mountOpts = {
  global: {
    stubs: {
      InputText: InputTextStub,
      Textarea: TextareaStub,
      DatePicker: DatePickerStub,
      Select: SelectStub,
      Button: ButtonStub,
      SectionCard: SectionCardStub,
      Message: MessageStub,
      Dialog: true,
      ConfirmDialog: true,
      ConfirmableTargetPicker: TargetPickerStub,
    },
  },
}

const templates: ConfirmableNotificationTemplate[] = [{
  id: 11, scopeType: 'TEAM', scopeId: '5', name: '定型A', title: 'テンプレ件名', body: null,
  defaultPriority: 'NORMAL', defaultRecipientGroupId: null, createdAt: '2026-11-01T00:00:00Z',
}]
const groups: ConfirmableRecipientGroup[] = [{ id: 'grp-1', name: '幹部', targets: [{ type: 'TEAM', id: 1 }], createdAt: '2026-11-01T00:00:00Z' }]

type Wrapper = VueWrapper<InstanceType<typeof ConfirmableNotificationSender>>

async function mountSender(props: { scopeType?: 'TEAM' | 'ORGANIZATION'; scopeId?: string } = {}): Promise<Wrapper> {
  const wrapper = await mountSuspended(ConfirmableNotificationSender, {
    ...mountOpts,
    props: { scopeType: props.scopeType ?? 'TEAM', scopeId: props.scopeId ?? '5' },
  })
  await flushPromises()
  return wrapper as Wrapper
}

const titleInput = (w: Wrapper) => w.findComponent(InputTextStub)
const bodyInput = (w: Wrapper) => w.findComponent(TextareaStub)
const deadlineInput = (w: Wrapper) => w.findComponent(DatePickerStub)
const selects = (w: Wrapper) => w.findAllComponents(SelectStub)
const templateSelect = (w: Wrapper) => {
  const found = selects(w).find(s => (s.props('options') ?? [])[0]?.value === null && (s.props('options') ?? []).some(o => o.value === 11))
  if (!found) throw new Error('テンプレート選択が見つからない')
  return found
}
const priorityValues = ['NORMAL', 'HIGH', 'URGENT']
const prioritySelect = (w: Wrapper) => {
  const found = selects(w).find(s => (s.props('options') ?? []).some(o => o.value === 'URGENT') && (s.props('options') ?? []).every(o => priorityValues.includes(String(o.value))))
  if (!found) throw new Error('優先度選択が見つからない')
  return found
}
const audienceSelect = (w: Wrapper) => {
  const found = selects(w).find(s => s.attributes('data-testid') === 'sender-audience-mode-select')
  if (!found) throw new Error('宛先方式選択が見つからない')
  return found
}
const groupSelect = (w: Wrapper) => selects(w).find(s => s.attributes('data-testid') === 'sender-group-select')
const sendButton = (w: Wrapper) => {
  const found = w.findAllComponents(ButtonStub).find(b => b.props('icon') === 'pi pi-send')
  if (!found) throw new Error('送信ボタンが見つからない')
  return found
}

const rows = (w: Wrapper) => w.findAll('[data-testid="confirmable-draft-row"]')
const rowIds = (w: Wrapper) => rows(w).map(r => r.attributes('data-draft-id'))
const row = (w: Wrapper, id: string) => {
  const found = rows(w).find(r => r.attributes('data-draft-id') === id)
  if (!found) throw new Error(`下書き行 ${id} が見つからない`)
  return found
}
async function openDraft(w: Wrapper, id: string) {
  await row(w, id).find('[data-testid="confirmable-draft-open"]').trigger('click')
  await flushPromises()
}
async function clickTestId(w: Wrapper, testId: string) {
  const el = w.find(`[data-testid="${testId}"]`)
  if (!el.exists()) throw new Error(`${testId} が見つからない`)
  await el.trigger('click')
  await flushPromises()
}
async function typeTitle(w: Wrapper, value: string) {
  titleInput(w).vm.$emit('update:modelValue', value)
  await flushPromises()
}
async function typeBody(w: Wrapper, value: string) {
  bodyInput(w).vm.$emit('update:modelValue', value)
  await flushPromises()
}
async function advance(ms: number) {
  await vi.advanceTimersByTimeAsync(ms)
  await flushPromises()
}
function useFakeTimers() {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
}
/** 画面に i18n の生キーが出ていない（訳が引けている）ことを確かめる */
function expectNoRawDraftKey(text: string) {
  expect(text).not.toMatch(/confirmable\.draft\./)
}
function expectBlankForm(w: Wrapper) {
  expect(titleInput(w).props('modelValue')).toBe('')
  expect(bodyInput(w).props('modelValue')).toBe('')
  expect(prioritySelect(w).props('modelValue')).toBe('NORMAL')
  expect(deadlineInput(w).props('modelValue')).toBeNull()
  expect(audienceSelect(w).props('modelValue')).toBe('DEFAULT')
  expect(templateSelect(w).props('modelValue')).toBeNull()
}
const lastCall = <T extends unknown[]>(fn: { mock: { calls: T[] } }): T | undefined => fn.mock.calls.at(-1)

describe('ConfirmableNotificationSender 下書き（DB 保存・複数件・上限10件, CMP-261126-1558 FE）', () => {
  beforeEach(() => {
    localStorage.clear()
    server.drafts.clear()
    server.clock = 0
    server.seq = 0
    for (const fn of Object.values(mocks)) fn.mockReset()
    mocks.listTemplates.mockResolvedValue({ data: templates })
    mocks.listRecipientGroups.mockResolvedValue({ data: groups })
    mocks.previewRecipients.mockResolvedValue({ data: { estimatedRecipientCount: 3 } })
    installServer()
  })
  afterEach(() => {
    vi.useRealTimers()
    localStorage.clear()
  })

  // ---------------------------------------------------------------- FE-AC1
  describe('FE-AC1 下書き一覧', () => {
    it('FE-AC1: 開くと自分の下書き一覧が新しい順に、タイトル・本文冒頭・更新日時つきで出る', async () => {
      const a = seed({ id: 'd-a', title: '古い下書き', body: '古い本文です' })
      const b = seed({ id: 'd-b', title: '中くらい', body: '中くらいの本文' })
      const c = seed({ id: 'd-c', title: '新しい下書き', body: '新しい本文です' })
      const wrapper = await mountSender()

      expect(mocks.listDrafts).toHaveBeenCalledWith('TEAM', '5')
      expect(wrapper.find('[data-testid="confirmable-draft-list"]').exists()).toBe(true)
      expect(rowIds(wrapper)).toEqual(['d-c', 'd-b', 'd-a'])
      for (const d of [a, b, c]) {
        const r = row(wrapper, d.id)
        expect(r.text()).toContain(d.title)
        expect(r.text()).toContain(d.body)
        const time = r.find('[data-testid="confirmable-draft-updated-at"]')
        expect(time.exists()).toBe(true)
        expect(time.attributes('datetime')).toBe(d.updatedAt)
        expect(time.text()).not.toBe('')
      }
      expectNoRawDraftKey(wrapper.text())
    })

    it('FE-AC1: 件数が「N/10」で出る', async () => {
      seed({ id: 'd-1' })
      seed({ id: 'd-2' })
      seed({ id: 'd-3' })
      const wrapper = await mountSender()

      const count = wrapper.find('[data-testid="confirmable-draft-count"]')
      expect(count.exists()).toBe(true)
      expect(count.text().replace(/\s/g, '')).toContain('3/10')
    })

    it('FE-AC1: 0件なら空表示が出て「0/10」になる', async () => {
      const wrapper = await mountSender()

      expect(rows(wrapper)).toHaveLength(0)
      const empty = wrapper.find('[data-testid="confirmable-draft-empty"]')
      expect(empty.exists()).toBe(true)
      expect(empty.text()).not.toBe('')
      expectNoRawDraftKey(empty.text())
      expect(wrapper.find('[data-testid="confirmable-draft-count"]').text().replace(/\s/g, '')).toContain('0/10')
    })

    it('FE-AC1: タイトルが空の下書きは「無題」相当の訳で出る（生キーや空行にしない）', async () => {
      seed({ id: 'd-untitled', title: '', body: '本文だけある' })
      const wrapper = await mountSender()

      const r = row(wrapper, 'd-untitled')
      expect(r.text()).toContain('本文だけある')
      expectNoRawDraftKey(r.text())
      const textWithoutBody = r.text().replace('本文だけある', '').replace(r.find('[data-testid="confirmable-draft-updated-at"]').text(), '').trim()
      expect(textWithoutBody).not.toBe('')
    })

    it('FE-AC1: 組織のフォームでは組織スコープの一覧を取る', async () => {
      await mountSender({ scopeType: 'ORGANIZATION', scopeId: '9' })

      expect(mocks.listDrafts).toHaveBeenCalledWith('ORGANIZATION', '9')
    })
  })

  // ---------------------------------------------------------------- FE-AC2
  describe('FE-AC2 新しく書く', () => {
    it('FE-AC2: 「新しく書く」で開いていた下書きを閉じ、空のフォームになる', async () => {
      seed({ id: 'd-1', title: '開いた件名', body: '開いた本文', priority: 'HIGH', deadlineAt: '2026-12-01T09:00:00.000Z', audienceMode: 'GROUP', selectedGroupId: 'grp-1', selectedTemplateId: 11 })
      const wrapper = await mountSender()
      await openDraft(wrapper, 'd-1')
      expect(titleInput(wrapper).props('modelValue')).toBe('開いた件名')

      await clickTestId(wrapper, 'confirmable-draft-new')

      expectBlankForm(wrapper)
    })

    it('FE-AC2: 最初の入力から3秒後に POST で作られ、一覧に加わる', async () => {
      seed({ id: 'd-old' })
      const wrapper = await mountSender()
      useFakeTimers()
      await clickTestId(wrapper, 'confirmable-draft-new')

      await typeTitle(wrapper, '新しい件名')
      await advance(DEBOUNCE_MS - 100)
      expect(mocks.createDraft).not.toHaveBeenCalled()
      await advance(100)

      expect(mocks.createDraft).toHaveBeenCalledTimes(1)
      expect(mocks.createDraft).toHaveBeenCalledWith('TEAM', '5', expect.objectContaining({ title: '新しい件名' }))
      expect(mocks.updateDraft).not.toHaveBeenCalled()
      expect(rowIds(wrapper)).toEqual(['new-1', 'd-old'])
      expect(row(wrapper, 'new-1').text()).toContain('新しい件名')
      expect(wrapper.find('[data-testid="confirmable-draft-count"]').text().replace(/\s/g, '')).toContain('2/10')
    })

    it('FE-AC2: 作成後の入力は POST ではなく、作られた draftId へ PUT する', async () => {
      const wrapper = await mountSender()
      useFakeTimers()
      await typeTitle(wrapper, '一回目')
      await advance(DEBOUNCE_MS)
      expect(mocks.createDraft).toHaveBeenCalledTimes(1)
      const created = server.drafts.get('new-1')
      expect(created).toBeDefined()

      await typeBody(wrapper, '二回目の本文')
      await advance(DEBOUNCE_MS)

      expect(mocks.createDraft).toHaveBeenCalledTimes(1)
      expect(mocks.updateDraft).toHaveBeenCalledTimes(1)
      expect(mocks.updateDraft).toHaveBeenCalledWith('TEAM', '5', 'new-1', expect.objectContaining({ title: '一回目', body: '二回目の本文', baseVersion: 0 }))
      expect(server.drafts.get('new-1')?.body).toBe('二回目の本文')
    })

    it('FE-AC2: POST の応答を待つ間に入力が続いても二重に作らず、応答後にその draftId へ PUT する', async () => {
      let release: (() => void) | undefined
      const realCreate = mocks.createDraft.getMockImplementation()
      mocks.createDraft.mockImplementationOnce((...args: unknown[]) => new Promise((resolve, reject) => {
        release = () => { (realCreate as (...a: unknown[]) => Promise<unknown>)(...args).then(resolve, reject) }
      }))
      const wrapper = await mountSender()
      useFakeTimers()
      await typeTitle(wrapper, '作成中')
      await advance(DEBOUNCE_MS)
      expect(mocks.createDraft).toHaveBeenCalledTimes(1)

      await typeTitle(wrapper, '作成中に追記')
      await advance(DEBOUNCE_MS)
      expect(mocks.createDraft).toHaveBeenCalledTimes(1)

      release?.()
      await flushPromises()
      await advance(DEBOUNCE_MS)

      expect(mocks.createDraft).toHaveBeenCalledTimes(1)
      expect(server.drafts.size).toBe(1)
      expect(lastCall(mocks.updateDraft)).toEqual(['TEAM', '5', 'new-1', expect.objectContaining({ title: '作成中に追記' })])
      expect(server.drafts.get('new-1')?.title).toBe('作成中に追記')
    })

    it('FE-AC2: 何も入力しなければ（宛先を切り替えて戻しただけでも）下書きは作られない', async () => {
      const wrapper = await mountSender()
      useFakeTimers()
      audienceSelect(wrapper).vm.$emit('update:modelValue', 'TARGETS')
      audienceSelect(wrapper).vm.$emit('update:modelValue', 'DEFAULT')
      await flushPromises()
      await advance(DEBOUNCE_MS * 3)

      expect(mocks.createDraft).not.toHaveBeenCalled()
    })
  })

  // ---------------------------------------------------------------- FE-AC3
  describe('FE-AC3 自動保存', () => {
    it('FE-AC3: 連続入力はデバウンスされ、止まって3秒後に baseVersion 付きの PUT が1回だけ飛ぶ', async () => {
      seed({ id: 'd-1', title: '元の件名', version: 3 })
      const wrapper = await mountSender()
      await openDraft(wrapper, 'd-1')
      useFakeTimers()

      await typeTitle(wrapper, '元の件名A')
      await advance(1000)
      await typeTitle(wrapper, '元の件名AB')
      await advance(1000)
      await typeTitle(wrapper, '元の件名ABC')
      await advance(DEBOUNCE_MS - 100)
      expect(mocks.updateDraft).not.toHaveBeenCalled()
      await advance(100)

      expect(mocks.updateDraft).toHaveBeenCalledTimes(1)
      expect(mocks.updateDraft).toHaveBeenCalledWith('TEAM', '5', 'd-1', expect.objectContaining({ title: '元の件名ABC', baseVersion: 3 }))
      expect(mocks.createDraft).not.toHaveBeenCalled()
    })

    it('FE-AC3: 応答の version を次の PUT の baseVersion に使う', async () => {
      seed({ id: 'd-1', title: '件名', version: 3 })
      const wrapper = await mountSender()
      await openDraft(wrapper, 'd-1')
      useFakeTimers()

      await typeTitle(wrapper, '件名1')
      await advance(DEBOUNCE_MS)
      expect(server.drafts.get('d-1')?.version).toBe(4)
      await typeTitle(wrapper, '件名2')
      await advance(DEBOUNCE_MS)

      expect(mocks.updateDraft).toHaveBeenCalledTimes(2)
      expect(mocks.updateDraft.mock.calls[1]?.[3]).toEqual(expect.objectContaining({ title: '件名2', baseVersion: 4 }))
      expect(server.drafts.get('d-1')?.title).toBe('件名2')
    })

    it('FE-AC3: PUT 本文には優先度・期限（ISO）・宛先モード・宛先・グループ・テンプレートも入る', async () => {
      seed({ id: 'd-1', title: '件名', version: 1 })
      const wrapper = await mountSender()
      await openDraft(wrapper, 'd-1')
      useFakeTimers()

      prioritySelect(wrapper).vm.$emit('update:modelValue', 'URGENT')
      deadlineInput(wrapper).vm.$emit('update:modelValue', new Date('2026-12-24T03:00:00.000Z'))
      audienceSelect(wrapper).vm.$emit('update:modelValue', 'GROUP')
      await flushPromises()
      groupSelect(wrapper)?.vm.$emit('update:modelValue', 'grp-1')
      templateSelect(wrapper).vm.$emit('update:modelValue', 11)
      await flushPromises()
      await advance(DEBOUNCE_MS)

      expect(lastCall(mocks.updateDraft)?.[3]).toEqual(expect.objectContaining({
        priority: 'URGENT',
        deadlineAt: '2026-12-24T03:00:00.000Z',
        audienceMode: 'GROUP',
        selectedGroupId: 'grp-1',
        selectedTemplateId: 11,
        baseVersion: 1,
      }))
    })

    it('FE-AC3: 下書きを開いただけ（復元による値の変化）では PUT しない', async () => {
      seed({ id: 'd-1', title: '件名', body: '本文', priority: 'HIGH', deadlineAt: '2026-12-01T09:00:00.000Z', audienceMode: 'GROUP', selectedGroupId: 'grp-1', selectedTemplateId: 11, version: 2 })
      const wrapper = await mountSender()
      useFakeTimers()
      await openDraft(wrapper, 'd-1')
      await advance(DEBOUNCE_MS * 3)

      expect(mocks.updateDraft).not.toHaveBeenCalled()
      expect(mocks.createDraft).not.toHaveBeenCalled()
    })

    it('FE-AC3: 同じ値の入力や、変えて元に戻しただけでは PUT しない', async () => {
      seed({ id: 'd-1', title: '件名', version: 2 })
      const wrapper = await mountSender()
      await openDraft(wrapper, 'd-1')
      useFakeTimers()

      await typeTitle(wrapper, '件名')
      await advance(DEBOUNCE_MS)
      await typeTitle(wrapper, '件名X')
      await advance(500)
      await typeTitle(wrapper, '件名')
      await advance(DEBOUNCE_MS)

      expect(mocks.updateDraft).not.toHaveBeenCalled()
    })

    it('FE-AC3: 保存した直後に同じ内容をもう一度入れても PUT しない', async () => {
      seed({ id: 'd-1', title: '件名', version: 2 })
      const wrapper = await mountSender()
      await openDraft(wrapper, 'd-1')
      useFakeTimers()
      await typeTitle(wrapper, '件名2')
      await advance(DEBOUNCE_MS)
      expect(mocks.updateDraft).toHaveBeenCalledTimes(1)

      await typeTitle(wrapper, '件名2')
      await advance(DEBOUNCE_MS * 2)

      expect(mocks.updateDraft).toHaveBeenCalledTimes(1)
    })
  })

  // ---------------------------------------------------------------- FE-AC4
  describe('FE-AC4 下書きを開く', () => {
    it('FE-AC4: 行を選ぶと GET した全項目がフォームに復元される（GROUP 宛て）', async () => {
      seed({ id: 'd-1', title: '下書き件名', body: '下書き本文', priority: 'HIGH', deadlineAt: '2026-12-01T09:00:00.000Z', audienceMode: 'GROUP', selectedGroupId: 'grp-1', selectedTemplateId: 11 })
      seed({ id: 'd-2', title: '別の下書き' })
      const wrapper = await mountSender()

      await openDraft(wrapper, 'd-1')

      expect(mocks.getDraft).toHaveBeenCalledWith('TEAM', '5', 'd-1')
      expect(titleInput(wrapper).props('modelValue')).toBe('下書き件名')
      expect(bodyInput(wrapper).props('modelValue')).toBe('下書き本文')
      expect(prioritySelect(wrapper).props('modelValue')).toBe('HIGH')
      const deadline = deadlineInput(wrapper).props('modelValue')
      expect(deadline).toBeInstanceOf(Date)
      expect((deadline as unknown as Date).toISOString()).toBe('2026-12-01T09:00:00.000Z')
      expect(audienceSelect(wrapper).props('modelValue')).toBe('GROUP')
      expect(groupSelect(wrapper)?.props('modelValue')).toBe('grp-1')
      expect(templateSelect(wrapper).props('modelValue')).toBe(11)
    })

    it('FE-AC4: TARGETS の下書きは宛先をそのまま復元する', async () => {
      const targets: ConfirmableTarget[] = [{ type: 'TEAM', id: 101 }, { type: 'ORGANIZATION', id: 9 }]
      seed({ id: 'd-1', audienceMode: 'TARGETS', selectedTargets: targets })
      const wrapper = await mountSender()

      await openDraft(wrapper, 'd-1')

      expect(audienceSelect(wrapper).props('modelValue')).toBe('TARGETS')
      expect(wrapper.findComponent(TargetPickerStub).props('modelValue')).toEqual(targets)
    })

    it('FE-AC4: 別の下書きに開き直すと、その内容に置き換わる（前の下書きの値が残らない）', async () => {
      seed({ id: 'd-1', title: '一つ目', body: '一つ目の本文', priority: 'URGENT', deadlineAt: '2026-12-01T09:00:00.000Z' })
      seed({ id: 'd-2', title: '二つ目', body: '' })
      const wrapper = await mountSender()
      await openDraft(wrapper, 'd-1')

      await openDraft(wrapper, 'd-2')

      expect(titleInput(wrapper).props('modelValue')).toBe('二つ目')
      expect(bodyInput(wrapper).props('modelValue')).toBe('')
      expect(prioritySelect(wrapper).props('modelValue')).toBe('NORMAL')
      expect(deadlineInput(wrapper).props('modelValue')).toBeNull()
    })
  })

  // ---------------------------------------------------------------- FE-AC5
  describe('FE-AC5 削除', () => {
    it('FE-AC5: 行の削除は確認を出し、承認すると DELETE して一覧から消える', async () => {
      seed({ id: 'd-1' })
      seed({ id: 'd-2' })
      const wrapper = await mountSender()

      await row(wrapper, 'd-1').find('[data-testid="confirmable-draft-delete"]').trigger('click')
      await flushPromises()
      expect(mocks.confirmRequire).toHaveBeenCalledTimes(1)
      expect(mocks.deleteDraft).not.toHaveBeenCalled()
      const options = mocks.confirmRequire.mock.calls[0]?.[0] as { message?: string; accept?: () => unknown }
      expect(options.message).toBeTruthy()
      expectNoRawDraftKey(String(options.message))
      await options.accept?.()
      await flushPromises()

      expect(mocks.deleteDraft).toHaveBeenCalledWith('TEAM', '5', 'd-1')
      expect(rowIds(wrapper)).toEqual(['d-2'])
      expect(wrapper.find('[data-testid="confirmable-draft-count"]').text().replace(/\s/g, '')).toContain('1/10')
    })

    it('FE-AC5: 確認を承認しなければ消さない', async () => {
      seed({ id: 'd-1' })
      const wrapper = await mountSender()

      await row(wrapper, 'd-1').find('[data-testid="confirmable-draft-delete"]').trigger('click')
      await flushPromises()

      expect(mocks.confirmRequire).toHaveBeenCalledTimes(1)
      expect(mocks.deleteDraft).not.toHaveBeenCalled()
      expect(rowIds(wrapper)).toEqual(['d-1'])
    })

    it('FE-AC5: 削除ボタンを押しても、その下書きを開かない', async () => {
      seed({ id: 'd-1', title: '消す候補' })
      const wrapper = await mountSender()

      await row(wrapper, 'd-1').find('[data-testid="confirmable-draft-delete"]').trigger('click')
      await flushPromises()

      expect(mocks.getDraft).not.toHaveBeenCalled()
      expect(titleInput(wrapper).props('modelValue')).toBe('')
    })

    it('FE-AC5: 開いている下書きを消したらフォームは新規状態になり、次の入力は POST で新しく作る', async () => {
      seed({ id: 'd-1', title: '開いて消す', body: '本文', priority: 'HIGH', selectedTemplateId: 11 })
      const wrapper = await mountSender()
      await openDraft(wrapper, 'd-1')

      await row(wrapper, 'd-1').find('[data-testid="confirmable-draft-delete"]').trigger('click')
      await flushPromises()
      const options = mocks.confirmRequire.mock.calls[0]?.[0] as { accept?: () => unknown }
      await options.accept?.()
      await flushPromises()

      expectBlankForm(wrapper)
      useFakeTimers()
      await typeTitle(wrapper, '消した後の入力')
      await advance(DEBOUNCE_MS)
      expect(mocks.updateDraft).not.toHaveBeenCalled()
      expect(mocks.createDraft).toHaveBeenCalledWith('TEAM', '5', expect.objectContaining({ title: '消した後の入力' }))
    })
  })

  // ---------------------------------------------------------------- FE-AC6
  describe('FE-AC6 上限', () => {
    it('FE-AC6: 10件あるときは入力前から上限の案内（古い下書きを消すよう促す）が出る', async () => {
      for (let i = 1; i <= LIMIT; i++) seed({ id: `d-${i}` })
      const wrapper = await mountSender()

      const notice = wrapper.find('[data-testid="confirmable-draft-limit"]')
      expect(notice.exists()).toBe(true)
      expect(notice.text()).not.toBe('')
      expectNoRawDraftKey(notice.text())
      expect(wrapper.find('[data-testid="confirmable-draft-count"]').text().replace(/\s/g, '')).toContain('10/10')
    })

    it('FE-AC6: 9件以下なら上限の案内は出ない', async () => {
      for (let i = 1; i <= LIMIT - 1; i++) seed({ id: `d-${i}` })
      const wrapper = await mountSender()

      expect(wrapper.find('[data-testid="confirmable-draft-limit"]').exists()).toBe(false)
    })

    it('FE-AC6: 自動作成が 409 LIMIT_EXCEEDED のときは上限の案内を出し、入力内容は失わない', async () => {
      for (let i = 1; i <= LIMIT - 1; i++) seed({ id: `d-${i}` })
      const wrapper = await mountSender()
      // 一覧を取った後で別の画面が10件目を作った
      seed({ id: 'd-other-tab' })
      useFakeTimers()

      await typeTitle(wrapper, '上限で保存できない件名')
      await typeBody(wrapper, '消えてはいけない本文')
      await advance(DEBOUNCE_MS)

      expect(mocks.createDraft).toHaveBeenCalled()
      const notice = wrapper.find('[data-testid="confirmable-draft-limit"]')
      expect(notice.exists()).toBe(true)
      expectNoRawDraftKey(notice.text())
      expect(titleInput(wrapper).props('modelValue')).toBe('上限で保存できない件名')
      expect(bodyInput(wrapper).props('modelValue')).toBe('消えてはいけない本文')
      expect(server.drafts.size).toBe(LIMIT)
    })

    it('FE-AC6: 10件のまま入力しても入力内容は失わず、勝手に古い下書きを消さない', async () => {
      for (let i = 1; i <= LIMIT; i++) seed({ id: `d-${i}` })
      const wrapper = await mountSender()
      useFakeTimers()

      await typeTitle(wrapper, '上限中の入力')
      await advance(DEBOUNCE_MS * 2)

      expect(titleInput(wrapper).props('modelValue')).toBe('上限中の入力')
      expect(mocks.deleteDraft).not.toHaveBeenCalled()
      expect(server.drafts.size).toBe(LIMIT)
      expect(wrapper.find('[data-testid="confirmable-draft-limit"]').exists()).toBe(true)
    })
  })

  // ---------------------------------------------------------------- FE-AC7
  describe('FE-AC7 競合', () => {
    async function openThenConflict() {
      seed({ id: 'd-1', title: '自分の件名', version: 3 })
      const wrapper = await mountSender()
      await openDraft(wrapper, 'd-1')
      // 別の画面が同じ下書きを更新した
      const d = server.drafts.get('d-1')
      if (!d) throw new Error('seed 失敗')
      d.title = '別画面の件名'
      d.version = 4
      d.updatedAt = tick()
      useFakeTimers()
      await typeTitle(wrapper, '自分の書き換え')
      await advance(DEBOUNCE_MS)
      return wrapper
    }

    it('FE-AC7: 409 VERSION_CONFLICT で「別の画面で更新されました」の案内と2つの操作を出し、黙って上書きしない', async () => {
      const wrapper = await openThenConflict()

      expect(mocks.updateDraft).toHaveBeenCalledTimes(1)
      const notice = wrapper.find('[data-testid="confirmable-draft-conflict"]')
      expect(notice.exists()).toBe(true)
      expect(notice.text()).not.toBe('')
      expectNoRawDraftKey(notice.text())
      expect(wrapper.find('[data-testid="confirmable-draft-reload-latest"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="confirmable-draft-overwrite"]').exists()).toBe(true)
      expect(server.drafts.get('d-1')?.title).toBe('別画面の件名')
      expect(titleInput(wrapper).props('modelValue')).toBe('自分の書き換え')
    })

    it('FE-AC7: 競合中は自動保存を止める（入力を続けても PUT しない）', async () => {
      const wrapper = await openThenConflict()

      await typeTitle(wrapper, '自分の書き換え2')
      await advance(DEBOUNCE_MS * 3)

      expect(mocks.updateDraft).toHaveBeenCalledTimes(1)
      expect(server.drafts.get('d-1')?.title).toBe('別画面の件名')
    })

    it('FE-AC7: 「最新を読み込む」で最新の内容に置き換わり、以後は最新 version で自動保存する', async () => {
      const wrapper = await openThenConflict()

      await clickTestId(wrapper, 'confirmable-draft-reload-latest')

      expect(mocks.getDraft).toHaveBeenLastCalledWith('TEAM', '5', 'd-1')
      expect(titleInput(wrapper).props('modelValue')).toBe('別画面の件名')
      expect(wrapper.find('[data-testid="confirmable-draft-conflict"]').exists()).toBe(false)
      await typeTitle(wrapper, '最新から続き')
      await advance(DEBOUNCE_MS)
      expect(lastCall(mocks.updateDraft)).toEqual(['TEAM', '5', 'd-1', expect.objectContaining({ title: '最新から続き', baseVersion: 4 })])
      expect(server.drafts.get('d-1')?.title).toBe('最新から続き')
    })

    it('FE-AC7: 「この内容で上書き保存」は最新 version で手元の内容を PUT し、案内を閉じる', async () => {
      const wrapper = await openThenConflict()

      await clickTestId(wrapper, 'confirmable-draft-overwrite')

      expect(lastCall(mocks.updateDraft)).toEqual(['TEAM', '5', 'd-1', expect.objectContaining({ title: '自分の書き換え', baseVersion: 4 })])
      expect(server.drafts.get('d-1')?.title).toBe('自分の書き換え')
      expect(wrapper.find('[data-testid="confirmable-draft-conflict"]').exists()).toBe(false)
      expect(titleInput(wrapper).props('modelValue')).toBe('自分の書き換え')
    })
  })

  // ---------------------------------------------------------------- FE-AC8
  describe('FE-AC8 送信連携', () => {
    it('FE-AC8: 送信リクエストに開いている draftId が入り、成功後は一覧から消えフォームは新規状態になる', async () => {
      seed({ id: 'd-1', title: '送る下書き', body: '本文' })
      seed({ id: 'd-2', title: '残る下書き' })
      const wrapper = await mountSender()
      await openDraft(wrapper, 'd-1')

      await sendButton(wrapper).trigger('click')
      await flushPromises()

      expect(mocks.sendNotification).toHaveBeenCalledWith('TEAM', '5', expect.objectContaining({ title: '送る下書き', draftId: 'd-1' }))
      expect(wrapper.emitted('sent')).toHaveLength(1)
      expect(rowIds(wrapper)).toEqual(['d-2'])
      expect(titleInput(wrapper).props('modelValue')).toBe('')
      expect(bodyInput(wrapper).props('modelValue')).toBe('')
    })

    it('FE-AC8: 送信成功の後の入力は送った下書きへ PUT せず、POST で新しく作る', async () => {
      seed({ id: 'd-1', title: '送る下書き', version: 1 })
      const wrapper = await mountSender()
      await openDraft(wrapper, 'd-1')
      useFakeTimers()
      // 保存待ちの debounce を残したまま送信する
      await typeTitle(wrapper, '送る下書き（改）')
      await sendButton(wrapper).trigger('click')
      await flushPromises()
      const sendOrder = mocks.sendNotification.mock.invocationCallOrder[0] ?? 0
      await advance(DEBOUNCE_MS * 2)

      const putsAfterSend = mocks.updateDraft.mock.invocationCallOrder.filter(order => order > sendOrder)
      expect(putsAfterSend).toEqual([])
      await typeTitle(wrapper, '次の通知')
      await advance(DEBOUNCE_MS)
      expect(mocks.createDraft).toHaveBeenCalledWith('TEAM', '5', expect.objectContaining({ title: '次の通知' }))
      expect(server.drafts.has('d-1')).toBe(false)
    })

    it('FE-AC8: 下書きを作る前に送信しても、送信後に下書きが残らない', async () => {
      const wrapper = await mountSender()
      useFakeTimers()
      await typeTitle(wrapper, 'すぐ送る')
      await sendButton(wrapper).trigger('click')
      await flushPromises()
      await advance(DEBOUNCE_MS * 2)

      expect(mocks.sendNotification).toHaveBeenCalledTimes(1)
      expect(server.drafts.size).toBe(0)
      expect(rows(wrapper)).toHaveLength(0)
    })

    it('FE-AC8: 送信が失敗したら下書きは残り、フォームの内容と開いている下書きもそのまま', async () => {
      seed({ id: 'd-1', title: '失敗する送信', version: 2 })
      const failure = apiError(500, 'INTERNAL_SERVER_ERROR')
      mocks.sendNotification.mockRejectedValue(failure)
      const wrapper = await mountSender()
      await openDraft(wrapper, 'd-1')

      await sendButton(wrapper).trigger('click')
      await flushPromises()

      expect(mocks.sendNotification).toHaveBeenCalledWith('TEAM', '5', expect.objectContaining({ draftId: 'd-1' }))
      expect(mocks.handleApiError).toHaveBeenCalledWith(failure, expect.anything())
      expect(wrapper.emitted('sent')).toBeUndefined()
      expect(rowIds(wrapper)).toEqual(['d-1'])
      expect(titleInput(wrapper).props('modelValue')).toBe('失敗する送信')
      useFakeTimers()
      await typeTitle(wrapper, '失敗後に直す')
      await advance(DEBOUNCE_MS)
      expect(lastCall(mocks.updateDraft)).toEqual(['TEAM', '5', 'd-1', expect.objectContaining({ title: '失敗後に直す', baseVersion: 2 })])
      expect(mocks.createDraft).not.toHaveBeenCalled()
    })
  })

  // ---------------------------------------------------------------- FE-AC9
  describe('FE-AC9 エラー', () => {
    it('FE-AC9: PUT が 404（他画面で削除済み）なら一覧を取り直し、新規状態になって次の保存は POST になる', async () => {
      seed({ id: 'd-1', title: '消される下書き', version: 1 })
      seed({ id: 'd-2' })
      const wrapper = await mountSender()
      await openDraft(wrapper, 'd-1')
      server.drafts.delete('d-1')
      const listCallsBefore = mocks.listDrafts.mock.calls.length
      useFakeTimers()

      await typeTitle(wrapper, '消された後の入力')
      await advance(DEBOUNCE_MS)

      expect(mocks.updateDraft).toHaveBeenCalledTimes(1)
      expect(mocks.listDrafts.mock.calls.length).toBeGreaterThan(listCallsBefore)
      expect(rowIds(wrapper)).toEqual(['d-2'])
      await typeTitle(wrapper, '新規として書き直し')
      await advance(DEBOUNCE_MS)
      expect(mocks.updateDraft).toHaveBeenCalledTimes(1)
      expect(mocks.createDraft).toHaveBeenCalledWith('TEAM', '5', expect.objectContaining({ title: '新規として書き直し' }))
    })

    it('FE-AC9: 開こうとした下書きが 404 なら一覧を取り直し、フォームは新規状態のまま', async () => {
      seed({ id: 'd-1' })
      seed({ id: 'd-2' })
      const wrapper = await mountSender()
      server.drafts.delete('d-1')
      const listCallsBefore = mocks.listDrafts.mock.calls.length

      await openDraft(wrapper, 'd-1')

      expect(mocks.getDraft).toHaveBeenCalledWith('TEAM', '5', 'd-1')
      expect(mocks.listDrafts.mock.calls.length).toBeGreaterThan(listCallsBefore)
      expect(rowIds(wrapper)).toEqual(['d-2'])
      expectBlankForm(wrapper)
    })

    it('FE-AC9: 削除が 404（既に消えていた）なら一覧を取り直して行を消す', async () => {
      seed({ id: 'd-1' })
      seed({ id: 'd-2' })
      const wrapper = await mountSender()
      server.drafts.delete('d-1')

      await row(wrapper, 'd-1').find('[data-testid="confirmable-draft-delete"]').trigger('click')
      await flushPromises()
      const options = mocks.confirmRequire.mock.calls[0]?.[0] as { accept?: () => unknown }
      await options.accept?.()
      await flushPromises()

      expect(rowIds(wrapper)).toEqual(['d-2'])
    })

    it('FE-AC9: 自動保存の 5xx は共通エラー処理に渡す（握りつぶさない）', async () => {
      seed({ id: 'd-1', version: 1 })
      const failure = apiError(500, 'INTERNAL_SERVER_ERROR')
      const wrapper = await mountSender()
      await openDraft(wrapper, 'd-1')
      mocks.updateDraft.mockRejectedValueOnce(failure)
      useFakeTimers()

      await typeTitle(wrapper, '500 になる保存')
      await advance(DEBOUNCE_MS)

      expect(mocks.handleApiError).toHaveBeenCalledWith(failure, expect.anything())
      expect(titleInput(wrapper).props('modelValue')).toBe('500 になる保存')
    })

    it('FE-AC9: 一覧取得の 5xx も共通エラー処理に渡す', async () => {
      const failure = apiError(503, 'SERVICE_UNAVAILABLE')
      mocks.listDrafts.mockRejectedValue(failure)

      await mountSender()

      expect(mocks.handleApiError).toHaveBeenCalledWith(failure, expect.anything())
    })

    it('FE-AC9: 作成の 5xx も共通エラー処理に渡し、入力内容は残る', async () => {
      const failure = apiError(500, 'INTERNAL_SERVER_ERROR')
      mocks.createDraft.mockRejectedValueOnce(failure)
      const wrapper = await mountSender()
      useFakeTimers()

      await typeTitle(wrapper, '作成が 500')
      await advance(DEBOUNCE_MS)

      expect(mocks.handleApiError).toHaveBeenCalledWith(failure, expect.anything())
      expect(titleInput(wrapper).props('modelValue')).toBe('作成が 500')
    })
  })

  // ---------------------------------------------------------------- FE-AC10（検分で追加）
  describe('FE-AC10 保存できていない入力を黙って失わない', () => {
    type ConfirmOptions = { header?: string; message?: string; accept?: () => unknown; reject?: () => unknown }
    const lastConfirm = () => mocks.confirmRequire.mock.calls.at(-1)?.[0] as ConfirmOptions | undefined

    it('FE-AC10: 上限で保存できない入力があるまま「新しく書く」を押すと確認を出し、拒否なら入力はそのまま', async () => {
      for (let i = 1; i <= LIMIT; i++) seed({ id: `d-${i}` })
      const wrapper = await mountSender()
      useFakeTimers()
      await typeTitle(wrapper, '上限で保存できていない件名')
      await advance(DEBOUNCE_MS)

      await clickTestId(wrapper, 'confirmable-draft-new')

      expect(mocks.confirmRequire).toHaveBeenCalledTimes(1)
      const options = lastConfirm()
      expect(options?.message).toBeTruthy()
      expect(options?.header).toBeTruthy()
      expectNoRawDraftKey(`${options?.message} ${options?.header}`)
      // 拒否（accept を呼ばない / reject を呼ぶ）なら何もしない
      await options?.reject?.()
      await flushPromises()
      expect(titleInput(wrapper).props('modelValue')).toBe('上限で保存できていない件名')
    })

    it('FE-AC10: 確認を承認したときだけ破棄して新規状態にする', async () => {
      for (let i = 1; i <= LIMIT; i++) seed({ id: `d-${i}` })
      const wrapper = await mountSender()
      useFakeTimers()
      await typeTitle(wrapper, '破棄してよい件名')
      await advance(DEBOUNCE_MS)
      await clickTestId(wrapper, 'confirmable-draft-new')

      await lastConfirm()?.accept?.()
      await flushPromises()

      expectBlankForm(wrapper)
      expect(server.drafts.size).toBe(LIMIT)
    })

    it('FE-AC10: 5xx で保存できていない入力があるまま別の下書きを開くと確認を出し、承認するまで開かない', async () => {
      seed({ id: 'd-1', title: '一つ目', version: 1 })
      seed({ id: 'd-2', title: '二つ目' })
      const wrapper = await mountSender()
      await openDraft(wrapper, 'd-1')
      mocks.updateDraft.mockRejectedValue(apiError(500, 'INTERNAL_SERVER_ERROR'))
      useFakeTimers()
      await typeTitle(wrapper, '保存できていない書き換え')
      await advance(DEBOUNCE_MS)
      mocks.getDraft.mockClear()

      await openDraft(wrapper, 'd-2')

      expect(mocks.confirmRequire).toHaveBeenCalledTimes(1)
      expect(mocks.getDraft).not.toHaveBeenCalled()
      expect(titleInput(wrapper).props('modelValue')).toBe('保存できていない書き換え')
      await lastConfirm()?.accept?.()
      await flushPromises()
      expect(titleInput(wrapper).props('modelValue')).toBe('二つ目')
    })

    it('FE-AC10: 競合で保存を止めている入力があるまま「新しく書く」を押しても確認を出す', async () => {
      seed({ id: 'd-1', title: '自分の件名', version: 3 })
      const wrapper = await mountSender()
      await openDraft(wrapper, 'd-1')
      const d = server.drafts.get('d-1')
      if (!d) throw new Error('seed 失敗')
      d.version = 4
      useFakeTimers()
      await typeTitle(wrapper, '競合中の書き換え')
      await advance(DEBOUNCE_MS)
      expect(wrapper.find('[data-testid="confirmable-draft-conflict"]').exists()).toBe(true)

      await clickTestId(wrapper, 'confirmable-draft-new')

      expect(mocks.confirmRequire).toHaveBeenCalledTimes(1)
      expect(titleInput(wrapper).props('modelValue')).toBe('競合中の書き換え')
    })

    it('FE-AC10: 保存できた入力なら確認を出さずに切り替わる', async () => {
      seed({ id: 'd-1', title: '一つ目', version: 1 })
      const wrapper = await mountSender()
      useFakeTimers()
      await typeTitle(wrapper, '保存待ちの入力')

      await clickTestId(wrapper, 'confirmable-draft-new')

      expect(mocks.confirmRequire).not.toHaveBeenCalled()
      expect(mocks.createDraft).toHaveBeenCalledWith('TEAM', '5', expect.objectContaining({ title: '保存待ちの入力' }))
      expectBlankForm(wrapper)
    })

    it('FE-AC10: 上限で保存できなかった入力は、古い下書きを消して空きができたら自動保存される', async () => {
      for (let i = 1; i <= LIMIT; i++) seed({ id: `d-${i}` })
      const wrapper = await mountSender()
      useFakeTimers()
      await typeTitle(wrapper, '空き待ちの件名')
      await advance(DEBOUNCE_MS)
      expect(mocks.createDraft).not.toHaveBeenCalled()

      await row(wrapper, 'd-1').find('[data-testid="confirmable-draft-delete"]').trigger('click')
      await flushPromises()
      await lastConfirm()?.accept?.()
      await flushPromises()
      await advance(DEBOUNCE_MS)

      expect(mocks.createDraft).toHaveBeenCalledWith('TEAM', '5', expect.objectContaining({ title: '空き待ちの件名' }))
    })

    it('FE-AC10: 送信の応答待ちに入力しても、送った下書きを PUT/POST で復活させない', async () => {
      seed({ id: 'd-1', title: '送る下書き', version: 1 })
      let resolveSend: (value: { data: { estimatedRecipientCount: number } }) => void = () => {}
      mocks.sendNotification.mockImplementation((_t: string, _s: string, req: CreateConfirmableNotificationRequest) => new Promise((resolve) => {
        resolveSend = (value) => {
          if (req.draftId) server.drafts.delete(req.draftId)
          resolve(value)
        }
      }))
      const wrapper = await mountSender()
      await openDraft(wrapper, 'd-1')
      useFakeTimers()

      await sendButton(wrapper).trigger('click')
      await flushPromises()
      await typeTitle(wrapper, '応答待ちの入力')
      await advance(DEBOUNCE_MS)
      resolveSend({ data: { estimatedRecipientCount: 3 } })
      await flushPromises()
      await advance(DEBOUNCE_MS * 2)

      expect(mocks.updateDraft).not.toHaveBeenCalled()
      expect(mocks.createDraft).not.toHaveBeenCalled()
      expect(server.drafts.size).toBe(0)
    })
  })
})

describe('ConfirmableNotificationSender 送信前提と未確認者表示の記憶（回帰）', () => {
  beforeEach(() => {
    localStorage.clear()
    server.drafts.clear()
    for (const fn of Object.values(mocks)) fn.mockReset()
    mocks.listTemplates.mockResolvedValue({ data: templates })
    mocks.listRecipientGroups.mockResolvedValue({ data: groups })
    mocks.previewRecipients.mockResolvedValue({ data: { estimatedRecipientCount: 3 } })
    installServer()
  })
  afterEach(() => localStorage.clear())

  it('タイトルが空なら送らない', async () => {
    const wrapper = await mountSender()
    await sendButton(wrapper).trigger('click')
    await flushPromises()
    expect(mocks.sendNotification).not.toHaveBeenCalled()
    expect(mocks.toastAdd).toHaveBeenCalledWith(expect.objectContaining({ severity: 'warn' }))
  })

  it('推定人数が 0 なら送信ボタンは無効で、押しても送らない', async () => {
    mocks.previewRecipients.mockResolvedValue({ data: { estimatedRecipientCount: 0 } })
    const wrapper = await mountSender()
    await typeTitle(wrapper, '件名')
    expect(sendButton(wrapper).props('disabled')).toBe(true)
    sendButton(wrapper).vm.$emit('click')
    await flushPromises()
    expect(mocks.sendNotification).not.toHaveBeenCalled()
  })

  it('未確認者表示は記憶値で初期化され、送信成功で記憶される（下書きには含めない）', async () => {
    localStorage.setItem('confirmable.lastUnconfirmedVisibility', 'HIDDEN')
    const wrapper = await mountSender()
    const visibility = selects(wrapper).find(s => s.attributes('data-testid') === 'sender-visibility-select')
    expect(visibility?.props('modelValue')).toBe('HIDDEN')
    visibility?.vm.$emit('update:modelValue', 'ALL_MEMBERS')
    await typeTitle(wrapper, '件名')
    await sendButton(wrapper).trigger('click')
    await flushPromises()
    expect(mocks.sendNotification).toHaveBeenCalledWith('TEAM', '5', expect.objectContaining({ title: '件名', unconfirmedVisibility: 'ALL_MEMBERS' }))
    expect(localStorage.getItem('confirmable.lastUnconfirmedVisibility')).toBe('ALL_MEMBERS')
    for (const call of [...mocks.createDraft.mock.calls, ...mocks.updateDraft.mock.calls]) {
      expect(call.at(-1)).not.toHaveProperty('unconfirmedVisibility')
    }
  })
})
