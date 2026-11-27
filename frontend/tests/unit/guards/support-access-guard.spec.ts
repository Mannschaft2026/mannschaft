// @vitest-environment node
// 戦役Z-B 試練 S13/S16 の番人: 旧・管理者変身（useAdminImpersonationStore・X-Admin-Impersonate-User-Id・
// AdminImpersonationBanner・admin.impersonation.*）が残らず、新しい画面・6言語の文言が揃う。
import { readdirSync, readFileSync, statSync, existsSync } from 'node:fs'
import { join, relative } from 'node:path'
import { describe, expect, it } from 'vitest'

const ROOT = join(__dirname, '../../..')
const APP = join(ROOT, 'app')
const LOCALES = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) return name === 'node_modules' ? [] : walk(full)
    return /\.(ts|vue)$/.test(name) ? [full] : []
  })
}

type Dict = Record<string, unknown>
function lookup(obj: Dict, path: string): unknown {
  return path.split('.').reduce<unknown>((acc, key) => (acc as Dict | undefined)?.[key], obj)
}

const REQUIRED_KEYS = [
  'supportAccess.banner.readOnly',
  'supportAccess.banner.remaining',
  'supportAccess.banner.end',
  'supportAccess.settings.title',
  'supportAccess.settings.pending',
  'supportAccess.settings.active',
  'supportAccess.settings.logs',
  'supportAccess.settings.approve',
  'supportAccess.settings.deny',
  'supportAccess.settings.revoke',
  'supportAccess.settings.reauthRequired',
  'supportAccess.admin.title',
  'supportAccess.admin.request',
  'supportAccess.admin.reason',
  'supportAccess.admin.start',
  'supportAccess.admin.cancel',
]

describe('戦役Z-B S13/S16 サポート閲覧の FE 番人', () => {
  it('S13 旧ストア・旧バナーのファイルは存在しない', () => {
    expect(existsSync(join(APP, 'stores/useAdminImpersonationStore.ts'))).toBe(false)
    expect(existsSync(join(APP, 'components/admin/AdminImpersonationBanner.vue'))).toBe(false)
  })

  it('S13 本番コード（spec 以外）に旧ストア名・旧ヘッダー名・旧バナー名が残らない', () => {
    const offenders = walk(APP)
      .filter(f => !f.endsWith('.spec.ts'))
      .filter((f) => {
        const src = readFileSync(f, 'utf8')
        return /useAdminImpersonationStore|X-Admin-Impersonate-User-Id|AdminImpersonationBanner/.test(src)
      })
      .map(f => relative(ROOT, f))
    expect(offenders).toEqual([])
  })

  it('S16 運営画面・本人の設定画面がある', () => {
    const adminPage = ['pages/system-admin/support-access.vue', 'pages/system-admin/support-access/index.vue']
      .some(p => existsSync(join(APP, p)))
    const settingsPage = ['pages/settings/support-access.vue', 'pages/settings/support-access/index.vue']
      .some(p => existsSync(join(APP, p)))
    expect(adminPage, '/system-admin/support-access').toBe(true)
    expect(settingsPage, '/settings/support-access').toBe(true)
  })

  it('S16 既定レイアウトは新しいバナーを置く', () => {
    const layout = readFileSync(join(APP, 'layouts/default.vue'), 'utf8')
    expect(layout).toContain('SupportAccessBanner')
  })

  it.each(LOCALES)('S16 %s の common.json にサポート閲覧の文言が揃い、旧 admin.impersonation は無い', (locale) => {
    const dict = JSON.parse(readFileSync(join(APP, `locales/${locale}/common.json`), 'utf8')) as Dict
    const missing = REQUIRED_KEYS.filter(k => typeof lookup(dict, k) !== 'string' || (lookup(dict, k) as string) === '')
    expect(missing).toEqual([])
    expect(lookup(dict, 'admin.impersonation')).toBeUndefined()
  })
})
