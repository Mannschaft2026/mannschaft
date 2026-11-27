/**
 * F10.9 サポート閲覧（本人承認制の代理ログイン）の API 型。
 *
 * 形は docs/openapi.json 由来の生成型（types/generated）を正とし、ここでは列挙値（status・role）の絞り込みと
 * 画面用の別名だけを置く。日時（requestedAt・expiresAt 等）は UTC の ISO-8601 Instant（`…Z`）の文字列で返る。
 */
import type { components } from '~/types/generated'

type Schemas = components['schemas']

export type SupportAccessStatus = 'PENDING' | 'APPROVED' | 'DENIED' | 'REVOKED' | 'EXPIRED' | 'CANCELLED'

/** 運営側に返す依頼・許可（本人のメールは含まない）。 */
export type SupportAccessGrant = Omit<Schemas['SupportAccessGrantResponse'], 'status'> & {
  status: SupportAccessStatus
}

/** 運営の依頼（理由 10〜1000字）。 */
export type CreateSupportAccessGrantRequest = Schemas['CreateSupportAccessGrantRequest']

/** 閲覧開始の応答。 */
export type SupportAccessSessionResponse = Schemas['SupportAccessSessionResponse']

/** 本人（または保護者）に返す依頼・許可。運営者は表示名だけ。 */
export type MySupportAccessGrant = Omit<Schemas['MySupportAccessGrantResponse'], 'status' | 'role'> & {
  status: SupportAccessStatus
  /** TARGET=本人 / GUARDIAN=保護者 */
  role: 'TARGET' | 'GUARDIAN'
}

/** 許可時の再認証の材料（どちらか一方）。 */
export type SupportAccessApproveRequest = Schemas['SupportAccessApproveRequest']

/** 閲覧記録（運営者は表示名だけ）。 */
export type SupportAccessLog = Schemas['SupportAccessLogView']

export interface SupportAccessCursorPage<T> {
  data: T[]
  meta: { nextCursor: string | null; hasNext: boolean; limit: number }
}
