/**
 * N4: ScopeNavDropdown のキーボード操作・ARIA（role=menu、初期フォーカス、矢印/Home/End、Esc でトリガーへ復帰）。
 * PrimeVue Popover は body へポータル化されるため、スロットを描画するスタブに差し替えて検証する。
 */
import { afterEach, describe, expect, it } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ScopeNavDropdown from '~/components/Navigation/ScopeNavDropdown.vue'

mockNuxtImport('useScopeFoldersStore', () => () => ({
  foldersFor: () => [{ id: 1, itemScopeIds: [] }],
  customFoldersFor: () => [],
  defaultFolderFor: () => ({ id: 1, itemScopeIds: [] }),
  fetchAll: async () => {},
}))
mockNuxtImport('useTeamStore', () => () => ({
  myTeams: [{ id: 1, slug: 't1', name: 'チーム1', nickname1: null, role: 'ADMIN' }],
  fetchMyTeams: async () => {},
}))
mockNuxtImport('useOrganizationStore', () => () => ({ myOrganizations: [], fetchMyOrganizations: async () => {} }))

const PopoverStub = defineComponent({
  emits: ['show', 'hide'],
  setup(_, { slots, emit, expose }) {
    const open = ref(false)
    expose({
      toggle: () => { open.value = true; emit('show') },
      hide: () => { open.value = false; emit('hide') },
    })
    return () => (open.value ? h('div', slots.default?.()) : null)
  },
})

let wrapper: Awaited<ReturnType<typeof mountDropdown>> | undefined

async function mountDropdown() {
  return mountSuspended(ScopeNavDropdown, {
    props: { scopeType: 'TEAM', label: 'チーム' },
    attachTo: document.body,
    global: { stubs: { Popover: PopoverStub, ScopeFolderEditDialog: true } },
  })
}

async function openMenu() {
  wrapper = await mountDropdown()
  await wrapper.get('[data-testid="scope-nav-dropdown-toggle-TEAM"]').trigger('click')
  await nextTick()
  await flushPromises()
  return wrapper
}

function items(): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>('[role="menuitem"]'))
}

function press(key: string) {
  document.querySelector<HTMLElement>('[role="menu"]')!
    .dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }))
  return nextTick()
}

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe('ScopeNavDropdown キーボード操作', () => {
  it('menuitem の親に role=menu がある', async () => {
    await openMenu()
    const menu = document.querySelector('[role="menu"]')
    expect(menu).not.toBeNull()
    expect(items().length).toBeGreaterThan(0)
    for (const item of items()) expect(menu!.contains(item)).toBe(true)
  })

  it('開いたら最初の項目にフォーカスが入る', async () => {
    await openMenu()
    expect(document.activeElement).toBe(items()[0])
  })

  it('ArrowDown/ArrowUp で項目移動（端で循環）、Home/End で先頭/末尾', async () => {
    await openMenu()
    const list = items()
    await press('ArrowDown')
    expect(document.activeElement).toBe(list[1])
    await press('End')
    expect(document.activeElement).toBe(list[list.length - 1])
    await press('ArrowDown')
    expect(document.activeElement).toBe(list[0])
    await press('ArrowUp')
    expect(document.activeElement).toBe(list[list.length - 1])
    await press('Home')
    expect(document.activeElement).toBe(list[0])
  })

  it('Esc で閉じてトリガーボタンへフォーカスが戻る', async () => {
    await openMenu()
    await press('Escape')
    expect(document.querySelector('[role="menu"]')).toBeNull()
    expect(document.activeElement).toBe(document.querySelector('[data-testid="scope-nav-dropdown-toggle-TEAM"]'))
  })

  it('display:none の項目は矢印キー移動の対象から除外される', async () => {
    await openMenu()
    const list = items()
    list[1]!.style.display = 'none'
    await press('ArrowDown')
    expect(document.activeElement).toBe(list[2] ?? list[0])
    expect(document.activeElement).not.toBe(list[1])
  })
})
