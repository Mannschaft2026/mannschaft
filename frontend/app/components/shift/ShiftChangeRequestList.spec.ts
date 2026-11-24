import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { nextTick } from 'vue'
import { flushPromises } from '@vue/test-utils'
import ShiftChangeRequestList from './ShiftChangeRequestList.vue'
import type { ChangeRequest } from '~/types/shift'

/**
 * CMP-261010-1432 FE-a: 変更依頼一覧の AC。
 *   AC-A3 却下ダイアログの審査コメントが submitReview に渡る（楽観ロックの version も一緒に）
 *   AC-B4 本人の依頼に審査ボタンを出さない / currentUser 未解決なら審査・取り下げを出さない
 *   AC-C1 表ヘッダーに common. の生キーが出ない
 *   AC-I1 取り下げの処理中は disabled
 *   AC-I2 確定ボタンを素早く2回押しても submitReview は1回だけ
 *   AC-M3 依頼者列に名前、null は「不明なユーザー」、userId は出さない
 */
function row(over: {
  id: number
  requestedBy: number
  requesterName?: string | null
  status?: ChangeRequest['reviewInfo']['status']
}): ChangeRequest {
  return {
    id: over.id,
    version: 5,
    scheduleId: 10,
    slotId: null,
    requestInfo: { requestType: 'PRE_CONFIRM_EDIT', reason: '理由', requestedBy: over.requestedBy },
    reviewInfo: { status: over.status ?? 'OPEN', reviewerId: null, reviewComment: null, reviewedAt: null },
    timing: { expiresAt: null, createdAt: '2026-10-01T00:00:00' },
    requesterName: over.requesterName,
  }
}

const OTHERS = row({ id: 1, requestedBy: 7, requesterName: '山田花子' })
const OWN = row({ id: 2, requestedBy: 5, requesterName: '自分' })

const noopReview = () => vi.fn().mockResolvedValue({ status: 'ok' })

function btn(wrapper: Awaited<ReturnType<typeof mountSuspended>>, id: string) {
  return wrapper.find(`[data-testid="${id}"]`)
}

beforeEach(() => {
  document.body.innerHTML = ''
})

describe('ShiftChangeRequestList 審査ボタン（AC-B4）', () => {
  it('AC-B4: 他人の OPEN 依頼には審査ボタンが出て、本人の依頼には出ない（取り下げは本人にだけ出る）', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: [OTHERS, OWN], currentUserId: 5, canReview: true },
    })
    expect(btn(wrapper, 'change-request-approve-1').exists()).toBe(true)
    expect(btn(wrapper, 'change-request-reject-1').exists()).toBe(true)
    expect(btn(wrapper, 'change-request-withdraw-1').exists()).toBe(false)

    expect(btn(wrapper, 'change-request-approve-2').exists()).toBe(false)
    expect(btn(wrapper, 'change-request-reject-2').exists()).toBe(false)
    expect(btn(wrapper, 'change-request-withdraw-2').exists()).toBe(true)
  })

  it('AC-B4: currentUserId が未解決（null）なら審査・取り下げボタンをどちらも出さない', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: [OTHERS, OWN], currentUserId: null, canReview: true },
    })
    for (const id of [1, 2]) {
      expect(btn(wrapper, `change-request-approve-${id}`).exists()).toBe(false)
      expect(btn(wrapper, `change-request-reject-${id}`).exists()).toBe(false)
      expect(btn(wrapper, `change-request-withdraw-${id}`).exists()).toBe(false)
    }
  })

  it('AC-A2（部品側）: canReview が false なら他人の依頼にも審査ボタンを出さない', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: [OTHERS], currentUserId: 5, canReview: false },
    })
    expect(btn(wrapper, 'change-request-approve-1').exists()).toBe(false)
    expect(btn(wrapper, 'change-request-reject-1').exists()).toBe(false)
  })
})

