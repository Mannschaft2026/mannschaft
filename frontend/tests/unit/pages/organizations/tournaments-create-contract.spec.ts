import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import TournamentsPage from '~/pages/organizations/[slug]/tournaments/index.vue'
import type { components } from '~/types/generated'
import { TOURNAMENT_SPORTS } from '~/types/tournament'
import { TOURNAMENT_VISIBILITY_LEVELS } from '~/utils/tournamentStandings'

/**
 * CMP-261123-2131: 大会作成モーダルの送信ボディが BE の CreateTournamentRequest と一致すること。
 *
 *   CONTRACT-001: 送信キーが生成型のキー集合に含まれ、name・format が入る
 *   CONTRACT-002: BE が要求しない sportCategory / isPublic / title / seasonYear を送らない
 *   CONTRACT-003: season は年度の 4 桁文字列
 *   CONTRACT-004: 400 の fieldErrors があれば、不正な欄を画面で示す
 *   CONTRACT-005: 年度入力は桁区切り無し
 *   CONTRACT-006: sport / visibility / format は BE の許容値
 *   CONTRACT-007: 年度が空なら送信せず欄にエラーを出す
 *   CONTRACT-008: 大会名が空なら送信せず欄にエラーを出す
 *   CONTRACT-009: 点数欄の上限は 255（DB は TINYINT UNSIGNED）
 *   CONTRACT-010: 再送信で古い欄エラーが消える
 *   CONTRACT-011: 未知の field は通知に出る
 *   CONTRACT-012: fieldErrors が無い失敗は error.message を通知に出す
 */

type CreateReq = components['schemas']['CreateTournamentRequest']
const ALLOWED_KEYS: ReadonlySet<string> = new Set<keyof CreateReq>([
  'bonusPointRules', 'description', 'drawPoints', 'endDate', 'format', 'hasDraw', 'hasExtraTime',
  'hasPenalties', 'hasSets', 'knockoutLegs', 'leagueRoundType', 'lossPoints', 'name',
  'scoreUnitLabel', 'season', 'setsToWin', 'sport', 'startDate', 'statDefs', 'templateId',
  'tiebreakers', 'visibility', 'winPoints',
])
/** BE の @Pattern と同じ許容値（CreateTournamentRequest.format は String。TournamentFormat の 3 値）。 */
const BE_FORMATS = ['LEAGUE', 'KNOCKOUT', 'GROUP_KNOCKOUT']
const BE_SPORT_PATTERN = /^(SOCCER|FUTSAL|BASKETBALL|VOLLEYBALL|SHOGI|GO|FIGURE_SKATING|GYMNASTICS)$/
const BE_VISIBILITY_PATTERN =
  /^(PUBLIC|SUPPORTERS_AND_ABOVE|MEMBERS_AND_ABOVE|ADMINS_AND_ABOVE|SCOPE_AFFILIATED|PARTICIPANTS_ONLY)$/

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

async function open() {
  const wrapper = await mountSuspended(TournamentsPage)
  await flushPromises()
  const openBtn = wrapper
    .findAllComponents({ name: 'Button' })
    .find((b) => b.attributes('data-testid') === 'create-open')
  await openBtn!.trigger('click')
  await flushPromises()
  return wrapper
}

async function openAndFill(name: string) {
  const wrapper = await open()
  setNativeInput(document.querySelectorAll('.p-inputtext')[0]!, name)
  await flushPromises()
  return wrapper
}

async function submit(wrapper: VueWrapper) {
  const btn = wrapper.findAllComponents({ name: 'Button' }).find((b) => b.attributes('data-testid') === 'create-submit')
  await btn!.trigger('click')
  await flushPromises()
}

