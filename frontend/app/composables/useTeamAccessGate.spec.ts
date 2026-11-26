// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, h, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { useTeamAccessGate } from './useTeamAccessGate'

const mocks = vi.hoisted(() => ({
  getTeam: vi.fn(),
  resolve: vi.fn(),
  navigate: vi.fn(),
  error: vi.fn(),
  route: { path: '/teams/a/files', query: {}, hash: '' },
}))
mockNuxtImport('useTeamApi', () => () => ({ getTeam: mocks.getTeam }))
mockNuxtImport('useSlugRedirect', () => () => ({ resolveSlug: mocks.resolve }))
mockNuxtImport('navigateTo', () => mocks.navigate)
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.error }))
mockNuxtImport('useRoute', () => () => mocks.route)

function mountGate(slug = ref('a')) {
  let gate!: ReturnType<typeof useTeamAccessGate>
  const wrapper = mount(defineComponent({
    setup() {
      gate = useTeamAccessGate(slug)
      return () => h('div')
    },
  }))
  return { gate, wrapper }
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.resolve.mockResolvedValue({ status: 'NOT_FOUND' })
})

describe('useTeamAccessGate（CMP-261124-0159 PR-F1 / AC-0159-88。403 の理由表示 AC-0159-86・87 は取り下げ）', () => {
  it('200 は ok になりチームを返す', async () => {
    mocks.getTeam.mockResolvedValue({ data: { id: 'a', numericId: 7 } })
    const { gate } = mountGate()
    expect(gate.state.value).toBe('loading')
    await flushPromises()
    expect(gate.state.value).toBe('ok')
    expect(gate.team.value?.numericId).toBe(7)
    expect(mocks.error).not.toHaveBeenCalled()
  })

  it('403 は 404 と同じ notFound になり、トーストを出さずチームを保持せず、slug 移転の解決もしない', async () => {
    mocks.getTeam.mockRejectedValue({ response: { status: 403 } })
    const { gate } = mountGate()
    await flushPromises()
    expect(gate.state.value).toBe('notFound')
    expect(gate.team.value).toBeNull()
    expect(mocks.error).not.toHaveBeenCalled()
    expect(mocks.resolve).not.toHaveBeenCalled()
    expect(mocks.navigate).not.toHaveBeenCalled()
  })

  it('404 で slug 移転が無ければ notFound になり、トーストを出さない', async () => {
    mocks.getTeam.mockRejectedValue({ response: { status: 404 } })
    const { gate } = mountGate()
    await flushPromises()
    expect(gate.state.value).toBe('notFound')
    expect(mocks.error).not.toHaveBeenCalled()
    expect(mocks.navigate).not.toHaveBeenCalled()
  })

  it('404 で slug が移転済みなら新 slug へ 301 replace 遷移し notFound にしない', async () => {
    mocks.getTeam.mockRejectedValue({ response: { status: 404 } })
    mocks.resolve.mockResolvedValue({ status: 'MOVED', canonicalSlug: 'b' })
    const { gate } = mountGate()
    await flushPromises()
    expect(mocks.navigate).toHaveBeenCalledExactlyOnceWith(
      { path: '/teams/b/files', query: {}, hash: '' },
      { redirectCode: 301, replace: true },
    )
    expect(gate.state.value).not.toBe('notFound')
  })

  it('5xx は error になり既存のエラー処理（トースト）を 1 回だけ呼ぶ', async () => {
    mocks.getTeam.mockRejectedValue({ response: { status: 503 } })
    const { gate } = mountGate()
    await flushPromises()
    expect(gate.state.value).toBe('error')
    expect(mocks.error).toHaveBeenCalledTimes(1)
  })

  it('再取得（retry）で error から ok に回復できる', async () => {
    mocks.getTeam.mockRejectedValueOnce({ response: { status: 503 } })
    mocks.getTeam.mockResolvedValueOnce({ data: { id: 'a', numericId: 7 } })
    const { gate } = mountGate()
    await flushPromises()
    await gate.load()
    expect(gate.state.value).toBe('ok')
  })
})
