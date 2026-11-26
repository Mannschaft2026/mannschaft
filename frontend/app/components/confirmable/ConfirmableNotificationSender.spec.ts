import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, type PropType } from 'vue'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import ConfirmableNotificationSender from './ConfirmableNotificationSender.vue'
import type { ConfirmableNotificationTemplate, ConfirmableRecipientGroup, ConfirmableTarget } from '~/types/confirmable'

/**
 * 試練: 確認通知の作成フォームの下書き自動保存（CMP-261126-1558）。
 *
 * フォーム本体・useFormDraft（実物）・localStorage（実物）を使い、API・認証・トースト・エラー処理の境界だけ差し替える。
 *
 * 実装側が合わせる契約:
 *  - localStorage キー: `confirmable-send-draft-${userId}-${scopeType}-${scopeId}`（userId は useAuthStore().currentUser.id）
 *  - 保存形: { title, body, priority, deadlineAt(ISO文字列|null), audienceMode, selectedTargets, selectedGroupId, selectedTemplateId }
 *    （unconfirmedVisibility は含めない）
 *  - 復元バナー: data-testid `confirmable-draft-restored`、文言は i18n キー `confirmable.draft_restored`
 */
const mocks = vi.hoisted(() => ({
  sendNotification: vi.fn(),
  previewRecipients: vi.fn(),
  listTemplates: vi.fn(),
  listRecipientGroups: vi.fn(),
  handleApiError: vi.fn(),
  toastAdd: vi.fn(),
  auth: { currentUser: { id: 7 } as { id: number } | null },
}))
mockNuxtImport('useConfirmableNotificationApi', () => () => ({
  sendNotification: mocks.sendNotification,
  previewRecipients: mocks.previewRecipients,
  listTemplates: mocks.listTemplates,
  listRecipientGroups: mocks.listRecipientGroups,
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
mockNuxtImport('useToast', () => () => ({ add: mocks.toastAdd }))
mockNuxtImport('useAuthStore', () => () => ({
  get currentUser() { return mocks.auth.currentUser },
  get user() { return mocks.auth.currentUser },
  loadFromStorage: vi.fn(),
}))

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
  props: { label: { type: String, default: '' }, disabled: { type: Boolean, default: false }, loading: { type: Boolean, default: false } },
  emits: ['click'],
  template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
})
const SectionCardStub = defineComponent({ template: '<section><slot name="header-actions" /><slot /></section>' })
const MessageStub = defineComponent({ template: '<div class="message-stub"><slot /></div>' })

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
      ConfirmableTargetPicker: TargetPickerStub,
    },
  },
}

const templates: ConfirmableNotificationTemplate[] = [{
  id: 11, scopeType: 'TEAM', scopeId: '5', name: '定型A', title: 'テンプレ件名', body: null,
  defaultPriority: 'NORMAL', defaultRecipientGroupId: null, createdAt: '2026-11-01T00:00:00Z',
}]
const groups: ConfirmableRecipientGroup[] = [{ id: 'grp-1', name: '幹部', targets: [{ type: 'TEAM', id: 1 }], createdAt: '2026-11-01T00:00:00Z' }]

const KEY = 'confirmable-send-draft-7-TEAM-5'
const DEBOUNCE_WAIT_MS = 5000

interface DraftJson {
  title: string
  body: string
  priority: string
  deadlineAt: string | null
  audienceMode: 'DEFAULT' | 'TARGETS' | 'GROUP'
  selectedTargets: ConfirmableTarget[]
  selectedGroupId: string | null
  selectedTemplateId: number | null
}
function seedDraft(key: string, overrides: Partial<DraftJson> = {}) {
  const draft: DraftJson = {
    title: '下書き件名', body: '下書き本文', priority: 'HIGH', deadlineAt: '2026-12-01T09:00:00.000Z',
    audienceMode: 'GROUP', selectedTargets: [], selectedGroupId: 'grp-1', selectedTemplateId: 11, ...overrides,
  }
  localStorage.setItem(key, JSON.stringify(draft))
}
function draftKeys(): string[] {
  return Object.keys(localStorage).filter(key => key.startsWith('confirmable-send-draft-'))
}

