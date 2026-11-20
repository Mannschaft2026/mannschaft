import type { components } from '~/types/generated'
import type { EffortFieldValueBody } from '~/utils/effortFields'

type Schemas = components['schemas']

export type EffortRecordResponse = Schemas['EffortOwnerRecordResponse']
export type EffortDefinitionSnapshotResponse = Schemas['EffortDefinitionSnapshot']
export type EffortStatsResult = Schemas['EffortStatsResponse']
export type EffortDeleteResult = Schemas['EffortDeleteResponse']
export type EffortRecordPageResponse = Schemas['CursorPagedResponseEffortOwnerRecordResponse']

/** 生成型の value?: string は null を表せないため、adapter の送信形（string | null）で上書きする。 */
export interface CreateEffortRecordBody {
  recordedOn: string
  recordTimezone: string
  definitionUuid: string
  definitionRevision: number
  definitionToken?: string
  values: EffortFieldValueBody[]
  privateNote?: string | null
}

export interface UpdateEffortRecordBody {
  version: number
  recordedOn: string
  values: EffortFieldValueBody[]
  privateNote?: string | null
}

export interface EffortStatsQuery {
  dateFrom: string
  dateTo: string
  definitionUuid: string
  fieldKey: string
  chart: 'BAR' | 'LINE' | 'PIE'
  grain?: 'DAY' | 'MONTH'
}

const BASE = '/api/v1/effort-records/me'

/**
 * F06.4.1 個人の頑張り記録 API（`/api/v1/effort-records/me/**`）。
 *
 * POST create / DELETE は Idempotency-Key 必須。キーは呼び出し側が操作単位で保持し、
 * 応答消失の再送では同じキーを渡す（別キーで複製しない・_api.md §4.1）。
 * 原本 PUT は version による重複検知で、再送の 409 は GET で照合する。
 */
export function useEffortRecordApi() {
  const api = useApi()

  function listPresets() {
    return api<{ data: EffortDefinitionSnapshotResponse[] }>(`${BASE}/presets`)
  }

  function create(body: CreateEffortRecordBody, idempotencyKey: string) {
    return api<{ data: EffortRecordResponse }>(BASE, {
      method: 'POST',
      body,
      headers: { 'Idempotency-Key': idempotencyKey },
    })
  }

  function list(params: { dateFrom?: string; dateTo?: string; definitionUuid?: string; cursor?: string; limit?: number }) {
    const query: Record<string, string | number> = {}
    if (params.dateFrom) query.date_from = params.dateFrom
    if (params.dateTo) query.date_to = params.dateTo
    if (params.definitionUuid) query.definition_uuid = params.definitionUuid
    if (params.cursor) query.cursor = params.cursor
    if (params.limit !== undefined) query.limit = params.limit
    return api<EffortRecordPageResponse>(BASE, { query })
  }

  function get(id: string) {
    return api<{ data: EffortRecordResponse }>(`${BASE}/${id}`)
  }

  function update(id: string, body: UpdateEffortRecordBody) {
    return api<{ data: EffortRecordResponse }>(`${BASE}/${id}`, { method: 'PUT', body })
  }

  function remove(id: string, version: number, idempotencyKey: string) {
    return api<{ data: EffortDeleteResult }>(`${BASE}/${id}`, {
      method: 'DELETE',
      query: { version },
      headers: { 'Idempotency-Key': idempotencyKey },
    })
  }

  function stats(q: EffortStatsQuery) {
    const query: Record<string, string> = {
      date_from: q.dateFrom,
      date_to: q.dateTo,
      definition_uuid: q.definitionUuid,
      field_key: q.fieldKey,
      chart: q.chart,
    }
    if (q.grain) query.grain = q.grain
    return api<{ data: EffortStatsResult }>(`${BASE}/stats`, { query })
  }

  return { listPresets, create, list, get, update, remove, stats }
}
