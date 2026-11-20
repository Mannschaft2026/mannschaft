import { describe, it, expect } from 'vitest'
import type { TournamentContactSpace } from '~/types/tournament'
import {
  findBulletinSpace,
  findChatSpace,
  resolveBulletinScope,
  resolveChatChannelId,
} from '~/utils/tournamentContactSpace'

// BE の ContactSpaceResponse（id / scopeType / scopeId / spaceKind / refId / isPublic）と同形
const bulletin: TournamentContactSpace = {
  id: 'b1',
  scopeType: 'TOURNAMENT',
  scopeId: 7,
  spaceKind: 'BULLETIN',
  refId: 501,
  isPublic: true,
}
const chat: TournamentContactSpace = {
  id: 'c1',
  scopeType: 'TOURNAMENT',
  scopeId: 7,
  spaceKind: 'CHAT',
  refId: 902,
  isPublic: true,
}

describe('tournamentContactSpace', () => {
  it('spaceKind=BULLETIN の行だけを掲示板スペースとして選ぶ（チャット行は選ばない）', () => {
    expect(findBulletinSpace([chat, bulletin])).toEqual(bulletin)
    expect(findBulletinSpace([chat])).toBeNull()
  })

  it('掲示板 API のスコープは契約どおり scopeType / scopeId から解決し、undefined を渡さない', () => {
    expect(resolveBulletinScope(bulletin)).toEqual({ scopeType: 'TOURNAMENT', scopeId: 7 })
    expect(
      resolveBulletinScope({ ...bulletin, scopeType: 'TOURNAMENT_DIVISION', scopeId: 31 }),
    ).toEqual({ scopeType: 'TOURNAMENT_DIVISION', scopeId: 31 })
  })

  it('scopeId が得られないときは null を返し、掲示板 API を空のまま呼ばせない', () => {
    expect(resolveBulletinScope({ ...bulletin, scopeId: undefined })).toBeNull()
    expect(resolveBulletinScope(null)).toBeNull()
  })

  it('チャットは spaceKind=CHAT の refId をチャンネルIDとして読む', () => {
    expect(findChatSpace([bulletin, chat])).toEqual(chat)
    expect(resolveChatChannelId(chat)).toBe(902)
    expect(resolveChatChannelId({ ...chat, refId: undefined })).toBeNull()
    expect(resolveChatChannelId(null)).toBeNull()
  })
})
