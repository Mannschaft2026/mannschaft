import type { components } from '~/types/generated'
import { toYmd, type ActivityFieldValue } from '~/utils/activityFields'

/**
 * F06.4.1 個人の頑張り記録の snapshot 入力 adapter。
 *
 * 既存の {@link ~/utils/activityFields#buildActivityFieldValues} は「未入力は送らない・型付き値
 * （number/boolean）で送る」活動記録用。頑張り記録の API（_api.md §6）は
 * 「snapshot の全 field を全件、値は文字列 または null で送る」契約のため、
 * 型差を本ファイルで吸収する。
 *
 * - NUMBER: 文字列（10 進）。空は null（0 は "0"）
 * - CHECKBOX: "true" / "false"。未操作は null
 * - DATE: YYYY-MM-DD
 * - DATETIME: オフセット付き ISO8601（UTC 正規化）
 * - TEXTAREA: TEXT へ正規化（改行コードを LF へ統一・前後空白除去）。空は null
 * - TEXT / SELECT: 前後空白除去。空は null
 */

export type EffortSnapshotField = components['schemas']['EffortFieldDefinition']
export type EffortRecord = components['schemas']['EffortOwnerRecordResponse']
export type EffortSnapshot = components['schemas']['EffortDefinitionSnapshot']

/** adapter が扱うフィールド型（snapshot の type に、フォーム部品由来の TEXTAREA を加える） */
export type EffortInputFieldType = 'NUMBER' | 'TEXT' | 'TEXTAREA' | 'SELECT' | 'DATE' | 'DATETIME' | 'CHECKBOX'

export interface EffortInputField {
  fieldKey: string
  type: EffortInputFieldType
}

/** 送信用 1 件（値は文字列または null。null を保持して送る） */
export interface EffortFieldValueBody {
  fieldKey: string
  value: string | null
}

function isBlank(v: ActivityFieldValue): boolean {
  return v === null || v === undefined || (typeof v === 'string' && v.trim() === '')
}

/** 入力値 1 件を送信用の文字列 / null へ変換する。 */
export function toEffortWireValue(type: EffortInputFieldType, v: ActivityFieldValue): string | null {
  switch (type) {
    case 'NUMBER': {
      if (isBlank(v)) return null
      if (typeof v === 'number') return Number.isFinite(v) ? String(v) : null
      if (typeof v === 'string') return v.trim()
      return null
    }
    case 'CHECKBOX':
      return typeof v === 'boolean' ? (v ? 'true' : 'false') : null
    case 'DATE':
      return v instanceof Date && !Number.isNaN(v.getTime()) ? toYmd(v) : null
    case 'DATETIME':
      return v instanceof Date && !Number.isNaN(v.getTime()) ? v.toISOString() : null
    case 'TEXTAREA':
    case 'TEXT':
    case 'SELECT': {
      if (typeof v !== 'string') return null
      const normalized = v.replace(/\r\n?/g, '\n').trim()
      return normalized.length > 0 ? normalized : null
    }
    default:
      return null
  }
}

/** snapshot の全 field を、入力値マップから fieldValues（全件・文字列/null）へ変換する。 */
export function buildEffortFieldValues(
  fields: readonly EffortInputField[],
  inputs: Record<string, ActivityFieldValue>,
): EffortFieldValueBody[] {
  return fields.map((f) => ({ fieldKey: f.fieldKey, value: toEffortWireValue(f.type, inputs[f.fieldKey]) }))
}

/** 受信した文字列 / null を入力部品用の値へ復元する。 */
export function fromEffortWireValue(type: EffortInputFieldType, value: string | null | undefined): ActivityFieldValue {
  if (value === null || value === undefined) return null
  switch (type) {
    case 'NUMBER': {
      const n = Number(value)
      return value.trim() !== '' && Number.isFinite(n) ? n : null
    }
    case 'CHECKBOX':
      return value === 'true' ? true : value === 'false' ? false : null
    case 'DATE': {
      const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
      return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : null
    }
    case 'DATETIME': {
      const d = new Date(value)
      return Number.isNaN(d.getTime()) ? null : d
    }
    default:
      return value
  }
}

/** 受信した values を、snapshot の field 定義に沿って入力値マップへ復元する。 */
export function restoreEffortInputs(
  fields: readonly EffortInputField[],
  values: ReadonlyArray<{ fieldKey: string; value?: string | null }> | undefined,
): Record<string, ActivityFieldValue> {
  const byKey = new Map((values ?? []).map((x) => [x.fieldKey, x.value ?? null]))
  const out: Record<string, ActivityFieldValue> = {}
  for (const f of fields) out[f.fieldKey] = fromEffortWireValue(f.type, byKey.get(f.fieldKey))
  return out
}

