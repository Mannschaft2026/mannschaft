import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import TournamentsPage from '~/pages/organizations/[slug]/tournaments/index.vue'
import type { components } from '~/types/generated'

/**
 * CMP-261123-2131: 大会作成モーダルの送信ボディが BE の CreateTournamentRequest と一致すること。
 *
 *   CONTRACT-001: 送信キーが生成型 CreateTournamentRequest のキー集合に含まれ、name（必須）・format が入る
 *   CONTRACT-002: BE が要求しない sportCategory / isPublic / title / seasonYear を送らない
 *   CONTRACT-003: season は区切り無しの年度文字列（"2026"）で送る
 *   CONTRACT-004: 400 の fieldErrors があれば、不正な欄を画面で示す
 *   CONTRACT-005: 年度入力は桁区切り無しで表示する（2,026 にならない）
 */

type CreateReq = components['schemas']['CreateTournamentRequest']
const ALLOWED_KEYS: ReadonlySet<string> = new Set<keyof CreateReq>([
  'bonusPointRules', 'description', 'drawPoints', 'endDate', 'format', 'hasDraw', 'hasExtraTime',
  'hasPenalties', 'hasSets', 'knockoutLegs', 'leagueRoundType', 'lossPoints', 'name',
  'scoreUnitLabel', 'season', 'setsToWin', 'sport', 'startDate', 'statDefs', 'templateId',
  'tiebreakers', 'visibility', 'winPoints',
])

const getTournaments = vi.fn()
const createTournament = vi.fn()
const notifySuccess = vi.fn()
const notifyError = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-1' } }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useTournamentApi', () => () => ({ getTournaments, createTournament }))
mockNuxtImport('useNotification', () => () => ({ success: notifySuccess, error: notifyError }))
mockNuxtImport('useRoleAccess', () => () => ({ isAdminOrDeputy: ref(true), loadPermissions: vi.fn() }))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))

function setNativeInput(el: Element, value: string) {
  const input = el as HTMLInputElement
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
}

async function openAndFill(name: string) {
  const wrapper = await mountSuspended(TournamentsPage)
  await flushPromises()
  const openBtn = wrapper.findAllComponents({ name: 'Button' }).find((b) => b.text().includes('大会を作成'))
  await openBtn!.trigger('click')
  await flushPromises()
  setNativeInput(document.querySelectorAll('.p-inputtext')[0]!, name)
  await flushPromises()
  return wrapper
}

async function submit(wrapper: VueWrapper) {
  const btn = wrapper
    .findAllComponents({ name: 'Button' })
    .find((b) => b.text().includes('作成') && !b.text().includes('大会を作成'))
  await btn!.trigger('click')
  await flushPromises()
}

describe('大会作成モーダル — BE CreateTournamentRequest との契約', () => {
  beforeEach(() => {
    getTournaments.mockReset().mockResolvedValue({ data: [] })
    createTournament.mockReset().mockResolvedValue({ data: { id: 1 } })
    notifySuccess.mockReset()
    notifyError.mockReset()
  })

  it('CONTRACT-001/002: 送信ボディは BE スキーマのキーのみで name・format を含む', async () => {
    const wrapper = await openAndFill('春季リーグ')
    await submit(wrapper)
    expect(createTournament).toHaveBeenCalledTimes(1)
    const [, body] = createTournament.mock.calls[0]!
    for (const k of Object.keys(body)) expect(ALLOWED_KEYS.has(k), `未知キー: ${k}`).toBe(true)
    expect(body.name).toBe('春季リーグ')
    expect(body.format).toBe('LEAGUE')
    for (const k of ['sportCategory', 'isPublic', 'title', 'seasonYear']) expect(body).not.toHaveProperty(k)
  })

  it('CONTRACT-003: season は区切り無しの年度文字列', async () => {
    const wrapper = await openAndFill('大会')
    await submit(wrapper)
    const [, body] = createTournament.mock.calls[0]!
    expect(body.season).toMatch(/^\d{4}$/)
  })

  it('CONTRACT-004: 400 fieldErrors の欄を画面に示す', async () => {
    createTournament.mockRejectedValue({
      data: { error: { code: 'COMMON_001', fieldErrors: [{ field: 'name', message: '必須です' }] } },
    })
    const wrapper = await openAndFill('大会')
    await submit(wrapper)
    const err = document.querySelector('[data-testid="create-error-name"]')
    expect(err?.textContent).toContain('必須です')
    expect(notifyError).toHaveBeenCalled()
  })

  it('CONTRACT-005: 年度入力は桁区切り無し', async () => {
    await openAndFill('大会')
    const season = document.querySelector<HTMLInputElement>('[data-testid="create-season"] input')
    expect(season?.value).toMatch(/^\d{4}$/)
  })
})