describe('ShiftChangeRequestList 審査ダイアログ（AC-A3 / AC-I2）', () => {
  it('AC-A3: 却下ダイアログに入力した審査コメントと依頼の version が submitReview に渡り、成功で閉じる', async () => {
    const submitReview = vi.fn().mockResolvedValue({ status: 'ok' })
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { requests: [OTHERS], currentUserId: 5, canReview: true, submitReview },
    })
    await btn(wrapper, 'change-request-reject-1').trigger('click')
    await nextTick()

    const textarea = document.body.querySelector<HTMLTextAreaElement>('[data-testid="change-request-review-comment"]')
    expect(textarea).not.toBeNull()
    textarea!.value = '人手が足りません'
    textarea!.dispatchEvent(new Event('input'))
    await nextTick()
    document.body.querySelector<HTMLButtonElement>('[data-testid="change-request-review-confirm"]')!.click()
    await flushPromises()

    expect(submitReview).toHaveBeenCalledWith(1, 'REJECTED', '人手が足りません', 5)
    expect(document.body.querySelector('[data-testid="change-request-review-confirm"]')).toBeNull()
  })

  it('AC-I2: 確定ボタンを素早く2回押しても submitReview は1回だけ', async () => {
    const submitReview = vi.fn().mockResolvedValue({ status: 'ok' })
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { requests: [OTHERS], currentUserId: 5, canReview: true, submitReview },
    })
    await btn(wrapper, 'change-request-approve-1').trigger('click')
    await nextTick()

    const confirm = document.body.querySelector<HTMLButtonElement>('[data-testid="change-request-review-confirm"]')!
    confirm.click()
    confirm.click()
    await flushPromises()

    expect(submitReview).toHaveBeenCalledTimes(1)
  })

  it('409 で最新がまだ OPEN なら、ダイアログとコメントを残し、最新の version で再送できる', async () => {
    const submitReview = vi.fn()
      .mockResolvedValueOnce({ status: 'conflict', latest: { ...OTHERS, version: 6 } })
      .mockResolvedValueOnce({ status: 'ok' })
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { requests: [OTHERS], currentUserId: 5, canReview: true, submitReview },
    })
    await btn(wrapper, 'change-request-reject-1').trigger('click')
    await nextTick()
    const textarea = document.body.querySelector<HTMLTextAreaElement>('[data-testid="change-request-review-comment"]')!
    textarea.value = '理由'
    textarea.dispatchEvent(new Event('input'))
    await nextTick()
    const confirm = () => document.body.querySelector<HTMLButtonElement>('[data-testid="change-request-review-confirm"]')!
    confirm().click()
    await flushPromises()
    expect(confirm()).not.toBeNull()
    confirm().click()
    await flushPromises()

    expect(submitReview).toHaveBeenNthCalledWith(2, 1, 'REJECTED', '理由', 6)
  })

  it('409 で最新が審査済みなら、ダイアログを閉じる', async () => {
    const submitReview = vi.fn().mockResolvedValue({ status: 'conflict', latest: { ...OTHERS, reviewInfo: { ...OTHERS.reviewInfo, status: 'ACCEPTED' } } })
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { requests: [OTHERS], currentUserId: 5, canReview: true, submitReview },
    })
    await btn(wrapper, 'change-request-approve-1').trigger('click')
    await nextTick()
    document.body.querySelector<HTMLButtonElement>('[data-testid="change-request-review-confirm"]')!.click()
    await flushPromises()

    expect(document.body.querySelector('[data-testid="change-request-review-confirm"]')).toBeNull()
  })
})

describe('ShiftChangeRequestList 取り下げ（AC-I1）', () => {
  it('AC-I1: 処理中の依頼の取り下げボタンは disabled で、押しても withdraw を発火しない', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: [OWN], currentUserId: 5, canReview: false, processingIds: [2] },
    })
    const button = wrapper.find('[data-testid="change-request-withdraw-2"]')
    expect(button.attributes('disabled')).toBeDefined()
    await button.trigger('click')
    expect(wrapper.emitted('withdraw')).toBeUndefined()
  })

  it('AC-I1: 処理中でなければ押下で withdraw を1回発火する', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: [OWN], currentUserId: 5, canReview: false },
    })
    await wrapper.find('[data-testid="change-request-withdraw-2"]').trigger('click')
    expect(wrapper.emitted('withdraw')).toEqual([[2]])
  })
})

