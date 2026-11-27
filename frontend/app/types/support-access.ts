/**
 * F10.9 サポート閲覧（本人承認制の代理ログイン）の API 型。
 *
 * BE の SupportAccess* DTO に一対一で対応する。docs/openapi.json への反映後は生成型（types/generated）へ移行する。
 */

export type SupportAccessStatus = 'PENDING' | 'APPROVED' | 'DENIED' | 'REVOKED' | 'EXPIRED' | 'CANCELLED'

/** 運営側に返す依頼・許可（本人のメールは含まない）。 */
export interface SupportAccessGrant {
  id: string
  targetUserId: number
  targetDisplayName: string
  status: SupportAccessStatus
  /** 運営が入力した依頼理由。プレーンテキストとして表示すること。 */
  requestReason: string
  referenceType: string | null
  referenceId: number | null
  requestedAt: string
  requestExpiresAt: string
  decidedAt: string | null
  expiresAt: string | null
  revokedAt: string | null
  endedReason: string | null
  guardianApprovalRequired: boolean
  guardianDecidedAt: string | null
  /** 閲覧開始済みで未終了か。 */
  sessionActive: boolean
}

/** 運営の依頼（理由 10〜1000字）。 */
export interface CreateSupportAccessGrantRequest {
  targetUserId: number
  reason: string
  referenceType?: string
  referenceId?: number
}

/** 閲覧開始の応答。 */
export interface SupportAccessSessionResponse {
  grantId: string
  targetUserId: number
  targetDisplayName: string
  expiresAt: string
}

/** 本人（または保護者）に返す依頼・許可。運営者は表示名だけ。 */
export interface MySupportAccessGrant {
  id: string
  /** TARGET=本人 / GUARDIAN=保護者 */
  role: 'TARGET' | 'GUARDIAN'
  operatorDisplayName: string
  targetDisplayName: string
  status: SupportAccessStatus
  /** 運営が入力した依頼理由。プレーンテキストとして表示し、リンク化しない。 */
  requestReason: string
  referenceType: string | null
  requestedAt: string
  requestExpiresAt: string
  decidedAt: string | null
  expiresAt: string | null
  revokedAt: string | null
  endedReason: string | null
  guardianApprovalRequired: boolean
  targetApproved: boolean
  guardianApproved: boolean
  /** いま許可・拒否できるか。 */
  canApprove: boolean
}

/** 許可時の再認証の材料（どちらか一方）。 */
export interface SupportAccessApproveRequest {
  currentPassword?: string
  /** パスキー署名の JSON（credentialId / authenticatorData / clientDataJson / signature / signCount）。 */
  passkeyAssertionJson?: string
}

/** 閲覧記録（運営者は表示名だけ）。 */
export interface SupportAccessLog {
  grantId: string
  occurredAt: string
  operatorDisplayName: string
  eventKind: string
  requestPath: string | null
  scopeType: string | null
  scopeId: number | null
}

export interface SupportAccessCursorPage<T> {
  data: T[]
  meta: { nextCursor: string | null; hasNext: boolean; limit: number }
}
