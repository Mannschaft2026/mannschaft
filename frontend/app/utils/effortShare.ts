import type {
  EffortOwnerShareState,
  EffortShareMappingBody,
} from '~/composables/useEffortShareApi'
import type { EffortSnapshotField } from '~/utils/effortFields'
import { getApiErrorCode, getLoadErrorHttpStatus } from '~/utils/loadError'

/** 努力由来のタイムライン投稿の本文に BE が入れる固定の識別子（TimelineEffortSourceService#EFFORT_POST_CONTENT）。 */
export const EFFORT_POST_CONTENT = 'effort.share'

/** 60 秒を超えて PENDING のままなら明示再試行できる（API 契約 §4.1）。 */
export const EFFORT_PENDING_RETRY_AFTER_MS = 60_000

export function isEffortTimelinePost(post: { content?: { content?: string | null } | null } | null | undefined): boolean {
  return post?.content?.content === EFFORT_POST_CONTENT
}

export interface EffortShareLike {
  state?: EffortOwnerShareState
  updatedAt?: string
  lastErrorCode?: string
}

/** 共有状態の表示キー（親設計 §2.1・§4.2）。脱退による取消は補足キーを併記する。 */
export function effortShareStatus(share: EffortShareLike | null | undefined): { key: string; noteKey: string | null } | null {
  if (!share?.state) return null
  switch (share.state) {
    case 'PENDING': return { key: 'effort.status.pending', noteKey: null }
    case 'VISIBLE': return { key: 'effort.status.visible', noteKey: null }
    case 'FAILED': return { key: 'effort.status.failed', noteKey: null }
    case 'MODERATED': return { key: 'effort.status.moderated', noteKey: null }
    case 'REVOKED':
      return {
        key: 'effort.status.revoked',
        noteKey: /MEMBERSHIP/i.test(share.lastErrorCode ?? '') ? 'effort.status.revokedMembership' : null,
      }
    default: return null
  }
}

export function canRetryEffortShare(share: EffortShareLike | null | undefined, now: number = Date.now()): boolean {
  if (!share) return false
  if (share.state === 'FAILED') return true
  if (share.state !== 'PENDING') return false
  const updated = share.updatedAt ? Date.parse(share.updatedAt) : Number.NaN
  return Number.isFinite(updated) && now - updated > EFFORT_PENDING_RETRY_AFTER_MS
}

export function canRevokeEffortShare(share: EffortShareLike | null | undefined): boolean {
  return share?.state === 'PENDING' || share?.state === 'VISIBLE' || share?.state === 'FAILED'
}

/** 同じ semantic・unit・type の原本項目だけが、共有項目へ値を引き継げる候補（API 契約 §4）。 */
export function compatibleSourceFields(
  target: EffortSnapshotField,
  sources: readonly EffortSnapshotField[],
): EffortSnapshotField[] {
  return sources.filter((s) => s.semantic === target.semantic && s.unit === target.unit && s.type === target.type)
}

export interface EffortMappingRow {
  targetFieldKey: string
  /** null = 引き継がない */
  sourceFieldKey: string | null
  valueOverride: string
}

/** 共有項目ごとの初期対応。候補が1つに決まる項目だけ自動で引き継ぐ。 */
export function buildDefaultMappingRows(
  targets: readonly EffortSnapshotField[],
  sources: readonly EffortSnapshotField[],
): EffortMappingRow[] {
  return targets.map((tg) => {
    const cands = compatibleSourceFields(tg, sources)
    return {
      targetFieldKey: tg.fieldKey ?? '',
      sourceFieldKey: cands.length === 1 ? (cands[0]!.fieldKey ?? null) : null,
      valueOverride: '',
    }
  })
}

/** 送信用 mappings。sourceFieldKey と valueOverride は同時に送らない（BE が拒否する）。 */
export function toMappingBody(rows: readonly EffortMappingRow[]): EffortShareMappingBody[] {
  return rows.map((r) => {
    if (r.sourceFieldKey) return { targetFieldKey: r.targetFieldKey, sourceFieldKey: r.sourceFieldKey, valueOverride: null }
    const v = r.valueOverride.trim()
    return { targetFieldKey: r.targetFieldKey, sourceFieldKey: null, valueOverride: v === '' ? null : v }
  })
}

/** 共有エラーを利用者向けの i18n キーへ。分類できないものは null（呼び出し側が共通のエラー処理へ回す）。 */
export function effortShareErrorKey(e: unknown): string | null {
  const code = getApiErrorCode(e)
  switch (code) {
    case 'EFFORT_002': return 'effort.error.templateRetired'
    case 'EFFORT_003': return 'effort.error.interactionDisabled'
    case 'EFFORT_004': return 'effort.error.teamUnavailable'
    case 'EFFORT_005': return 'effort.error.shareFailed'
    default: break
  }
  if (getLoadErrorHttpStatus(e) === 409) return 'effort.error.shareUnavailable'
  return null
}