function inputNumber(wrapper: VueWrapper, testid: string) {
  return wrapper.findAllComponents({ name: 'InputNumber' }).find((c) => c.attributes('data-testid') === testid)
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

  it('CONTRACT-003: season は年度の 4 桁文字列', async () => {
    const wrapper = await openAndFill('大会')
    await submit(wrapper)
    const [, body] = createTournament.mock.calls[0]!
    expect(typeof body.season).toBe('string')
    expect(body.season).toMatch(/^\d{4}$/)
  })

  it('CONTRACT-004: 400 fieldErrors の欄を画面に示す', async () => {
    createTournament.mockRejectedValue({
      data: { error: { code: 'COMMON_001', fieldErrors: [{ field: 'name', message: '必須です' }] } },
    })
    const wrapper = await openAndFill('大会')
    await submit(wrapper)
    expect(document.querySelector('[data-testid="create-error-name"]')?.textContent).toContain('必須です')
    expect(notifyError).toHaveBeenCalledTimes(1)
    // 欄ごとに出したエラーを通知で全文重ねない
    expect(notifyError.mock.calls[0]![1]).toBe('tournament.create.createFailedHint')
  })

  it('CONTRACT-005: 年度入力は桁区切り無し', async () => {
    await openAndFill('大会')
    const season = document.querySelector<HTMLInputElement>('[data-testid="create-season"] input')
    expect(season?.value).toMatch(/^\d{4}$/)
  })

  it('CONTRACT-006: sport / visibility / format は BE の許容値', async () => {
    const wrapper = await openAndFill('大会')
    await submit(wrapper)
    const [, body] = createTournament.mock.calls[0]!
    expect(body.sport).toMatch(BE_SPORT_PATTERN)
    expect(TOURNAMENT_SPORTS.every((s) => BE_SPORT_PATTERN.test(s))).toBe(true)
    expect(body.visibility).toMatch(BE_VISIBILITY_PATTERN)
    expect(TOURNAMENT_VISIBILITY_LEVELS.every((v) => BE_VISIBILITY_PATTERN.test(v))).toBe(true)
    expect(BE_FORMATS).toContain(body.format)
  })

  it('CONTRACT-007: 年度が空なら送信せず欄にエラーを出す（"null" を送らない）', async () => {
    const wrapper = await openAndFill('大会')
    inputNumber(wrapper, 'create-season')!.vm.$emit('update:modelValue', null)
    await flushPromises()
    await submit(wrapper)
    expect(createTournament).not.toHaveBeenCalled()
    expect(document.querySelector('[data-testid="create-error-season"]')?.textContent).toContain(
      'tournament.create.seasonRequired',
    )
  })

  it('CONTRACT-008: 大会名が空なら送信せず欄にエラーを出す', async () => {
    const wrapper = await open()
    await submit(wrapper)
    expect(createTournament).not.toHaveBeenCalled()
    expect(document.querySelector('[data-testid="create-error-name"]')?.textContent).toContain(
      'tournament.create.nameRequired',
    )
  })

  it('CONTRACT-009: 勝点・引分点・敗点の上限は 255', async () => {
    const wrapper = await open()
    for (const id of ['create-win-points', 'create-draw-points', 'create-loss-points']) {
      const c = inputNumber(wrapper, id)
      expect(c, id).toBeTruthy()
      expect(c!.props('max')).toBe(255)
      expect(c!.props('min')).toBe(0)
    }
  })

  it('CONTRACT-010: 再送信で古い欄エラーが消える', async () => {
    createTournament.mockRejectedValueOnce({
      data: { error: { fieldErrors: [{ field: 'name', message: '必須です' }] } },
    })
    const wrapper = await openAndFill('大会')
    await submit(wrapper)
    expect(document.querySelector('[data-testid="create-error-name"]')).toBeTruthy()
    await submit(wrapper)
    expect(document.querySelector('[data-testid="create-error-name"]')).toBeNull()
  })

  it('CONTRACT-011: 未知の field は通知に出る', async () => {
    createTournament.mockRejectedValue({
      data: { error: { fieldErrors: [{ field: 'tiebreakers[0].priority', message: '不正です' }] } },
    })
    const wrapper = await openAndFill('大会')
    await submit(wrapper)
    expect(notifyError.mock.calls[0]![1]).toContain('不正です')
  })

  it('CONTRACT-012: fieldErrors が無い失敗は error.message を通知に出す', async () => {
    createTournament.mockRejectedValue({
      status: 403,
      data: { error: { code: 'COMMON_003', message: '権限がありません' } },
    })
    const wrapper = await openAndFill('大会')
    await submit(wrapper)
    expect(notifyError.mock.calls[0]![1]).toBe('権限がありません')
  })
})