/** snapshot の field 定義を adapter 用の型付き field 配列へ変換する（fieldKey / type 欠落は除外しない＝契約違反なので例外）。 */
export function toInputFields(fields: readonly EffortSnapshotField[] | undefined): EffortInputField[] {
  return (fields ?? []).map((f) => {
    if (!f.fieldKey || !f.type) throw new Error('snapshot の field 定義に fieldKey / type がありません')
    return { fieldKey: f.fieldKey, type: f.type }
  })
}

function sameValue(type: EffortInputFieldType, a: string | null, b: string | null): boolean {
  if (a === null || b === null) return a === b
  if (type === 'NUMBER') return Number(a) === Number(b)
  if (type === 'DATETIME') return Date.parse(a) === Date.parse(b)
  return a === b
}

/** 送信内容（API 形）と BE の保存結果が同値か。値は型別に比較する（"60.5" と "60.50"、UTC 表記ゆれを同一視）。 */
export function sameEffortContent(
  fields: readonly EffortInputField[],
  sent: { recordedOn: string; values: readonly EffortFieldValueBody[]; privateNote: string | null },
  saved: { recordedOn?: string; values?: ReadonlyArray<{ fieldKey: string; value?: string | null }>; privateNote?: string | null },
): boolean {
  if (sent.recordedOn !== saved.recordedOn) return false
  if ((sent.privateNote ?? null) !== (saved.privateNote ?? null)) return false
  const sentByKey = new Map(sent.values.map((v) => [v.fieldKey, v.value]))
  const savedByKey = new Map((saved.values ?? []).map((v) => [v.fieldKey, v.value ?? null]))
  return fields.every((f) => sameValue(f.type, sentByKey.get(f.fieldKey) ?? null, savedByKey.get(f.fieldKey) ?? null))
}

export type EffortUpdateReconcile = 'SAVED' | 'CONFLICT'

/**
 * 原本 PUT の応答消失 → 同じ version で再送して 409 になった場合の照合（_api.md §4.1）。
 * GET した現在値が「送った内容と同値 かつ version が送信 version より進んでいる」なら、
 * 自分の PUT は成功済み（SAVED = 保存済み表示）。内容が異なれば別の編集が入っている（CONFLICT）。
 */
export function reconcileUpdateAfter409(
  fields: readonly EffortInputField[],
  sent: { version: number; recordedOn: string; values: readonly EffortFieldValueBody[]; privateNote: string | null },
  current: EffortRecord,
): EffortUpdateReconcile {
  if ((current.version ?? 0) <= sent.version) return 'CONFLICT'
  return sameEffortContent(fields, sent, current) ? 'SAVED' : 'CONFLICT'
}

/** ActivityFieldInputs（既存の入力部品）へ渡すための変換結果（ActivityTemplateField 互換）。 */
export interface EffortFormField {
  id: number
  fieldKey: string
  fieldLabel: string
  fieldType: EffortInputFieldType
  isRequired: boolean
  optionsJson: string | null
  placeholder: string | null
  unit: string | null
  isAggregatable: boolean
  sortOrder: number
}

/**
 * snapshot の field 定義を、既存の入力部品 ActivityFieldInputs が受ける形へ変換する（型差の吸収）。
 * 表示ラベルは呼び出し側の resolveLabel（labelKey の i18n 解決 → snapshot の label）に委ねる。
 * 単位は NONE/空を表示しない。SELECT の選択肢は値に stable option key を使う。
 */
export function toEffortFormFields(
  fields: readonly EffortSnapshotField[] | undefined,
  resolveLabel: (f: EffortSnapshotField) => string,
): EffortFormField[] {
  return (fields ?? []).map((f, i) => {
    if (!f.fieldKey || !f.type) throw new Error('snapshot の field 定義に fieldKey / type がありません')
    const unit = f.unit && f.unit !== 'NONE' ? f.unit : null
    return {
      id: i,
      fieldKey: f.fieldKey,
      fieldLabel: resolveLabel(f),
      fieldType: f.type,
      isRequired: f.required === true,
      optionsJson: f.type === 'SELECT' ? JSON.stringify((f.options ?? []).map((o) => o.key ?? '')) : null,
      placeholder: null,
      unit,
      isAggregatable: f.aggregation === 'SUM',
      sortOrder: i,
      scale: f.type === 'NUMBER' && typeof f.scale === 'number' ? f.scale : null,
    }
  })
}
