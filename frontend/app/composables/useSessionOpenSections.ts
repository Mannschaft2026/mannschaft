import type { MaybeRefOrGetter } from 'vue'

interface UseSessionOpenSectionsOptions<K extends string> {
  /** sessionStorage のキー。null の間は読み書きしない（認証情報の確定待ちなど） */
  storageKey: MaybeRefOrGetter<string | null>
  /** 復元を許可するセクションキー。これ以外の保存値は捨てる */
  allowedKeys: readonly K[]
}

/**
 * セクション（アコーディオン）の開閉状態をタブ内（sessionStorage）で保持する。
 * 子ページへ移って戻っても、開いていたセクションを開いたまま再現するために使う。
 * 保存値は文字列配列の JSON。壊れた値や storage の例外は「保存状態を使えない」だけに縮退し、
 * 画面上の開閉は常に動く。
 */
export function useSessionOpenSections<K extends string>(options: UseSessionOpenSectionsOptions<K>) {
  const expandedKeys = ref(new Set<K>()) as Ref<Set<K>>
  const mountedKeys = ref(new Set<K>()) as Ref<Set<K>>

  function read(key: string): K[] {
    try {
      const raw = sessionStorage.getItem(key)
      if (raw === null) return []
      const parsed: unknown = JSON.parse(raw)
      if (!Array.isArray(parsed)) return []
      return options.allowedKeys.filter((allowed) => parsed.includes(allowed))
    } catch {
      // 取得失敗の偽装ではない: 保存した開閉状態は任意の UI 補助で、復元できなければ全閉が正しい初期状態
      // eslint-disable-next-line no-restricted-syntax -- 保存値が読めない場合は全閉（空配列）が正しい初期状態で、機能劣化なし
      return []
    }
  }

  function write(key: string, keys: Set<K>) {
    try {
      sessionStorage.setItem(key, JSON.stringify([...keys]))
    } catch {
      // 容量超過・storage 不可でも画面上の開閉は継続する
    }
  }

  function restore(key: string | null) {
    if (key === null) {
      // 前の組織・ユーザーの開閉状態を画面に残さない（mountedKeys は描画キャッシュなので残す）
      expandedKeys.value = new Set()
      return
    }
    const restored = read(key)
    expandedKeys.value = new Set(restored)
    mountedKeys.value = new Set([...mountedKeys.value, ...restored])
  }

  function toggle(key: K) {
    const next = new Set(expandedKeys.value)
    if (next.has(key)) next.delete(key)
    else next.add(key)
    expandedKeys.value = next
    mountedKeys.value = new Set([...mountedKeys.value, key])
    const storageKey = toValue(options.storageKey)
    if (storageKey !== null && import.meta.client) write(storageKey, next)
  }

  function isExpanded(key: K): boolean {
    return expandedKeys.value.has(key)
  }

  if (import.meta.client) {
    if (useNuxtApp().isHydrating) onMounted(() => restore(toValue(options.storageKey)))
    else restore(toValue(options.storageKey))

    // storageKey が後から確定・変化した場合は、その新しいキーで読み直す
    watch(
      () => toValue(options.storageKey),
      (key, previous) => {
        if (key !== previous) restore(key)
      },
    )
  }

  return { expandedKeys, mountedKeys, toggle, isExpanded }
}
