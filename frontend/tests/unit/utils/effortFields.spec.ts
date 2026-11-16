// @vitest-environment node
/**
 * F06.4.1 個人の頑張り記録: snapshot 入力 adapter（AC-64）・原本 PUT 再送 409 の照合（AC-65）・期間/グラフ許可表。
 */
import { describe, expect, it } from 'vitest'
import {
  buildEffortFieldValues,
  fromEffortWireValue,
  reconcileUpdateAfter409,
  restoreEffortInputs,
  toEffortFormFields,
  toEffortWireValue,
  type EffortInputField,
  type EffortRecord,
} from '~/utils/effortFields'
import { listEffortChartTargets, resolveEffortPeriod } from '~/utils/effortPeriod'

const ALL_TYPES: EffortInputField[] = [
  { fieldKey: 'n', type: 'NUMBER' },
  { fieldKey: 'c', type: 'CHECKBOX' },
  { fieldKey: 'dt', type: 'DATETIME' },
  { fieldKey: 'd', type: 'DATE' },
  { fieldKey: 'ta', type: 'TEXTAREA' },
  { fieldKey: 'tx', type: 'TEXT' },
  { fieldKey: 'sel', type: 'SELECT' },
]

describe('AC-64 snapshot 入力 adapter', () => {
  it('全 field を文字列 / null で全件送る（未入力も落とさず null で送る）', () => {
    const out = buildEffortFieldValues(ALL_TYPES, {})
    expect(out).toHaveLength(ALL_TYPES.length)
    expect(out.every((v) => v.value === null)).toBe(true)
    expect(out.map((v) => v.fieldKey)).toEqual(ALL_TYPES.map((f) => f.fieldKey))
  })

  it('NUMBER は文字列化し、0 は "0"（null にしない）。空・NaN は null', () => {
    expect(toEffortWireValue('NUMBER', 0)).toBe('0')
    expect(toEffortWireValue('NUMBER', 60.5)).toBe('60.5')
    expect(toEffortWireValue('NUMBER', null)).toBeNull()
    expect(toEffortWireValue('NUMBER', '')).toBeNull()
    expect(toEffortWireValue('NUMBER', Number.NaN)).toBeNull()
  })

  it('CHECKBOX は "true"/"false"、未操作は null', () => {
    expect(toEffortWireValue('CHECKBOX', true)).toBe('true')
    expect(toEffortWireValue('CHECKBOX', false)).toBe('false')
    expect(toEffortWireValue('CHECKBOX', null)).toBeNull()
  })

  it('DATETIME はオフセット付き ISO8601（UTC 正規化）', () => {
    const wire = toEffortWireValue('DATETIME', new Date(Date.UTC(2026, 10, 15, 9, 30, 0)))
    expect(wire).toBe('2026-11-15T09:30:00.000Z')
    expect(wire).toMatch(/(Z|[+-]\d{2}:\d{2})$/)
  })

  it('DATE は暦日 YYYY-MM-DD（UTC ずれなし）', () => {
    expect(toEffortWireValue('DATE', new Date(2026, 0, 5))).toBe('2026-01-05')
  })

  it('TEXTAREA は TEXT へ正規化（CRLF→LF・前後空白除去・空は null）', () => {
    expect(toEffortWireValue('TEXTAREA', '  a\r\nb\rc \n')).toBe('a\nb\nc')
    expect(toEffortWireValue('TEXTAREA', '   ')).toBeNull()
    expect(toEffortWireValue('TEXT', ' 数学 ')).toBe('数学')
  })

  it('受信値を入力部品用の値へ復元する（null 保持・型別）', () => {
    const restored = restoreEffortInputs(ALL_TYPES, [
      { fieldKey: 'n', value: '60.50' },
      { fieldKey: 'c', value: 'false' },
      { fieldKey: 'dt', value: '2026-11-15T09:30:00Z' },
      { fieldKey: 'd', value: '2026-11-15' },
      { fieldKey: 'ta', value: 'x\ny' },
      { fieldKey: 'tx', value: null },
    ])
    expect(restored.n).toBe(60.5)
    expect(restored.c).toBe(false)
    expect((restored.dt as Date).toISOString()).toBe('2026-11-15T09:30:00.000Z')
    const d = restored.d as Date
    expect([d.getFullYear(), d.getMonth(), d.getDate()]).toEqual([2026, 10, 15])
    expect(restored.ta).toBe('x\ny')
    expect(restored.tx).toBeNull()
    expect(restored.sel).toBeNull() // 受信に無い field も null
    expect(fromEffortWireValue('NUMBER', 'abc')).toBeNull()
  })

  it('往復で値が変わらない（送信→復元→送信）', () => {
    const inputs = { n: 12, c: true, dt: new Date(Date.UTC(2026, 0, 1, 0, 0, 0)), d: new Date(2026, 0, 1), ta: 'a\nb', tx: 'x', sel: 'k' }
    const wire = buildEffortFieldValues(ALL_TYPES, inputs)
    const again = buildEffortFieldValues(ALL_TYPES, restoreEffortInputs(ALL_TYPES, wire))
    expect(again).toEqual(wire)
  })

  it('既存入力部品向けの変換: unit NONE は出さず、SELECT の選択肢は option key', () => {
    const f = toEffortFormFields(
      [
        { fieldKey: 'duration', type: 'NUMBER', unit: 'MINUTE', required: true, aggregation: 'SUM', label: '時間' },
        { fieldKey: 'subject', type: 'SELECT', unit: 'NONE', options: [{ key: 'math', label: '数学' }] },
      ],
      (x) => x.label ?? '',
    )
    expect(f[0]).toMatchObject({ fieldKey: 'duration', unit: 'MINUTE', isRequired: true, isAggregatable: true })
    expect(f[1]?.unit).toBeNull()
    expect(f[1]?.optionsJson).toBe('["math"]')
  })

  it('NUMBER の scale を入力部品へ渡す（体重 scale 2 で小数入力できる）。NUMBER 以外は null', () => {
    const f = toEffortFormFields(
      [
        { fieldKey: 'weight', type: 'NUMBER', scale: 2 },
        { fieldKey: 'duration', type: 'NUMBER' },
        { fieldKey: 'memo', type: 'TEXT', scale: 2 },
      ],
      (x) => x.fieldKey ?? '',
    )
    expect(f[0]?.scale).toBe(2)
    expect(f[1]?.scale).toBeNull()
    expect(f[2]?.scale).toBeNull()
  })
})