async function mountSender(props: { scopeType?: 'TEAM' | 'ORGANIZATION'; scopeId?: string } = {}) {
  const wrapper = await mountSuspended(ConfirmableNotificationSender, {
    ...mountOpts,
    props: { scopeType: props.scopeType ?? 'TEAM', scopeId: props.scopeId ?? '5' },
  })
  await flushPromises()
  return wrapper
}
type Wrapper = VueWrapper<InstanceType<typeof ConfirmableNotificationSender>>
const titleInput = (w: Wrapper) => w.findComponent(InputTextStub)
const bodyInput = (w: Wrapper) => w.findComponent(TextareaStub)
const deadlineInput = (w: Wrapper) => w.findComponent(DatePickerStub)
const selects = (w: Wrapper) => w.findAllComponents(SelectStub)
const templateSelect = (w: Wrapper) => {
  const found = selects(w).find(s => (s.props('options') ?? [])[0]?.value === null)
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
const visibilitySelect = (w: Wrapper) => {
  const found = selects(w).find(s => s.attributes('data-testid') === 'sender-visibility-select')
  if (!found) throw new Error('未確認者表示選択が見つからない')
  return found
}
const sendButton = (w: Wrapper) => {
  const found = w.findAllComponents(ButtonStub).find(b => b.props('label') !== '' && b.props('label') !== undefined)
  if (!found) throw new Error('送信ボタンが見つからない')
  return found
}
async function typeTitleAndBody(w: Wrapper, title: string, body: string) {
  titleInput(w).vm.$emit('update:modelValue', title)
  bodyInput(w).vm.$emit('update:modelValue', body)
  await flushPromises()
}
async function waitDebounce() {
  await vi.advanceTimersByTimeAsync(DEBOUNCE_WAIT_MS)
  await flushPromises()
}

describe('ConfirmableNotificationSender 下書き自動保存 (CMP-261126-1558)', () => {
  beforeEach(() => {
    localStorage.clear()
    mocks.auth.currentUser = { id: 7 }
    for (const fn of [mocks.sendNotification, mocks.previewRecipients, mocks.listTemplates, mocks.listRecipientGroups, mocks.handleApiError, mocks.toastAdd]) fn.mockReset()
    mocks.listTemplates.mockResolvedValue({ data: templates })
    mocks.listRecipientGroups.mockResolvedValue({ data: groups })
    mocks.previewRecipients.mockResolvedValue({ data: { estimatedRecipientCount: 3 } })
    mocks.sendNotification.mockResolvedValue({ data: { estimatedRecipientCount: 3 } })
  })
  afterEach(() => {
    vi.useRealTimers()
    localStorage.clear()
  })

  it('AC1: タイトルと本文を入力すると debounce 後に userId・scopeType・scopeId 入りのキーで保存される', async () => {
    const wrapper = await mountSender()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await typeTitleAndBody(wrapper, '集合時間の確認', '明日は8時集合です')
    expect(localStorage.getItem(KEY)).toBeNull()
    await waitDebounce()
    const raw = localStorage.getItem(KEY)
    expect(raw).not.toBeNull()
    const saved = JSON.parse(raw ?? '{}') as Record<string, unknown>
    expect(saved.title).toBe('集合時間の確認')
    expect(saved.body).toBe('明日は8時集合です')
    expect(saved).toHaveProperty('priority')
    expect(saved).toHaveProperty('audienceMode')
    expect(saved).not.toHaveProperty('unconfirmedVisibility')
  })

  it('AC1: 期限は ISO 文字列で保存される', async () => {
    const wrapper = await mountSender()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    titleInput(wrapper).vm.$emit('update:modelValue', '期限つき')
    deadlineInput(wrapper).vm.$emit('update:modelValue', new Date('2026-12-24T03:00:00.000Z'))
    await flushPromises()
    await waitDebounce()
    const saved = JSON.parse(localStorage.getItem(KEY) ?? '{}') as Record<string, unknown>
    expect(saved.deadlineAt).toBe('2026-12-24T03:00:00.000Z')
  })

  it('AC2: 保存済み下書きは loadOptions 後に復元され、復元バナーが出る（deadlineAt は Date）', async () => {
    seedDraft(KEY)
    const wrapper = await mountSender()
    expect(titleInput(wrapper).props('modelValue')).toBe('下書き件名')
    expect(bodyInput(wrapper).props('modelValue')).toBe('下書き本文')
    expect(prioritySelect(wrapper).props('modelValue')).toBe('HIGH')
    const deadline = deadlineInput(wrapper).props('modelValue')
    expect(deadline).toBeInstanceOf(Date)
    expect((deadline as unknown as Date).toISOString()).toBe('2026-12-01T09:00:00.000Z')
    expect(audienceSelect(wrapper).props('modelValue')).toBe('GROUP')
    expect(wrapper.find('[data-testid="sender-group-select"]').exists()).toBe(true)
    const groupSelect = selects(wrapper).find(s => s.attributes('data-testid') === 'sender-group-select')
    expect(groupSelect?.props('modelValue')).toBe('grp-1')
    expect(templateSelect(wrapper).props('modelValue')).toBe(11)
    const banner = wrapper.find('[data-testid="confirmable-draft-restored"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).not.toBe('')
    expect(banner.text()).not.toContain('confirmable.draft_restored')
  })

  it('AC2: TARGETS の下書きは selectedTargets をそのまま復元する', async () => {
    const targets: ConfirmableTarget[] = [{ type: 'TEAM', id: 101 }, { type: 'ORGANIZATION', id: 9 }]
    seedDraft(KEY, { audienceMode: 'TARGETS', selectedTargets: targets, selectedGroupId: null })
    const wrapper = await mountSender()
    expect(audienceSelect(wrapper).props('modelValue')).toBe('TARGETS')
    expect(wrapper.findComponent(TargetPickerStub).props('modelValue')).toEqual(targets)
  })

  it('AC3: 一覧に無い selectedTemplateId / selectedGroupId は null に戻し、ほかは復元する', async () => {
    seedDraft(KEY, { selectedTemplateId: 999, selectedGroupId: 'grp-gone' })
    const wrapper = await mountSender()
    expect(titleInput(wrapper).props('modelValue')).toBe('下書き件名')
    expect(bodyInput(wrapper).props('modelValue')).toBe('下書き本文')
    expect(prioritySelect(wrapper).props('modelValue')).toBe('HIGH')
    expect(templateSelect(wrapper).props('modelValue')).toBeNull()
    const groupSelect = selects(wrapper).find(s => s.attributes('data-testid') === 'sender-group-select')
    if (groupSelect) expect(groupSelect.props('modelValue')).toBeNull()
    expect(wrapper.find('[data-testid="confirmable-draft-restored"]').exists()).toBe(true)
  })

  it('AC4: 別スコープ・別ユーザーの下書きは復元しない', async () => {
    seedDraft('confirmable-send-draft-7-TEAM-6', { title: '別チーム' })
    seedDraft('confirmable-send-draft-7-ORGANIZATION-5', { title: '同番号の組織' })
    seedDraft('confirmable-send-draft-8-TEAM-5', { title: '別ユーザー' })
    const wrapper = await mountSender()
    expect(titleInput(wrapper).props('modelValue')).toBe('')
    expect(wrapper.find('[data-testid="confirmable-draft-restored"]').exists()).toBe(false)
    // 自分の下書きで正しいキーが選ばれることも確認する（契約: 正しいキーなら復元される）
    seedDraft(KEY, { title: '自分の下書き' })
    const mine = await mountSender()
    expect(titleInput(mine).props('modelValue')).toBe('自分の下書き')
  })

  it('AC5: userId が無いときは保存も復元もしない', async () => {
    mocks.auth.currentUser = null
    for (const anon of ['undefined', 'null', 'guest', '']) seedDraft(`confirmable-send-draft-${anon}-TEAM-5`, { title: `匿名${anon}` })
    const seeded = draftKeys().sort()
    const wrapper = await mountSender()
    expect(titleInput(wrapper).props('modelValue')).toBe('')
    expect(wrapper.find('[data-testid="confirmable-draft-restored"]').exists()).toBe(false)
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await typeTitleAndBody(wrapper, '未ログイン中', '本文')
    await waitDebounce()
    expect(draftKeys().sort()).toEqual(seeded)
    for (const key of seeded) expect(localStorage.getItem(key)).not.toContain('未ログイン中')
  })

  it('AC6: 送信成功で下書きが消え、直後に debounce が走っても再保存されない', async () => {
    const wrapper = await mountSender()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await typeTitleAndBody(wrapper, '送る件名', '送る本文')
    await waitDebounce()
    expect(localStorage.getItem(KEY)).not.toBeNull()
    // 保存待ちの debounce を残したまま送信する
    titleInput(wrapper).vm.$emit('update:modelValue', '送る件名（改）')
    await flushPromises()
    await sendButton(wrapper).trigger('click')
    await flushPromises()
    expect(mocks.sendNotification).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('sent')).toHaveLength(1)
    expect(localStorage.getItem(KEY)).toBeNull()
    await waitDebounce()
    expect(localStorage.getItem(KEY)).toBeNull()
    expect(draftKeys()).toEqual([])
  })

  it('AC6: 優先度と期限を変えて送信が成功しても、debounce の後に下書きが再保存されない', async () => {
    const wrapper = await mountSender()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await typeTitleAndBody(wrapper, '送る件名', '送る本文')
    prioritySelect(wrapper).vm.$emit('update:modelValue', 'URGENT')
    deadlineInput(wrapper).vm.$emit('update:modelValue', new Date('2026-12-24T03:00:00.000Z'))
    await flushPromises()
    await waitDebounce()
    expect(localStorage.getItem(KEY)).not.toBeNull()
    await sendButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.emitted('sent')).toHaveLength(1)
    expect(localStorage.getItem(KEY)).toBeNull()
    await waitDebounce()
    expect(localStorage.getItem(KEY)).toBeNull()
    expect(prioritySelect(wrapper).props('modelValue')).toBe('NORMAL')
    expect(deadlineInput(wrapper).props('modelValue')).toBeNull()
  })

  it('AC9: 何も入力しなければ debounce を待っても下書きのキーができない', async () => {
    const wrapper = await mountSender()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    audienceSelect(wrapper).vm.$emit('update:modelValue', 'TARGETS')
    audienceSelect(wrapper).vm.$emit('update:modelValue', 'DEFAULT')
    await flushPromises()
    await waitDebounce()
    expect(draftKeys()).toEqual([])
  })

  it('AC7: 送信が失敗したときは下書きが残る', async () => {
    mocks.sendNotification.mockRejectedValue(new Error('500'))
    const wrapper = await mountSender()
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    await typeTitleAndBody(wrapper, '失敗する件名', '本文')
    await waitDebounce()
    await sendButton(wrapper).trigger('click')
    await flushPromises()
    await waitDebounce()
    expect(mocks.handleApiError).toHaveBeenCalled()
    expect(wrapper.emitted('sent')).toBeUndefined()
    const saved = JSON.parse(localStorage.getItem(KEY) ?? '{}') as Record<string, unknown>
    expect(saved.title).toBe('失敗する件名')
  })
})

