import type { BulletinScopeType } from '~/types/bulletin'
import type { TournamentContactSpace } from '~/types/tournament'

/**
 * 大会連絡スペース（BE: ContactSpaceResponse）の解釈ヘルパー。
 *
 * 契約: 1 行 = 1 スペース。`spaceKind` が BULLETIN / CHAT を区別し、
 * `refId` は払い出し先実体 ID（BULLETIN=カテゴリID / CHAT=チャンネルID）、
 * 掲示板 API のスコープは `scopeType` + `scopeId`（大会ID / ディビジョンID）で指す。
 */

/** 掲示板スペース行を返す。無ければ null。 */
export function findBulletinSpace(spaces: TournamentContactSpace[]): TournamentContactSpace | null {
  return spaces.find(s => s.spaceKind === 'BULLETIN') ?? null
}

/** チャットスペース行を返す。無ければ null。 */
export function findChatSpace(spaces: TournamentContactSpace[]): TournamentContactSpace | null {
  return spaces.find(s => s.spaceKind === 'CHAT') ?? null
}

/**
 * 掲示板 API に渡すスコープを解決する。
 * scopeType / scopeId のいずれかが得られないときは null（掲示板 API を呼んではならない）。
 */
export function resolveBulletinScope(
  space: TournamentContactSpace | null,
): { scopeType: BulletinScopeType, scopeId: number } | null {
  if (!space || space.scopeType === undefined || space.scopeId === undefined) return null
  return { scopeType: space.scopeType, scopeId: space.scopeId }
}

/** チャットスペースのチャンネルID（refId）。得られなければ null。 */
export function resolveChatChannelId(space: TournamentContactSpace | null): number | null {
  return space?.refId ?? null
}
