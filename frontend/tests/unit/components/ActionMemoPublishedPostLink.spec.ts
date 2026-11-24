import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ActionMemoPublishedPostLink from '~/components/action-memo/ActionMemoPublishedPostLink.vue'
import type { ActionMemo } from '~/types/actionMemo'

/**
 * CMP-261123-1201: 行動メモ → 公開した個人投稿への導線（ActionMemoPublishedPostLink）。
 *
 * 契約（出陣はこの契約で実装する）:
 *   - props: memos（選択日のメモ一覧）, overridePostId?（公開応答で得た ID。再取得に失敗した間の保持用。あれば最優先）
 *   - リンク先 = overridePostId ?? memos の非 null な timelinePostId のうち最初の値（個人投稿 ID）。無ければ何も描画しない
 *   - 描画: ルート data-testid="action-memo-published-post"、リンク data-testid="action-memo-published-post-link"（<a> / href=/timeline/{id}）
 *   - 「公開済み（自分だけが見られます）」の注記 data-testid="action-memo-published-post-note"
 *
 * AC-3 未公開・0件・仮メモはリンクなし / AC-4 null 混在でも既存 ID・再公開で新 ID /
 * AC-10 link role・ラベル・キーボード到達・タップ領域 / AC-11 チーム投稿 ID へはリンクしない
 */

function makeMemo(overrides: Partial<ActionMemo> = {}): ActionMemo {
  return {
    id: 100,
    memoDate: '2026-11-20',
    content: 'memo',
    mood: null,
    relatedTodoId: null,
    timelinePostId: null,
    teamTimelinePostId: null,
    tags: [],
    createdAt: '2026-11-20T08:00:00',
    category: 'OTHER',
    durationMinutes: null,
    progressRate: null,
    completesTodo: false,
    postedTeamId: null,
    organizationId: null,
    orgVisibility: null,
    ...overrides,
  }
}

const LINK = '[data-testid="action-memo-published-post-link"]'
const ROOT = '[data-testid="action-memo-published-post"]'

describe('ActionMemoPublishedPostLink', () => {
  it('AC-2: 公開済みメモがあれば /timeline/{個人投稿ID} へのリンクを出す', async () => {
    const w = await mountSuspended(ActionMemoPublishedPostLink, {
      props: { memos: [makeMemo({ timelinePostId: 321 })] },
    })
    const link = w.find(LINK)
    expect(link.exists()).toBe(true)
    expect(link.element.tagName).toBe('A')
    expect(link.attributes('href')).toBe('/timeline/321')
    expect(w.find('[data-testid="action-memo-published-post-note"]').exists()).toBe(true)
  })

  it('AC-3: 0件ならリンクも枠も出さない', async () => {
    const w = await mountSuspended(ActionMemoPublishedPostLink, { props: { memos: [] } })
    expect(w.find(LINK).exists()).toBe(false)
    expect(w.find(ROOT).exists()).toBe(false)
  })

  it('AC-3: 全メモが未公開（timelinePostId=null）ならリンクを出さない', async () => {
    const w = await mountSuspended(ActionMemoPublishedPostLink, {
      props: { memos: [makeMemo({ id: 1 }), makeMemo({ id: 2 })] },
    })
    expect(w.find(LINK).exists()).toBe(false)
  })

  it('AC-3: オフライン送信待ちの仮メモ（負の仮 ID・未公開）だけならリンクを出さない', async () => {
    const w = await mountSuspended(ActionMemoPublishedPostLink, {
      props: { memos: [makeMemo({ id: -1 })] },
    })
    expect(w.find(LINK).exists()).toBe(false)
  })

  it('AC-4: 公開後に追加したメモ（null）が混在していても、既存の個人投稿 ID へリンクする', async () => {
    const w = await mountSuspended(ActionMemoPublishedPostLink, {
      props: {
        memos: [makeMemo({ id: 1, timelinePostId: null }), makeMemo({ id: 2, timelinePostId: 555 }), makeMemo({ id: 3, timelinePostId: null })],
      },
    })
    expect(w.get(LINK).attributes('href')).toBe('/timeline/555')
  })

  it('AC-4: 再公開で全メモが新 ID になれば新 ID へリンクする（旧 ID を指さない）', async () => {
    const w = await mountSuspended(ActionMemoPublishedPostLink, {
      props: { memos: [makeMemo({ id: 1, timelinePostId: 777 }), makeMemo({ id: 2, timelinePostId: 777 })] },
    })
    expect(w.get(LINK).attributes('href')).toBe('/timeline/777')
    await w.setProps({ memos: [makeMemo({ id: 1, timelinePostId: 888 }), makeMemo({ id: 2, timelinePostId: 888 })] })
    expect(w.get(LINK).attributes('href')).toBe('/timeline/888')
  })

  it('AC-13: overridePostId（公開応答の ID）があれば、メモが未反映でもそれを最優先でリンクする', async () => {
    const stale = await mountSuspended(ActionMemoPublishedPostLink, {
      props: { memos: [makeMemo({ timelinePostId: null })], overridePostId: 900 },
    })
    expect(stale.get(LINK).attributes('href')).toBe('/timeline/900')
    const old = await mountSuspended(ActionMemoPublishedPostLink, {
      props: { memos: [makeMemo({ timelinePostId: 800 })], overridePostId: 900 },
    })
    expect(old.get(LINK).attributes('href')).toBe('/timeline/900')
  })

  it('AC-11: チーム投稿 ID（teamTimelinePostId）だけではリンクしない', async () => {
    const w = await mountSuspended(ActionMemoPublishedPostLink, {
      props: { memos: [makeMemo({ timelinePostId: null, teamTimelinePostId: 4242, postedTeamId: 7 })] },
    })
    expect(w.find(LINK).exists()).toBe(false)
  })

  it('AC-11: 個人・チーム両方の ID があっても個人投稿 ID のリンクだけを出す', async () => {
    const w = await mountSuspended(ActionMemoPublishedPostLink, {
      props: { memos: [makeMemo({ timelinePostId: 321, teamTimelinePostId: 4242, postedTeamId: 7 })] },
    })
    expect(w.findAll('a')).toHaveLength(1)
    expect(w.html()).not.toContain('/timeline/4242')
  })

  it('AC-10: リンクは読み上げ可能なラベルを持ち、キーボードで到達でき、タップ領域が 44px 以上・アイコンは装飾扱い', async () => {
    const w = await mountSuspended(ActionMemoPublishedPostLink, {
      props: { memos: [makeMemo({ timelinePostId: 321 })] },
    })
    const link = w.get(LINK)
    expect(link.text().trim().length).toBeGreaterThan(0)
    expect(link.attributes('tabindex')).not.toBe('-1')
    expect(link.attributes('aria-hidden')).toBeUndefined()
    expect(link.classes()).toContain('min-h-11')
    expect(link.find('i[aria-hidden="true"]').exists()).toBe(true)
  })
})
