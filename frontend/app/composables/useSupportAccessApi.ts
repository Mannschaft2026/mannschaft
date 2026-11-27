import type { ApiResponse } from '~/types/api'
import type {
  CreateSupportAccessGrantRequest,
  MySupportAccessGrant,
  SupportAccessApproveRequest,
  SupportAccessCursorPage,
  SupportAccessGrant,
  SupportAccessLog,
  SupportAccessSessionResponse,
  SupportAccessStatus,
} from '~/types/support-access'

const OPERATOR_BASE = '/api/v1/system-admin/support-access'
const MY_BASE = '/api/v1/me/support-access'

/**
 * F10.9 サポート閲覧 API（運営側 /system-admin/support-access・本人側 /me/support-access）。
 * 代理閲覧中は本人側 API を BE が拒否する（自己許可の抜け道を塞ぐ）。運営側は useApi が grant ID を付けない。
 */
export function useSupportAccessApi() {
  const api = useApi()

  // ─── 運営側 ───
  async function createGrant(body: CreateSupportAccessGrantRequest) {
    return api<ApiResponse<SupportAccessGrant>>(`${OPERATOR_BASE}/grants`, { method: 'POST', body })
  }

  async function listOperatorGrants(params: { status?: SupportAccessStatus; targetUserId?: number; cursor?: string | null; limit?: number } = {}) {
    return api<SupportAccessCursorPage<SupportAccessGrant>>(`${OPERATOR_BASE}/grants`, {
      query: { status: params.status, targetUserId: params.targetUserId, cursor: params.cursor ?? undefined, limit: params.limit },
    })
  }

  async function getOperatorGrant(id: string) {
    return api<ApiResponse<SupportAccessGrant>>(`${OPERATOR_BASE}/grants/${id}`)
  }

  async function cancelGrant(id: string) {
    return api<ApiResponse<SupportAccessGrant>>(`${OPERATOR_BASE}/grants/${id}/cancel`, { method: 'POST' })
  }

  async function startSession(id: string) {
    return api<ApiResponse<SupportAccessSessionResponse>>(`${OPERATOR_BASE}/grants/${id}/sessions`, { method: 'POST' })
  }

  async function listGrantLogs(id: string, cursor?: string | null) {
    return api<SupportAccessCursorPage<SupportAccessLog>>(`${OPERATOR_BASE}/grants/${id}/logs`, {
      query: { cursor: cursor ?? undefined },
    })
  }

  // ─── 本人側 ───
  async function listMyGrants(params: { status?: SupportAccessStatus; cursor?: string | null; limit?: number } = {}) {
    return api<SupportAccessCursorPage<MySupportAccessGrant>>(`${MY_BASE}/grants`, {
      query: { status: params.status, cursor: params.cursor ?? undefined, limit: params.limit },
    })
  }

  async function approveGrant(id: string, body: SupportAccessApproveRequest) {
    return api<ApiResponse<MySupportAccessGrant>>(`${MY_BASE}/grants/${id}/approve`, { method: 'POST', body })
  }

  async function denyGrant(id: string, comment?: string) {
    return api<ApiResponse<MySupportAccessGrant>>(`${MY_BASE}/grants/${id}/deny`, {
      method: 'POST',
      body: comment ? { comment } : {},
    })
  }

  async function revokeGrant(id: string) {
    return api<ApiResponse<MySupportAccessGrant>>(`${MY_BASE}/grants/${id}/revoke`, { method: 'POST' })
  }

  async function listMyLogs(params: { grantId?: string; cursor?: string | null } = {}) {
    return api<SupportAccessCursorPage<SupportAccessLog>>(`${MY_BASE}/logs`, {
      query: { grantId: params.grantId, cursor: params.cursor ?? undefined },
    })
  }

  return {
    createGrant,
    listOperatorGrants,
    getOperatorGrant,
    cancelGrant,
    startSession,
    listGrantLogs,
    listMyGrants,
    approveGrant,
    denyGrant,
    revokeGrant,
    listMyLogs,
  }
}