describe('ConfirmableNotificationSender 送信前提と未確認者表示の記憶（回帰, CMP-261126-1558 AC8）', () => {
  beforeEach(() => {
    localStorage.clear()
    mocks.auth.currentUser = { id: 7 }
    for (const fn of [mocks.sendNotification, mocks.previewRecipients, mocks.listTemplates, mocks.listRecipientGroups, mocks.handleApiError, mocks.toastAdd]) fn.mockReset()
    mocks.listTemplates.mockResolvedValue({ data: templates })
    mocks.listRecipientGroups.mockResolvedValue({ data: groups })
    mocks.previewRecipients.mockResolvedValue({ data: { estimatedRecipientCount: 3 } })
    mocks.sendNotification.mockResolvedValue({ data: { estimatedRecipientCount: 3 } })
  })
  afterEach(() => localStorage.clear())

  it('タイトルが空なら送らない', async () => {
    const wrapper = await mountSender()
    await sendButton(wrapper).trigger('click')
    await flushPromises()
    expect(mocks.sendNotification).not.toHaveBeenCalled()
    expect(mocks.toastAdd).toHaveBeenCalledWith(expect.objectContaining({ severity: 'warn' }))
  })

  it('宛先指定で宛先が無ければ送らない', async () => {
    const wrapper = await mountSender()
    audienceSelect(wrapper).vm.$emit('update:modelValue', 'TARGETS')
    titleInput(wrapper).vm.$emit('update:modelValue', '件名')
    await flushPromises()
    expect(sendButton(wrapper).props('disabled')).toBe(true)
    // ボタンの無効化を迂回しても onSend 側の前提確認で止まる
    sendButton(wrapper).vm.$emit('click')
    await flushPromises()
    expect(mocks.sendNotification).not.toHaveBeenCalled()
    expect(mocks.toastAdd).toHaveBeenCalledWith(expect.objectContaining({ severity: 'warn' }))
  })

  it('推定人数が 0 なら送信ボタンは無効で、押しても送らない', async () => {
    mocks.previewRecipients.mockResolvedValue({ data: { estimatedRecipientCount: 0 } })
    const wrapper = await mountSender()
    titleInput(wrapper).vm.$emit('update:modelValue', '件名')
    await flushPromises()
    expect(sendButton(wrapper).props('disabled')).toBe(true)
    sendButton(wrapper).vm.$emit('click')
    await flushPromises()
    expect(mocks.sendNotification).not.toHaveBeenCalled()
  })

  it('未確認者表示は記憶値で初期化され、送信成功で記憶される', async () => {
    localStorage.setItem('confirmable.lastUnconfirmedVisibility', 'HIDDEN')
    const wrapper = await mountSender()
    expect(visibilitySelect(wrapper).props('modelValue')).toBe('HIDDEN')
    visibilitySelect(wrapper).vm.$emit('update:modelValue', 'ALL_MEMBERS')
    titleInput(wrapper).vm.$emit('update:modelValue', '件名')
    await flushPromises()
    await sendButton(wrapper).trigger('click')
    await flushPromises()
    expect(mocks.sendNotification).toHaveBeenCalledWith('TEAM', '5', expect.objectContaining({ title: '件名', unconfirmedVisibility: 'ALL_MEMBERS' }))
    expect(localStorage.getItem('confirmable.lastUnconfirmedVisibility')).toBe('ALL_MEMBERS')
  })
})
