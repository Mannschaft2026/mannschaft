import { mountSuspended } from '@nuxt/test-utils/runtime'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import type { Ref } from 'vue'
import { useSessionOpenSections } from './useSessionOpenSections'

// 受け入れ条件 CMP-261125-2042: セクションの開閉状態をタブ内（sessionStorage）で保持する。

const ALLOWED = ['a', 'b', 'c'] as const
type Key = (typeof ALLOWED)[number]

interface Harness {
  expandedKeys: Ref<Set<Key>>
  mountedKeys: Ref<Set<Key>>
  toggle: (key: Key) => void
  isExpanded: (key: Key) => boolean
  /** setup の終了時点で復元済みだったキー */
  expandedAtSetup: Key[]
}

async function mountHarness(storageKey: Ref<string | null> | string | null) {
  let harness: Harness | undefined
  const Comp = defineComponent({
    setup() {
      const api = useSessionOpenSections<Key>({ storageKey, allowedKeys: ALLOWED })
      harness = { ...api, expandedAtSetup: [...api.expandedKeys.value] }
      return () => h('div')
    },
  })
  const wrapper = await mountSuspended(Comp)
  if (!harness) throw new Error('harness が初期化されていない')
  return { wrapper, harness }
}

function stored(key: string): unknown {
  const raw = sessionStorage.getItem(key)
  return raw === null ? null : JSON.parse(raw)
}

const KEY = 'scope-sections:organization:1:10'