describe('AC-65 原本 PUT 応答消失 → 再送 409 の照合', () => {
  const fields: EffortInputField[] = [{ fieldKey: 'duration', type: 'NUMBER' }, { fieldKey: 'subject', type: 'TEXT' }]
  const sent = {
    version: 3,
    recordedOn: '2026-11-15',
    values: [{ fieldKey: 'duration', value: '60' }, { fieldKey: 'subject', value: '数学' }],
    privateNote: null,
  }
  const base: EffortRecord = {
    version: 4,
    recordedOn: '2026-11-15',
    values: [{ fieldKey: 'duration', value: '60.00' }, { fieldKey: 'subject', value: '数学' }],
  }

  it('version が進み内容が一致 → 保存済み（"60" と "60.00" は同値）', () => {
    expect(reconcileUpdateAfter409(fields, sent, base)).toBe('SAVED')
  })

  it('version が進んでいて内容が違う → 競合（別編集）', () => {
    const other = { ...base, values: [{ fieldKey: 'duration', value: '90' }, { fieldKey: 'subject', value: '数学' }] }
    expect(reconcileUpdateAfter409(fields, sent, other)).toBe('CONFLICT')
  })

  it('記録日・私的メモが違っても競合', () => {
    expect(reconcileUpdateAfter409(fields, sent, { ...base, recordedOn: '2026-11-14' })).toBe('CONFLICT')
    expect(reconcileUpdateAfter409(fields, sent, { ...base, privateNote: 'x' })).toBe('CONFLICT')
  })

  it('version が進んでいない（自分の PUT は未反映）なら一致していても保存済みとは言わない', () => {
    expect(reconcileUpdateAfter409(fields, sent, { ...base, version: 3 })).toBe('CONFLICT')
  })

  it('null と値は区別する（null を 0 とみなさない）', () => {
    const nullSent = { ...sent, values: [{ fieldKey: 'duration', value: null }, { fieldKey: 'subject', value: null }] }
    const zero = { ...base, values: [{ fieldKey: 'duration', value: '0.00' }, { fieldKey: 'subject', value: null }] }
    expect(reconcileUpdateAfter409(fields, nullSent, zero)).toBe('CONFLICT')
  })
})

