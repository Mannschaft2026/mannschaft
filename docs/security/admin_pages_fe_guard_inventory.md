# `pages/admin/` FE ガード棚卸し（CMP-261012-2343）

> 対象: `frontend/app/pages/admin/` 配下の全 42 ページ。
> 前提: FE ガードは UX（誤遷移の早期遮断）であり認可の最終判断ではない。BE が各 API で必ず認可する。
> 番人: `frontend/tests/unit/middleware/admin-pages-guard.spec.ts`（全ページの分類漏れと、SYSTEM_ADMIN 専用ページの `system-admin` ミドルウェア欠落を検出する）。

## 1. 調査結果の要旨

- 全 42 ページが `middleware: 'auth'`（ログイン確認のみ）で、ロール判定は無かった。
- `/system-admin` 配下は CMP-260930-1933 で `['auth', 'system-admin']` に揃っている（前例）。
- 想定ロールは、各ページが呼ぶ API の BE 認可で決めた。
  - SYSTEM_ADMIN: `/api/v1/system-admin/**`（`SecurityConfig` の `hasRole("SYSTEM_ADMIN")`）、`/api/v1/admin/{reports,moderation,warning-re-reviews,users}/**`（同 hasRole）、`/api/v1/admin/audit-logs`（`AuditLogQueryService`）、`/api/v1/admin/point-cards/synonyms`（Service 層 `checkSystemAdmin`）。
  - スコープ ADMIN 系: `AdminFeedbackController` 等（`accessControlService.checkAdminOrAbove`）。

## 2. 是正済み（23 ページ: `['auth', 'system-admin']` を追加）

ad-credit-limit-requests / ad-rate-cards / advertiser-accounts / affiliate-settings / announcements / appeals / audit-logs / campaigns / feature-flags / maintenance / moderation / module-pricing / modules / organizations / packages / point-card-synonyms / reports / seasonal-wallpapers / storage-plans / tax-settings / templates / users / villages/creation-requests

（villages/creation-requests はページ内にも `isSystemAdmin` 判定があるが、ミドルウェアでも早期遮断する。）

## 3. スコープ ADMIN 系ページ（19 ページ）

| ページ | 想定ロール | 現ガード | 要否 |
|---|---|---|---|
| bulletin-categories / line-settings / receipt-settings / receipts / schedule-settings / sns-settings | スコープ ADMIN/DEPUTY_ADMIN | `middleware: 'auth'` + ページ内 `useAdminScopeGuard` | 済（対応不要） |
| vendors/index | スコープ ADMIN 系 | `auth` + ページ内 `useAdminScopeGuard`（URL クエリのスコープ） | 済（対応不要） |
| shift-budget/{alerts,allocations,dashboard,failed-events} | BUDGET_ADMIN | `auth` + `useShiftBudgetAdminAccess`（導線の出し分け） | 済（BE が Service 層で必須化） |
| dashboard / feedbacks / member-permissions / permission-groups | スコープ ADMIN 以上（BE は `checkAdminOrAbove` 等） | `auth` のみ | **未是正（要判断）** |
| promotions / proxy-desk / proxy/consents / proxy/records | スコープ権限（BE 側の要件を要確認） | `auth` のみ | **未是正（要判断）** |

## 4. 判断が割れる点（未実装）

1. 「`auth` のみ」の 8 ページは、`useAdminScopeGuard` を呼べば足りるが、個人スコープ（対象外）の扱い、`feedbacks` / `dashboard` が複数スコープを横断表示する設計か否か、`proxy-*`・`promotions` の BE 要件が未確認であり、実装前にマスターの方針が要る。
2. `useAdminScopeGuard` の `minRole` 既定（DEPUTY_ADMIN）で良いか、ページごとに ADMIN を要求するか。
3. BE が admin API を実際に守っているかは別課題（本件は FE のみ。`permission-groups` / `member-permissions` / `AdminDashboardController` は Controller に `@PreAuthorize` が無く Service 層の確認が必要）。