describe('useSessionOpenSections', () => {
  let nuxtApp: ReturnType<typeof useNuxtApp>
  let originalHydrating: boolean

  beforeEach(() => {
    sessionStorage.clear()
    nuxtApp = useNuxtApp()
    originalHydrating = nuxtApp.isHydrating ?? false
    nuxtApp.isHydrating = false
  })

  afterEach(() => {
    nuxtApp.isHydrating = originalHydrating
    vi.restoreAllMocks()
  })

  it('AC1: toggle で開いたキーを sessionStorage に保存し、再度 toggle すると消す', async () => {
    const { harness } = await mountHarness(KEY)

    harness.toggle('a')
    expect(harness.isExpanded('a')).toBe(true)
    expect(stored(KEY)).toEqual(['a'])

    harness.toggle('b')
    expect(new Set(stored(KEY) as string[])).toEqual(new Set(['a', 'b']))

    harness.toggle('a')
    expect(harness.isExpanded('a')).toBe(false)
    expect(stored(KEY)).toEqual(['b'])
  })

  it('AC2: 保存済みの状態から作り直すと expandedKeys と mountedKeys の両方へ復元する', async () => {
    const first = await mountHarness(KEY)
    first.harness.toggle('a')
    first.harness.toggle('c')
    first.wrapper.unmount()

    const { harness } = await mountHarness(KEY)
    expect(harness.expandedKeys.value).toEqual(new Set(['a', 'c']))
    expect(harness.mountedKeys.value).toEqual(new Set(['a', 'c']))
    expect(harness.isExpanded('a')).toBe(true)
    expect(harness.isExpanded('b')).toBe(false)
  })

  it('AC3: storageKey が異なる（別の組織・別のユーザー）と状態が混ざらない', async () => {
    const orgOne = await mountHarness('scope-sections:organization:1:10')
    orgOne.harness.toggle('a')

    const orgTwo = await mountHarness('scope-sections:organization:2:10')
    expect(orgTwo.harness.expandedKeys.value.size).toBe(0)
    orgTwo.harness.toggle('b')

    const otherUser = await mountHarness('scope-sections:organization:1:11')
    expect(otherUser.harness.expandedKeys.value.size).toBe(0)

    expect(stored('scope-sections:organization:1:10')).toEqual(['a'])
    expect(stored('scope-sections:organization:2:10')).toEqual(['b'])
    expect(stored('scope-sections:organization:1:11')).toBeNull()
  })

  it('AC4: storageKey が null の間は sessionStorage を読み書きしない', async () => {
    sessionStorage.setItem('null', JSON.stringify(['a']))
    const getSpy = vi.spyOn(Storage.prototype, 'getItem')
    const setSpy = vi.spyOn(Storage.prototype, 'setItem')

    const { harness } = await mountHarness(ref<string | null>(null))
    expect(harness.expandedKeys.value.size).toBe(0)

    harness.toggle('a')
    expect(harness.isExpanded('a')).toBe(true)
    expect(getSpy).not.toHaveBeenCalled()
    expect(setSpy).not.toHaveBeenCalled()
  })

  it('AC5: 許可キー以外の値は捨てて復元する', async () => {
    sessionStorage.setItem(KEY, JSON.stringify(['a', 'unknown', 42, null, 'c']))
    const { harness } = await mountHarness(KEY)
    expect(harness.expandedKeys.value).toEqual(new Set(['a', 'c']))
    expect(harness.mountedKeys.value).toEqual(new Set(['a', 'c']))
  })

  it.each([
    ['壊れた JSON', '{not json'],
    ['配列でない JSON', JSON.stringify({ a: true })],
    ['文字列の JSON', JSON.stringify('a')],
  ])('AC5: %s は全閉で動く', async (_label, raw) => {
    sessionStorage.setItem(KEY, raw)
    const { harness } = await mountHarness(KEY)
    expect(harness.expandedKeys.value.size).toBe(0)
    expect(harness.mountedKeys.value.size).toBe(0)
    harness.toggle('b')
    expect(stored(KEY)).toEqual(['b'])
  })

  it('AC5: getItem が例外を投げても全閉で動き、例外を外へ投げない', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError')
    })
    const { harness } = await mountHarness(KEY)
    expect(harness.expandedKeys.value.size).toBe(0)
    expect(() => harness.toggle('a')).not.toThrow()
    expect(harness.isExpanded('a')).toBe(true)
  })

  it('AC5: setItem が例外を投げても toggle は例外を投げず、画面上の開閉は動く', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError')
    })
    const { harness } = await mountHarness(KEY)
    expect(() => harness.toggle('a')).not.toThrow()
    expect(harness.isExpanded('a')).toBe(true)
    expect(harness.mountedKeys.value.has('a')).toBe(true)
    expect(() => harness.toggle('a')).not.toThrow()
    expect(harness.isExpanded('a')).toBe(false)
  })

  it('AC6: ハイドレーション中でなければ setup の直後に復元済みである', async () => {
    sessionStorage.setItem(KEY, JSON.stringify(['b']))
    nuxtApp.isHydrating = false
    const { harness } = await mountHarness(KEY)
    expect(harness.expandedAtSetup).toEqual(['b'])
    expect(harness.expandedKeys.value).toEqual(new Set(['b']))
  })

  it('AC6: ハイドレーション中は setup で読まず、onMounted のあとに復元する', async () => {
    sessionStorage.setItem(KEY, JSON.stringify(['b']))
    nuxtApp.isHydrating = true
    const { harness } = await mountHarness(KEY)
    expect(harness.expandedAtSetup).toEqual([])
    expect(harness.expandedKeys.value).toEqual(new Set(['b']))
    expect(harness.mountedKeys.value).toEqual(new Set(['b']))
  })

  describe('storageKey が同じインスタンスで変化する場合', () => {
    it('(a) null → key: 保存値が読み込まれる', async () => {
      sessionStorage.setItem(KEY, JSON.stringify(['a', 'c']))
      const keyRef = ref<string | null>(null)
      const { harness } = await mountHarness(keyRef)
      expect(harness.expandedKeys.value.size).toBe(0)

      keyRef.value = KEY
      await nextTick()
      expect(harness.expandedKeys.value).toEqual(new Set(['a', 'c']))
      expect(harness.mountedKeys.value).toEqual(new Set(['a', 'c']))
    })

    it('(b) A → B（B 未保存）: 全閉になり、B へ書くのは B での操作だけで A は変わらない', async () => {
      const A = 'scope-sections:organization:1:10'
      const B = 'scope-sections:organization:2:10'
      sessionStorage.setItem(A, JSON.stringify(['a']))
      const keyRef = ref<string | null>(A)
      const { harness } = await mountHarness(keyRef)
      expect(harness.expandedKeys.value).toEqual(new Set(['a']))

      keyRef.value = B
      await nextTick()
      expect(harness.expandedKeys.value.size).toBe(0)

      harness.toggle('b')
      expect(stored(B)).toEqual(['b'])
      expect(stored(A)).toEqual(['a'])
    })

    it('(c) A → B（B 保存済み）: B の状態に差し替わる', async () => {
      const A = 'scope-sections:organization:1:10'
      const B = 'scope-sections:organization:2:10'
      sessionStorage.setItem(A, JSON.stringify(['a']))
      sessionStorage.setItem(B, JSON.stringify(['b', 'c']))
      const keyRef = ref<string | null>(A)
      const { harness } = await mountHarness(keyRef)

      keyRef.value = B
      await nextTick()
      expect(harness.expandedKeys.value).toEqual(new Set(['b', 'c']))
      expect(stored(A)).toEqual(['a'])
    })

    it('(d) A → null: 全閉になり null の間は書かない。その後 null → B で B の状態になる', async () => {
      const A = 'scope-sections:organization:1:10'
      const B = 'scope-sections:organization:2:10'
      sessionStorage.setItem(A, JSON.stringify(['a']))
      sessionStorage.setItem(B, JSON.stringify(['c']))
      const keyRef = ref<string | null>(A)
      const { harness } = await mountHarness(keyRef)

      keyRef.value = null
      await nextTick()
      expect(harness.expandedKeys.value.size).toBe(0)

      const setSpy = vi.spyOn(Storage.prototype, 'setItem')
      harness.toggle('b')
      expect(setSpy).not.toHaveBeenCalled()
      expect(stored(A)).toEqual(['a'])

      keyRef.value = B
      await nextTick()
      expect(harness.expandedKeys.value).toEqual(new Set(['c']))
    })
  })
})
