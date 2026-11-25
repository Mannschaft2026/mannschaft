/**
 * F06.4.1 PR-4: 共有状態の表示・再試行/取消の可否・共有項目の対応づけ・EFFORT_003 などの誘導（E02・E05・E11・E16・E25）。
 */
import { describe, expect, it } from 'vitest'
import {
  buildDefaultMappingRows,
  canRetryEffortShare,
  canRevokeEffortShare,
  compatibleSourceFields,
  effortShareErrorKey,
  effortShareStatus,
  isEffortTimelinePost,
  toMappingBody,
} from '~/utils/effortShare'

const f = (fieldKey: string, semantic: string, unit: string, type = 'NUMBER') => ({ fieldKey, semantic, unit, type }) as never

const httpError = (status: number, code?: string) =>
  Object.assign(new Error(`HTTP ${status}`), { status, data: { error: { code } } })

describe('effortShareStatus（E11・E25: 保存と共有は別結果として表示する）', () => {
  it.each([
    ['PENDING', 'effort.status.pending'],
    ['VISIBLE', 'effort.status.visible'],
    ['FAILED', 'effort.status.failed'],
    ['REVOKED', 'effort.status.revoked'],
    ['MODERATED', 'effort.status.moderated'],
  ] as const)('%s は %s を返す', (state, key) => {
    expect(effortShareStatus({ state })).toEqual({ key, noteKey: null })
  })

  it('脱退による取消は補足キーを併記する', () => {
    expect(effortShareStatus({ state: 'REVOKED', lastErrorCode: 'MEMBERSHIP_ENDED' })).toEqual({
      key: 'effort.status.revoked',
      noteKey: 'effort.status.revokedMembership',
    })
  })

  it('共有が無い（個人のみ）ときは null', () => {
    expect(effortShareStatus(null)).toBeNull()
  })
})

describe('再試行・取消の可否', () => {
  const now = Date.parse('2026-11-25T00:10:00Z')
  it('FAILED は再試行できる', () => expect(canRetryEffortShare({ state: 'FAILED' }, now)).toBe(true))
  it('PENDING は 60 秒を超えてから再試行できる', () => {
    expect(canRetryEffortShare({ state: 'PENDING', updatedAt: '2026-11-25T00:09:30Z' }, now)).toBe(false)
    expect(canRetryEffortShare({ state: 'PENDING', updatedAt: '2026-11-25T00:08:00Z' }, now)).toBe(true)
  })
  it('管理者非表示（MODERATED）は本人の再試行・取消で戻せない', () => {
    expect(canRetryEffortShare({ state: 'MODERATED' }, now)).toBe(false)
    expect(canRevokeEffortShare({ state: 'MODERATED' })).toBe(false)
  })
  it('取消済みはさらに取り消せない。共有中は取り消せる', () => {
    expect(canRevokeEffortShare({ state: 'REVOKED' })).toBe(false)
    expect(canRevokeEffortShare({ state: 'VISIBLE' })).toBe(true)
  })
})

describe('共有項目の対応づけ（E02）', () => {
  const sources = [f('s_min', 'DURATION', 'MINUTE'), f('s_kg', 'WEIGHT', 'KILOGRAM'), f('s_text', 'MENU', 'NONE', 'TEXT')]
  it('同じ semantic・unit・type の原本項目だけが候補になる', () => {
    expect(compatibleSourceFields(f('t_min', 'DURATION', 'MINUTE'), sources).map((x) => x.fieldKey)).toEqual(['s_min'])
    expect(compatibleSourceFields(f('t_hour', 'DURATION', 'HOUR'), sources)).toEqual([])
  })
  it('候補が1つに決まる項目だけ初期対応する', () => {
    const rows = buildDefaultMappingRows([f('t_min', 'DURATION', 'MINUTE'), f('t_x', 'NUMBER', 'NONE')], sources)
    expect(rows).toEqual([
      { targetFieldKey: 't_min', sourceFieldKey: 's_min', valueOverride: '' },
      { targetFieldKey: 't_x', sourceFieldKey: null, valueOverride: '' },
    ])
  })
  it('送信は sourceFieldKey と valueOverride を同時に送らない', () => {
    expect(toMappingBody([
      { targetFieldKey: 'a', sourceFieldKey: 's', valueOverride: 'ignored' },
      { targetFieldKey: 'b', sourceFieldKey: null, valueOverride: ' 30 ' },
      { targetFieldKey: 'c', sourceFieldKey: null, valueOverride: '  ' },
    ])).toEqual([
      { targetFieldKey: 'a', sourceFieldKey: 's', valueOverride: null },
      { targetFieldKey: 'b', sourceFieldKey: null, valueOverride: '30' },
      { targetFieldKey: 'c', sourceFieldKey: null, valueOverride: null },
    ])
  })
})

describe('effortShareErrorKey（E06・E16: EFFORT_003 は取消への誘導）', () => {
  it('EFFORT_003 は誘導文言のキー', () => expect(effortShareErrorKey(httpError(403, 'EFFORT_003'))).toBe('effort.error.interactionDisabled'))
  it('EFFORT_002 は廃止、EFFORT_004 は障害、EFFORT_005 は共有失敗', () => {
    expect(effortShareErrorKey(httpError(409, 'EFFORT_002'))).toBe('effort.error.templateRetired')
    expect(effortShareErrorKey(httpError(503, 'EFFORT_004'))).toBe('effort.error.teamUnavailable')
    expect(effortShareErrorKey(httpError(503, 'EFFORT_005'))).toBe('effort.error.shareFailed')
  })
  it('COMMON_003 の 409 は再確認を促す', () => expect(effortShareErrorKey(httpError(409, 'COMMON_003'))).toBe('effort.error.shareUnavailable'))
  it('分類できないエラーは null（共通のエラー処理へ回す）', () => expect(effortShareErrorKey(httpError(500))).toBeNull())
})

describe('isEffortTimelinePost（E06・E12）', () => {
  it('固定の識別子の本文だけが努力由来', () => {
    expect(isEffortTimelinePost({ content: { content: 'effort.share' } })).toBe(true)
    expect(isEffortTimelinePost({ content: { content: 'こんにちは' } })).toBe(false)
    expect(isEffortTimelinePost(null)).toBe(false)
  })
})