describe('期間とグラフ許可表', () => {
  const anchor = new Date(2026, 10, 18) // 2026-11-18 水曜

  it('週は月曜〜日曜・DAY、月は暦月・DAY、年は 1/1〜12/31・MONTH', () => {
    expect(resolveEffortPeriod('week', anchor)).toEqual({ dateFrom: '2026-11-16', dateTo: '2026-11-22', grain: 'DAY' })
    expect(resolveEffortPeriod('month', anchor)).toEqual({ dateFrom: '2026-11-01', dateTo: '2026-11-30', grain: 'DAY' })
    expect(resolveEffortPeriod('year', anchor)).toEqual({ dateFrom: '2026-01-01', dateTo: '2026-12-31', grain: 'MONTH' })
  })

  it('閏日を含む月・前の期間への移動', () => {
    expect(resolveEffortPeriod('month', new Date(2028, 1, 10))).toEqual({ dateFrom: '2028-02-01', dateTo: '2028-02-29', grain: 'DAY' })
    expect(resolveEffortPeriod('week', anchor, -1)?.dateFrom).toBe('2026-11-09')
    expect(resolveEffortPeriod('year', anchor, -1)?.dateFrom).toBe('2025-01-01')
  })

  it('任意は 62 日以内 DAY・63〜366 日 MONTH・不正は null', () => {
    expect(resolveEffortPeriod('custom', anchor, 0, { from: new Date(2026, 0, 1), to: new Date(2026, 2, 3) })?.grain).toBe('DAY') // 62 日
    expect(resolveEffortPeriod('custom', anchor, 0, { from: new Date(2026, 0, 1), to: new Date(2026, 2, 4) })?.grain).toBe('MONTH') // 63 日
    expect(resolveEffortPeriod('custom', anchor, 0, { from: new Date(2026, 0, 1), to: new Date(2026, 11, 31) })?.grain).toBe('MONTH') // 365 日
    expect(resolveEffortPeriod('custom', anchor, 0, { from: new Date(2024, 0, 1), to: new Date(2025, 0, 1) })).toBeNull() // 367 日
    expect(resolveEffortPeriod('custom', anchor, 0, { from: new Date(2026, 1, 1), to: new Date(2026, 0, 1) })).toBeNull()
    expect(resolveEffortPeriod('custom', anchor, 0, { from: null, to: null })).toBeNull()
  })

  it('グラフ許可表: 勉強=BAR+PIE、運動=BAR のみ、体重=LINE のみ、強度・科目は不可', () => {
    const study = { durationFieldKey: 'duration', subjectFieldKey: 'subject', fields: [{ fieldKey: 'subject', semantic: 'SUBJECT' }, { fieldKey: 'duration', semantic: 'DURATION' }] }
    expect(listEffortChartTargets(study)).toEqual([{ chart: 'BAR', fieldKey: 'duration' }, { chart: 'PIE', fieldKey: 'duration' }])
    const exercise = { durationFieldKey: 'duration', subjectFieldKey: null, fields: [{ fieldKey: 'duration', semantic: 'DURATION' }, { fieldKey: 'intensity', semantic: 'INTENSITY' }, { fieldKey: 'menu', semantic: 'MENU' }] }
    expect(listEffortChartTargets(exercise)).toEqual([{ chart: 'BAR', fieldKey: 'duration' }])
    expect(listEffortChartTargets({ fields: [{ fieldKey: 'weight', semantic: 'WEIGHT' }] })).toEqual([{ chart: 'LINE', fieldKey: 'weight' }])
    expect(listEffortChartTargets({ fields: [{ fieldKey: 'x', semantic: 'NUMBER', aggregation: 'NONE' }] })).toEqual([])
  })
})
