import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { computed, ref, type ComputedRef, type Ref } from 'vue'
import type { RouteLocationNormalized } from 'vue-router'
import adminConsole from '~/middleware/admin-console'
import orgRoleGuard from '~/middleware/org-role-guard'
import { useScopeNotFound } from '~/composables/useScopeNotFound'

/**
 * CMP-260917-1351: HTTP 200 / roleName=null は、読込中でも管理権限でもない。
 * ページが実際に宣言するガードの結線と、既存ガードの非同期境界を組み合わせて検証する。
 * 未所属 null の2件が修正前の red 対象。実機の越境 GET 認可はこのモック試験では代替しない。
 *
 * 拒否の仕方はガードで異なる:
 *  - admin-console（invites）: 組織トップへ戻す（従来どおり）
 *  - org-role-guard（permission-groups）: CMP-261125-1108 で、リダイレクト・トーストをやめてルートを拒否し、
 *    シェルが本文を共通の ScopeNotFound に置き換える（非所属・権限なし→見つかりません）
 */
const navigate = vi.fn()
mockNuxtImport('navigateTo', () => (...args: unknown[]) => navigate(...args))

type LoadResult = { ok: true } | { ok: false, error: Error }
let role: Ref<string | null>
let access: {
  loadPermissions: ReturnType<typeof vi.fn<() => Promise<LoadResult>>>
  isAdmin: ComputedRef<boolean>
  isAdminOrDeputy: ComputedRef<boolean>
}
mockNuxtImport('useRoleAccess', () => () => access)

const top = '/organizations/null-role-org'
const pages = [
  { name: 'invites', guard: adminConsole, deputyAllowed: true, deniesInPlace: false },
  { name: 'permission-groups', guard: orgRoleGuard, deputyAllowed: false, deniesInPlace: true },
] as const

/** 退避（拒否）されたか。admin-console は組織トップへの遷移、org-role-guard はルートの拒否で見る。 */
function expectDenied(name: string, deniesInPlace: boolean) {
  if (deniesInPlace) {
    expect(navigate).not.toHaveBeenCalled()
    expect(useScopeNotFound().isRouteDenied(`${top}/${name}`)).toBe(true)
  }
  else {
    expect(navigate).toHaveBeenCalledOnce()
    expect(navigate).toHaveBeenCalledWith(top)
  }
}
function expectAllowed(name: string) {
  expect(navigate).not.toHaveBeenCalled()
  expect(useScopeNotFound().isRouteDenied(`${top}/${name}`)).toBe(false)
}

function route(page: string): RouteLocationNormalized {
  return {
    path: `${top}/${page}`,
    fullPath: `${top}/${page}`,
    hash: '',
    params: { slug: 'null-role-org' },
    query: {},
    name: undefined,
    matched: [],
    meta: {},
    redirectedFrom: undefined,
  }
}

async function runDeclaredGuards(page: 'invites' | 'permission-groups') {
  const source = readFileSync(resolve(process.cwd(), 'app/pages/organizations/[slug]', `${page}.vue`), 'utf8')
  const declaration = source.match(/middleware:\s*(\[[^\]]*\]|'[^']*')/)
  expect(declaration, '対象ページに middleware 宣言が必要').not.toBeNull()
  const names = [...declaration![1]!.matchAll(/'([^']+)'/g)].map(match => match[1])
  for (const name of names) {
    if (name === 'auth') continue // 認証済み利用者のスコープ境界だけを扱う。
    const guard = name === 'admin-console' ? adminConsole : name === 'org-role-guard' ? orgRoleGuard : null
    expect(guard, `未検証の middleware: ${name}`).not.toBeNull()
    await guard!(route(page), route(page))
  }
}

beforeEach(() => {
  navigate.mockReset()
  for (const { name } of pages) useScopeNotFound().setRouteDenied(`${top}/${name}`, false)
  role = ref<string | null>(null)
  access = {
    loadPermissions: vi.fn<() => Promise<LoadResult>>().mockResolvedValue({ ok: true }),
    isAdmin: computed(() => role.value === 'ADMIN' || role.value === 'SYSTEM_ADMIN'),
    isAdminOrDeputy: computed(() => ['ADMIN', 'DEPUTY_ADMIN', 'SYSTEM_ADMIN'].includes(role.value ?? '')),
  }
})

describe.each(pages)('$name の null ロール境界', ({ name, guard, deputyAllowed, deniesInPlace }) => {
  it('正常に解決した未所属 null を、ページに結線されたガードが退避（拒否）させる', async () => {
    await runDeclaredGuards(name)
    expectDenied(name, deniesInPlace)
  })

  it.each(['ADMIN', 'DEPUTY_ADMIN', 'MEMBER'])('%s の既存許可範囲を維持する', async (value) => {
    role.value = value
    await guard(route(name), route(name))
    if (value === 'ADMIN' || (value === 'DEPUTY_ADMIN' && deputyAllowed)) {
      expectAllowed(name)
    }
    else {
      expectDenied(name, deniesInPlace)
    }
  })

  it('取得前の null では退避せず、正常解決後の null で初めて退避する', async () => {
    let resolve!: (result: LoadResult) => void
    access.loadPermissions.mockReturnValue(new Promise<LoadResult>((done) => { resolve = done }))
    const pending = guard(route(name), route(name))
    expect(access.loadPermissions).toHaveBeenCalledOnce()
    expectAllowed(name)
    resolve({ ok: true })
    await pending
    expectDenied(name, deniesInPlace)
  })

  it('取得失敗を未所属と混同せず、redirect ではなく 503 として伝える', async () => {
    access.loadPermissions.mockResolvedValue({ ok: false, error: new Error('権限取得失敗') })
    await expect(guard(route(name), route(name))).rejects.toMatchObject({ statusCode: 503, fatal: true })
    expect(navigate).not.toHaveBeenCalled()
  })
})