describe('ShiftChangeRequestList 表示（AC-C1 / AC-M3）', () => {
  it('AC-C1: 表ヘッダーに common. で始まる生キーが出ない', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: [OTHERS], currentUserId: 5, canReview: true },
    })
    expect(wrapper.text()).not.toMatch(/\bcommon\./)
    expect(wrapper.text()).toContain('Requester')
    expect(wrapper.text()).toContain('Status')
    expect(wrapper.text()).toContain('Actions')
    // 表（広幅）側のヘッダーに限定しても同じ
    const headers = wrapper.findAll('[data-testid="change-request-table"] th').map((h) => h.text())
    expect(headers).toEqual(['Requester', 'Request type', 'Status', 'Reason (optional)', 'Actions'])
  })

  it('AC-M3: 依頼者列に名前が出て、null は「不明なユーザー」。userId は表示しない', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: {
        submitReview: noopReview(),
        requests: [OTHERS, row({ id: 3, requestedBy: 90241, requesterName: null })],
        currentUserId: 5,
        canReview: false,
      },
    })
    const text = wrapper.text()
    expect(text).toContain('山田花子')
    expect(text).toContain('Unknown user')
    expect(text).not.toContain('90241')
    // 表の依頼者列（td）に限定した確認
    const cells = wrapper.findAll('[data-testid="change-request-table"] tbody tr').map((tr) => tr.findAll('td')[0]!.text())
    expect(cells).toEqual(['山田花子', 'Unknown user'])
  })
})

describe('ShiftChangeRequestList 依頼者名の補完・審査結果', () => {
  it('requesterName が無い依頼者は memberNames で補い、引けなければ「不明なユーザー」', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: {
        submitReview: noopReview(),
        requests: [row({ id: 3, requestedBy: 2, requesterName: null }), row({ id: 4, requestedBy: 3, requesterName: null })],
        currentUserId: 5,
        memberNames: new Map([[2, '佐藤太郎']]),
      },
    })
    expect(wrapper.text()).toContain('佐藤太郎')
    expect(wrapper.text()).toContain('Unknown user')
    const cells = wrapper.findAll('[data-testid="change-request-table"] tbody tr').map((tr) => tr.findAll('td')[0]!.text())
    expect(cells).toEqual(['佐藤太郎', 'Unknown user'])
  })

  it('審査済みの依頼には審査日時と審査コメントを出す', async () => {
    const reviewed = row({ id: 6, requestedBy: 5, status: 'REJECTED' })
    reviewed.reviewInfo = { ...reviewed.reviewInfo, reviewComment: '人手が足りません', reviewedAt: '2026-10-02T09:00:00' }
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: [reviewed], currentUserId: 5 },
    })
    const box = wrapper.find('[data-testid="change-request-review-6"]')
    expect(box.exists()).toBe(true)
    expect(box.text()).toContain('人手が足りません')
    // 表のステータス列（td）の中にある
    expect(wrapper.find('[data-testid="change-request-table"] td [data-testid="change-request-review-6"]').exists()).toBe(true)
  })
})

describe('ShiftChangeRequestList 見出し重複・狭幅（アリシゼーション）', () => {
  it('空状態の文言はページ見出し「変更依頼」と重複しない', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: [], currentUserId: 5 },
    })
    expect(wrapper.find('[data-testid="change-request-empty"]').text()).toBe('No change requests yet')
  })

  it('表の種別列ヘッダーは「変更依頼」ではなく専用キー、操作列は右端固定', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: [OWN], currentUserId: 5 },
    })
    const headers = wrapper.findAll('th').map((h) => h.text())
    expect(headers).toContain('Request type')
    expect(headers).not.toContain('Change request')
    // sticky は scrollable でのみ効く。DataTable に scrollable 由来のクラスが付くこと
    expect(wrapper.find('.p-datatable').classes()).toContain('p-datatable-scrollable')
    const last = wrapper.findAll('th').at(-1)!
    expect(last.classes().join(' ')).toMatch(/frozen/)
    // 表の操作列（td）に取り下げボタンが入る
    const tds = wrapper.findAll('[data-testid="change-request-table"] tbody tr td')
    expect(tds.at(-1)!.find('[data-testid="change-request-withdraw-2"]').exists()).toBe(true)
  })
})

