import type { components } from '~/types/generated'

type Schemas = components['schemas']

export type EffortOwnerRecord = Schemas['EffortOwnerRecordResponse']
export type EffortOwnerShareState = NonNullable<Schemas['EffortOwnerShare']['state']>
export type EffortSharedRecordResponse = Schemas['EffortSharedRecord']
export type EffortSharePreview = Schemas['EffortSharePreviewResponse']
export type EffortShareMappingBody = { targetFieldKey: string; sourceFieldKey: string | null; valueOverride: string | null }
export type EffortDefinitionToken = Schemas['EffortDefinitionTokenResponse']
export type EffortTeamDefinitionCandidate = Schemas['EffortTeamDefinitionCandidate']
export type EffortTeamRecordPage = Schemas['CursorPagedResponseEffortSharedRecord']
export type EffortModerateResult = Schemas['EffortModerateResponse']

export interface EffortPreviewBody {
  version: number
  teamId: number
  definitionToken: string
  mappings: EffortShareMappingBody[]
  selectedFieldKeys: string[]
}

export interface EffortShareBody {
  version: number
  previewToken: string
  mappings: EffortShareMappingBody[]
  selectedFieldKeys: string[]
}

export interface EffortTeamRecordQuery {
  userId?: number
  dateFrom?: string
  dateTo?: string
  definitionUuid?: string
  cursor?: string
  limit?: number
}

const ME = '/api/v1/effort-records/me'

/**
 * F06.4.1 チーム共有 API（API 契約 §4）。共有 PUT・retry・取消・管理者非表示は Idempotency-Key 必須で、
 * キーは呼び出し側が操作単位で保持する（応答消失の再送は同じキー。§4.1）。preview は冪等キー不要。
 */
export function useEffortShareApi() {
  const api = useApi()

  function createDefinitionSnapshot(teamId: number, teamTemplateId: number) {
    return api<{ data: EffortDefinitionToken }>(`${ME}/definition-snapshots`, {
      method: 'POST',
      body: { teamId, teamTemplateId },
    })
  }

  function preview(id: string, body: EffortPreviewBody) {
    return api<{ data: EffortSharePreview }>(`${ME}/${id}/team-share/preview`, { method: 'POST', body })
  }

  function share(id: string, body: EffortShareBody, idempotencyKey: string) {
    return api<{ data: EffortOwnerRecord }>(`${ME}/${id}/team-share`, {
      method: 'PUT',
      body,
      headers: { 'Idempotency-Key': idempotencyKey },
    })
  }

  function retry(id: string, version: number, shareVersion: number, idempotencyKey: string) {
    return api<{ data: EffortOwnerRecord }>(`${ME}/${id}/team-share/retry`, {
      method: 'POST',
      body: { version, shareVersion },
      headers: { 'Idempotency-Key': idempotencyKey },
    })
  }

  function revoke(id: string, version: number, shareVersion: number, idempotencyKey: string) {
    return api<{ data: EffortOwnerRecord }>(`${ME}/${id}/team-share`, {
      method: 'DELETE',
      query: { version, share_version: shareVersion },
      headers: { 'Idempotency-Key': idempotencyKey },
    })
  }

  function listTeamRecords(teamId: number, q: EffortTeamRecordQuery) {
    const query: Record<string, string | number> = {}
    if (q.userId !== undefined) query.user_id = q.userId
    if (q.dateFrom) query.date_from = q.dateFrom
    if (q.dateTo) query.date_to = q.dateTo
    if (q.definitionUuid) query.definition_uuid = q.definitionUuid
    if (q.cursor) query.cursor = q.cursor
    if (q.limit !== undefined) query.limit = q.limit
    return api<EffortTeamRecordPage>(`/api/v1/teams/${teamId}/effort-records`, { query })
  }

  function getTeamRecord(teamId: number, shareId: string) {
    return api<{ data: EffortSharedRecordResponse }>(`/api/v1/teams/${teamId}/effort-records/${shareId}`)
  }

  function moderate(teamId: number, shareId: string, version: number, idempotencyKey: string) {
    return api<{ data: EffortModerateResult }>(`/api/v1/teams/${teamId}/effort-records/${shareId}`, {
      method: 'DELETE',
      query: { version },
      headers: { 'Idempotency-Key': idempotencyKey },
    })
  }

  function listTeamDefinitions(teamId: number) {
    return api<{ data: EffortTeamDefinitionCandidate[] }>(`/api/v1/teams/${teamId}/effort-records/definitions`)
  }

  return { createDefinitionSnapshot, preview, share, retry, revoke, listTeamRecords, getTeamRecord, moderate, listTeamDefinitions }
}
