import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

/**
 * CMP-261012-2343: `pages/admin/` 配下の FE ガード網羅の番人。
 *
 * `/system-admin` 配下は CMP-260930-1933 で `['auth', 'system-admin']` に揃えたが、`/admin` 配下は未調査だった。
 * 全ページを「想定ロール」で分類し、分類の漏れ（新規ページの追加忘れ）と、
 * SYSTEM_ADMIN 専用ページのミドルウェア欠落を機械的に検出する。
 * 根拠となる BE 認可と分類表は docs/security/admin_pages_fe_guard_inventory.md を参照。
 *
 * これは UX（誤遷移の早期遮断）の番人であり、認可の最終判断は BE が担う。
 */

/** BE が SYSTEM_ADMIN 限定で守る API だけを呼ぶページ（`/api/v1/system-admin/**` または SecurityConfig の hasRole 規則、Service 層 checkSystemAdmin）。 */
const SYSTEM_ADMIN_PAGES = [
  'ad-credit-limit-requests.vue',
  'ad-rate-cards.vue',
  'advertiser-accounts.vue',
  'affiliate-settings.vue',
  'announcements.vue',
  'appeals.vue',
  'audit-logs.vue',
  'campaigns.vue',
  'feature-flags.vue',
  'maintenance.vue',
  'moderation.vue',
  'module-pricing.vue',
  'modules.vue',
  'organizations.vue',
  'packages.vue',
  'point-card-synonyms.vue',
  'reports.vue',
  'seasonal-wallpapers.vue',
  'storage-plans.vue',
  'tax-settings.vue',
  'templates.vue',
  'users.vue',
  'villages/creation-requests.vue',
]

/** スコープ（チーム/組織）の ADMIN 系ロールが対象のページ。ガード方式は棚卸し文書に記載（本番人の対象外）。 */
const SCOPE_ADMIN_PAGES = [
  'bulletin-categories.vue',
  'dashboard.vue',
  'feedbacks.vue',
  'line-settings.vue',
  'member-permissions.vue',
  'permission-groups.vue',
  'promotions.vue',
  'proxy-desk.vue',
  'proxy/consents.vue',
  'proxy/records.vue',
  'receipt-settings.vue',
  'receipts.vue',
  'schedule-settings.vue',
  'shift-budget/alerts.vue',
  'shift-budget/allocations.vue',
  'shift-budget/dashboard.vue',
  'shift-budget/failed-events.vue',
  'sns-settings.vue',
  'vendors/index.vue',
]

const ROOT = join(process.cwd(), 'app', 'pages', 'admin')

function collectVueFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name)
    return statSync(full).isDirectory() ? collectVueFiles(full) : full.endsWith('.vue') ? [full] : []
  })
}

const allPages = collectVueFiles(ROOT)
  .map((f) => relative(ROOT, f).split('\\').join('/'))
  .sort()

describe('admin 配下ページのガード網羅（番人）', () => {
  it('全ページが想定ロールで分類されている（新規ページは分類表への追加が必須）', () => {
    const classified = new Set([...SYSTEM_ADMIN_PAGES, ...SCOPE_ADMIN_PAGES])
    expect(allPages.filter((p) => !classified.has(p))).toEqual([])
    expect([...classified].filter((p) => !allPages.includes(p))).toEqual([])
  })

  it("SYSTEM_ADMIN 専用ページは middleware: ['auth', 'system-admin'] を指定している", () => {
    const unguarded = SYSTEM_ADMIN_PAGES.filter(
      (p) => !/middleware:\s*\[\s*'auth'\s*,\s*'system-admin'\s*\]/.test(readFileSync(join(ROOT, p), 'utf8')),
    )
    expect(unguarded).toEqual([])
  })

  it('全ページが auth ミドルウェアを含む', () => {
    const missing = allPages.filter(
      (p) => !/middleware:\s*(\[[^\]]*)?'auth'/.test(readFileSync(join(ROOT, p), 'utf8')),
    )
    expect(missing).toEqual([])
  })
})