/**
 * md 以上の表は 822px 程度の枠でも主要情報（依頼者・種別・ステータス・操作）が枠内に収まること。
 * jsdom は幅を測れないので、列順・各列の min-width・理由セルの幅制限クラスで固定する。
 */
describe('ShiftChangeRequestList 表の列順と幅（枠内に収める）', () => {
  it('列順は 依頼者・種別・ステータス・理由・操作（ステータスは理由より前、操作列は最後）', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: [OWN], currentUserId: 5 },
    })
    const headers = wrapper.findAll('[data-testid="change-request-table"] th').map((h) => h.text())
    expect(headers).toHaveLength(5)
    expect(headers.indexOf('Status')).toBe(2)
    expect(headers.indexOf('Status')).toBeLessThan(headers.indexOf('Reason (optional)'))
    const tds = wrapper.findAll('[data-testid="change-request-table"] tbody tr td')
    expect(tds[2]!.find('[data-testid="change-request-review-2"], .p-tag').exists()).toBe(true)
    expect(tds.at(-1)!.find('[data-testid="change-request-withdraw-2"]').exists()).toBe(true)
  })

  it('理由セルは幅を制限して折り返す（line-clamp-2 と max-w）。長文で表が広がらない', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: [OWN], currentUserId: 5 },
    })
    const reason = wrapper.find('[data-testid="change-request-reason-2"]')
    expect(reason.exists()).toBe(true)
    const cls = reason.classes().join(' ')
    expect(cls).toContain('line-clamp-2')
    expect(cls).toMatch(/max-w-\[/)
    expect(cls).toContain('[overflow-wrap:anywhere]')
  })

  it('各列の min-width の合計が 822px 枠に収まる目安（操作列は固定幅で他列に重ならない）', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: [OWN], currentUserId: 5 },
    })
    const widths = wrapper
      .findAll('[data-testid="change-request-table"] th')
      .map((h) => Number(/min-width:\s*(\d+)px/.exec(h.attributes('style') ?? '')?.[1] ?? 0))
    expect(widths.every((w) => w > 0)).toBe(true)
    expect(widths.reduce((a, b) => a + b, 0)).toBeLessThanOrEqual(640)
  })

  it('審査結果ブロックの幅は 12rem 以下に制限される', async () => {
    const reviewed = {
      ...row({ id: 3, requestedBy: 5, requesterName: '自分', status: 'REJECTED' }),
      reviewInfo: { status: 'REJECTED' as const, reviewerId: 9, reviewComment: '人手が足りません', reviewedAt: '2026-10-02T09:30:00' },
    }
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: [reviewed], currentUserId: 5 },
    })
    expect(wrapper.find('[data-testid="change-request-review-3"]').classes().join(' ')).toContain('max-w-[12rem]')
  })
})

/**
 * 狭幅（md 未満）はカード縦積み。jsdom は CSS を解決しないので、
 * 「カードが DOM にあり md:hidden（md 以上で隠れる）」「表は max-md:hidden（md 未満で隠れる）」と、
 * 各カードに全項目・全操作が揃っていることを確かめる。実際の見え方は Playwright で撮って確認する。
 */
