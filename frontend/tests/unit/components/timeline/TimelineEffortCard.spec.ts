/**
 * F06.4.1 PR-4: 努力由来のタイムライン投稿カード（E06・E12・E16）。
 * 反応・返信・再投稿・ブックマーク・メニュー（ピン・削除・編集）を出さず、「活動履歴で見る」だけを出す。
 */
import { describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import TimelinePostCard from '~/components/timeline/TimelinePostCard.vue'
import type { TimelinePostResponse } from '~/types/timeline'

vi.setConfig({ testTimeout: 120000, hookTimeout: 120000 })

const post = (content: string, scope: Record<string, unknown> = { scopeType: 'TEAM', scopeId: '7', slug: 'tigers', name: 'タイガース' }) => ({
  id: 100,
  scope,
  author: { userId: 3, socialProfileId: null, postedAsType: null, postedAsId: null },
  user: { id: 3, displayName: '山田', avatarUrl: null },
  content: { content, isPinned: false },
  stats: { replyCount: 0, repostCount: 0 },
  mitayo: false,
  mitayoCount: 0,
  isBookmarked: false,
  attachments: [],
}) as unknown as TimelinePostResponse

describe('努力由来のタイムラインカード', () => {
  it('通常の操作（みたよ・返信・再投稿・ブックマーク・メニュー）を出さず、活動履歴へのリンクだけを出す', async () => {
    const w = await mountSuspended(TimelinePostCard, {
      props: { post: post('effort.share'), canPin: true, canDeleteOthers: true },
      route: '/teams/tigers/timeline',
    })
    await flushPromises()
    expect(w.find('[data-testid="timeline-effort-card"]').exists()).toBe(true)
    for (const id of ['team-timeline-like', 'team-timeline-reply-btn', 'team-timeline-post-menu', 'team-timeline-post']) {
      expect(w.find(`[data-testid="${id}"]`).exists()).toBe(false)
    }
    expect(w.text()).toContain('山田 shared an effort record')
    const link = w.find('[data-testid="timeline-effort-link"]')
    expect(link.text()).toContain('View in activity history')
    expect(link.attributes('href')).toBe('/teams/tigers/effort-records')
  })

  it('個人集約タイムライン（チーム画面以外）では投稿の scope.slug でリンクする', async () => {
    const w = await mountSuspended(TimelinePostCard, {
      props: { post: post('effort.share', { scopeType: 'TEAM', scopeId: '9', slug: 'lions', name: 'ライオンズ' }) },
      route: '/timeline',
    })
    await flushPromises()
    expect(w.find('[data-testid="timeline-effort-link"]').attributes('href')).toBe('/teams/lions/effort-records')
  })

  it('通常の投稿は従来どおりの操作を出す（非劣化）', async () => {
    const w = await mountSuspended(TimelinePostCard, { props: { post: post('こんにちは'), canPin: true }, route: '/teams/tigers/timeline' })
    await flushPromises()
    expect(w.find('[data-testid="timeline-effort-card"]').exists()).toBe(false)
    expect(w.find('[data-testid="team-timeline-reply-btn"]').exists()).toBe(true)
  })
})
