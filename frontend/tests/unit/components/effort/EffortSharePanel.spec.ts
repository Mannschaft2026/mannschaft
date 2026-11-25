/**
 * F06.4.1 PR-4: チーム共有ステップ（EffortSharePanel）。
 * E02 共有の段階表示・明示共有・空 allowlist 拒否 / E11 共有失敗と再試行 / E16 EFFORT_003・EFFORT_002 の誘導 /
 * E25 取消・管理者非表示の状態表示 / 0 件（共有できるチームなし）。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import EffortSharePanel from '~/components/effort/EffortSharePanel.vue'

vi.setConfig({ testTimeout: 120000, hookTimeout: 120000 })

const m = vi.hoisted(() => ({
  apiFn: vi.fn(),
  createDefinitionSnapshot: vi.fn(),
  preview: vi.fn(),
  share: vi.fn(),
  retry: vi.fn(),
  revoke: vi.fn(),
  recordGet: vi.fn(),
  getTemplates: vi.fn(),
  confirmRequire: vi.fn(),
}))
mockNuxtImport('useApi', () => () => m.apiFn)
mockNuxtImport('useEffortShareApi', () => () => ({
  createDefinitionSnapshot: m.createDefinitionSnapshot,
  preview: m.preview,
  share: m.share,
  retry: m.retry,
  revoke: m.revoke,
}))
mockNuxtImport('useEffortRecordApi', () => () => ({ get: m.recordGet }))
mockNuxtImport('useActivityApi', () => () => ({ getTemplates: m.getTemplates }))
mockNuxtImport('useConfirm', () => () => ({ require: m.confirmRequire }))

const httpError = (status: number, code?: string) =>
  Object.assign(new Error(`HTTP ${status}`), { status, data: { error: { code } } })

const sourceField = { fieldKey: 'src_min', label: '勉強時間', type: 'NUMBER', semantic: 'DURATION', unit: 'MINUTE' }
const baseRecord = (share: Record<string, unknown> | null = null) => ({
  id: 'rec-1', version: 3, contentRevision: 1, recordedOn: '2026-11-24',
  definition: { definitionUuid: 'def-1', revision: 1, fields: [sourceField] },
  values: [{ fieldKey: 'src_min', value: '30.00' }], privateNote: '秘密のメモ', share,
}) as never

const targetField = { fieldKey: 'tgt_min', label: '時間', type: 'NUMBER', semantic: 'DURATION', unit: 'MINUTE' }

async function mountPanel(record: unknown = baseRecord(), saveBeforePreview = vi.fn().mockResolvedValue(baseRecord())) {
  const w = await mountSuspended(EffortSharePanel, { props: { record: record as never, saveBeforePreview, dirty: false } })
  await flushPromises()
  return { w, saveBeforePreview }
}

async function pickTemplateAndField(w: Awaited<ReturnType<typeof mountPanel>>['w']) {
  await (w.findComponent('[data-testid="effort-share-template"]') as unknown as VueWrapper).vm.$emit('update:modelValue', 11)
  await flushPromises()
  await (w.findComponent('[data-testid="effort-share-select-tgt_min"]') as unknown as VueWrapper).vm.$emit('update:modelValue', ['tgt_min'])
  await flushPromises()
}

describe('EffortSharePanel', () => {
  beforeEach(() => {
    Object.values(m).forEach((fn) => fn.mockReset())
    m.apiFn.mockResolvedValue({ data: [{ id: 5, slug: 'tigers', name: 'タイガース' }] })
    m.getTemplates.mockResolvedValue({ data: [{ id: 11, name: '週次報告' }] })
    m.createDefinitionSnapshot.mockResolvedValue({ data: { definitionToken: 'dtok', definition: { fields: [targetField] } } })
  })

  it('所属チームが0件のときは共有できない旨を出し、共有 API は呼ばない（0件）', async () => {
    m.apiFn.mockResolvedValue({ data: [] })
    const { w } = await mountPanel()
    expect(w.find('[data-testid="effort-share-no-team"]').exists()).toBe(true)
    expect(m.createDefinitionSnapshot).not.toHaveBeenCalled()
  })

  it('チーム取得に失敗したらエラーと再試行を出す（握りつぶさない）', async () => {
    m.apiFn.mockRejectedValueOnce(httpError(500)).mockResolvedValueOnce({ data: [{ id: 5, slug: 't', name: 'T' }] })
    const { w } = await mountPanel()
    expect(w.find('[data-testid="effort-share-teams-error"]').exists()).toBe(true)
    await w.find('[data-testid="effort-share-teams-error"] button').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="effort-share-teams-error"]').exists()).toBe(false)
  })

  it('1チームなら初期選択するが、共有 API は preview 後の「この内容を共有」まで呼ばない', async () => {
    m.preview.mockResolvedValue({ data: { previewToken: 'ptok', recordVersion: 3, projection: { fields: [{ fieldKey: 'tgt_min', label: '時間', displayValue: '30 分' }] } } })
    m.share.mockResolvedValue({ data: baseRecord({ id: 's1', state: 'PENDING', version: 0 }) })
    const { w, saveBeforePreview } = await mountPanel()
    await pickTemplateAndField(w)
    expect(m.createDefinitionSnapshot).toHaveBeenCalledWith(5, 11)

    await w.find('[data-testid="effort-share-preview-btn"]').trigger('click')
    await flushPromises()
    expect(saveBeforePreview).toHaveBeenCalledTimes(1)
    expect(m.preview).toHaveBeenCalledWith('rec-1', {
      version: 3, teamId: 5, definitionToken: 'dtok',
      mappings: [{ targetFieldKey: 'tgt_min', sourceFieldKey: 'src_min', valueOverride: null }],
      selectedFieldKeys: ['tgt_min'],
    })
    expect(m.share).not.toHaveBeenCalled()
    expect(w.find('[data-testid="effort-share-preview"]').text()).toContain('30 分')
    expect(w.find('[data-testid="effort-share-preview"]').text()).not.toContain('秘密のメモ')

    await w.find('[data-testid="effort-share-confirm"]').trigger('click')
    await flushPromises()
    expect(m.share).toHaveBeenCalledTimes(1)
    const [id, body, key] = m.share.mock.calls[0]!
    expect(id).toBe('rec-1')
    expect(body).toEqual({
      version: 3, previewToken: 'ptok',
      mappings: [{ targetFieldKey: 'tgt_min', sourceFieldKey: 'src_min', valueOverride: null }],
      selectedFieldKeys: ['tgt_min'],
    })
    expect(key).toMatch(/^[0-9a-f-]{36}$/)
    expect(w.emitted('updated')).toHaveLength(1)
  })

  it('「入力へ戻る」は共有 API を呼ばない', async () => {
    m.preview.mockResolvedValue({ data: { previewToken: 'ptok', recordVersion: 3, projection: { fields: [] } } })
    const { w } = await mountPanel()
    await pickTemplateAndField(w)
    await w.find('[data-testid="effort-share-preview-btn"]').trigger('click')
    await flushPromises()
    await w.find('[data-testid="effort-share-back"]').trigger('click')
    await flushPromises()
    expect(m.share).not.toHaveBeenCalled()
    expect(w.find('[data-testid="effort-share-preview"]').exists()).toBe(false)
    expect(w.find('[data-testid="effort-share-fields"]').exists()).toBe(true)
  })

  it('共有項目を1つも選ばないときは保存も preview もせず、エラーを出す（空 allowlist）', async () => {
    const { w, saveBeforePreview } = await mountPanel()
    await (w.findComponent('[data-testid="effort-share-template"]') as unknown as VueWrapper).vm.$emit('update:modelValue', 11)
    await flushPromises()
    await w.find('[data-testid="effort-share-preview-btn"]').trigger('click')
    await flushPromises()
    expect(saveBeforePreview).not.toHaveBeenCalled()
    expect(m.preview).not.toHaveBeenCalled()
    expect(w.find('[data-testid="effort-share-error"]').text()).toBe('Select at least one field to share')
  })

  it('廃止されたテンプレート（EFFORT_002）は共有できない旨を出す', async () => {
    m.createDefinitionSnapshot.mockRejectedValue(httpError(409, 'EFFORT_002'))
    const { w } = await mountPanel()
    await (w.findComponent('[data-testid="effort-share-template"]') as unknown as VueWrapper).vm.$emit('update:modelValue', 11)
    await flushPromises()
    expect(w.find('[data-testid="effort-share-error"]').text()).toBe('This template has been retired and cannot be newly shared')
    expect(w.find('[data-testid="effort-share-fields"]').exists()).toBe(false)
  })

  it('共有失敗（FAILED）は個人保存の失敗と区別し、再試行できる', async () => {
    m.retry.mockResolvedValue({ data: baseRecord({ id: 's1', state: 'PENDING', version: 5 }) })
    const { w } = await mountPanel(baseRecord({ id: 's1', state: 'FAILED', version: 4, updatedAt: '2026-11-25T00:00:00Z' }))
    expect(w.find('[data-testid="effort-share-state-FAILED"]').exists()).toBe(true)
    await w.find('[data-testid="effort-share-retry"]').trigger('click')
    await flushPromises()
    expect(m.retry).toHaveBeenCalledWith('rec-1', 3, 4, expect.stringMatching(/^[0-9a-f-]{36}$/))
    expect(w.emitted('updated')).toHaveLength(1)
  })

  it('共有中は取消でき、確認後に取消 API を呼ぶ。取消済みは「共有し直す」を出す', async () => {
    m.confirmRequire.mockImplementation((o: { accept?: () => void }) => o.accept?.())
    m.revoke.mockResolvedValue({ data: baseRecord({ id: 's1', state: 'REVOKED', version: 6 }) })
    const { w } = await mountPanel(baseRecord({ id: 's1', state: 'VISIBLE', version: 5 }))
    await w.find('[data-testid="effort-share-revoke"]').trigger('click')
    await flushPromises()
    expect(m.confirmRequire).toHaveBeenCalledTimes(1)
    expect(m.revoke).toHaveBeenCalledWith('rec-1', 3, 5, expect.stringMatching(/^[0-9a-f-]{36}$/))

    const { w: revoked } = await mountPanel(baseRecord({ id: 's1', state: 'REVOKED', version: 6 }))
    expect(revoked.find('[data-testid="effort-share-state-REVOKED"]').exists()).toBe(true)
    expect(revoked.find('[data-testid="effort-share-again"]').exists()).toBe(true)
    expect(revoked.find('[data-testid="effort-share-revoke"]').exists()).toBe(false)
  })

  it('管理者により非表示（MODERATED）は再試行・取消・再共有の操作を出さない', async () => {
    const { w } = await mountPanel(baseRecord({ id: 's1', state: 'MODERATED', version: 6 }))
    expect(w.find('[data-testid="effort-share-state-MODERATED"]').exists()).toBe(true)
    for (const id of ['effort-share-retry', 'effort-share-revoke', 'effort-share-again', 'effort-share-team']) {
      expect(w.find(`[data-testid="${id}"]`).exists()).toBe(false)
    }
  })
})