describe('ShiftChangeRequestList 狭幅カード表示', () => {
  const REVIEWED = {
    ...row({ id: 3, requestedBy: 5, requesterName: '自分', status: 'REJECTED' }),
    reviewInfo: {
      status: 'REJECTED' as const,
      reviewerId: 9,
      reviewComment: '人手が足りません',
      reviewedAt: '2026-10-02T09:30:00',
    },
  }

  it('カード一覧は md 以上で隠れ、表は md 未満で隠れる', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: [OTHERS], currentUserId: 5, canReview: true },
    })
    const cards = wrapper.find('[data-testid="change-request-cards"]')
    expect(cards.exists()).toBe(true)
    expect(cards.classes()).toContain('md:hidden')
    expect(wrapper.find('[data-testid="change-request-table"]').classes()).toContain('max-md:hidden')
  })

  it('カードに依頼者・種別・理由・ステータス・審査日時とコメントが全部出る', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: [REVIEWED], currentUserId: 5 },
    })
    const card = wrapper.find('[data-testid="change-request-card-3"]')
    expect(card.exists()).toBe(true)
    const text = card.text()
    expect(text).toContain('自分')
    expect(text).toContain('理由')
    expect(text).toContain('Rejected')
    expect(text).toContain('人手が足りません')
    expect(card.find('[data-testid="change-request-card-review-3"]').text()).toContain('2026')
  })

  it('カードに承認・却下（他人の依頼）と取り下げ（本人の依頼）が出て、タップ領域は 44px 以上', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: [OTHERS, OWN], currentUserId: 5, canReview: true },
    })
    for (const id of ['change-request-card-approve-1', 'change-request-card-reject-1', 'change-request-card-withdraw-2']) {
      const b = wrapper.find(`[data-testid="${id}"]`)
      expect(b.exists(), id).toBe(true)
      expect(b.classes()).toContain('min-h-[44px]')
      expect(b.classes()).toContain('min-w-[44px]')
    }
    expect(wrapper.find('[data-testid="change-request-card-approve-2"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="change-request-card-withdraw-1"]').exists()).toBe(false)
  })

  it('カードの却下ボタンから審査ダイアログが開き、コメント付きで submitReview に渡る', async () => {
    const submitReview = noopReview()
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview, requests: [OTHERS], currentUserId: 5, canReview: true },
    })
    await wrapper.find('[data-testid="change-request-card-reject-1"]').trigger('click')
    await nextTick()
    const input = document.body.querySelector('[data-testid="change-request-review-comment"]') as HTMLTextAreaElement
    input.value = '無理です'
    input.dispatchEvent(new Event('input'))
    await nextTick()
    ;(document.body.querySelector('[data-testid="change-request-review-confirm"]') as HTMLElement).click()
    await flushPromises()
    expect(submitReview).toHaveBeenCalledWith(1, 'REJECTED', '無理です', 5)
  })

  it('カードの取り下げは withdraw を発火し、処理中は disabled', async () => {
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: [OWN], currentUserId: 5, processingIds: [] },
    })
    await wrapper.find('[data-testid="change-request-card-withdraw-2"]').trigger('click')
    expect(wrapper.emitted('withdraw')).toEqual([[2]])
    await wrapper.setProps({ processingIds: [2] })
    expect(wrapper.find('[data-testid="change-request-card-withdraw-2"]').attributes('disabled')).toBeDefined()
  })

  it('11 件以上ならカードも表と同じ 10 件でページングし、次ページで残りを出す', async () => {
    const many = Array.from({ length: 12 }, (_, i) => row({ id: 100 + i, requestedBy: 7, requesterName: `依頼者${i}` }))
    const wrapper = await mountSuspended(ShiftChangeRequestList, {
      props: { submitReview: noopReview(), requests: many, currentUserId: 5 },
    })
    expect(wrapper.findAll('[data-testid^="change-request-card-1"]').filter((c) => /^change-request-card-\d+$/.test(c.attributes('data-testid')!))).toHaveLength(10)
    const next = wrapper.find('[data-testid="change-request-cards-paginator"] .p-paginator-next')
    await next.trigger('click')
    await nextTick()
    expect(wrapper.findAll('[data-testid^="change-request-card-1"]').filter((c) => /^change-request-card-\d+$/.test(c.attributes('data-testid')!))).toHaveLength(2)
  })
})
