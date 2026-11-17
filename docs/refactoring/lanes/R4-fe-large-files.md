# R4 — FE 巨大ファイルの構造分割

> 親文書: [`../refactor-instructions.md`](../refactor-instructions.md)。先に親文書を読むこと。
> 推奨モデル: Sonnet。段階3（SFC）で詰まったら Opus に上げる。
> **横展開型のレーンである。着手前に正本 Issue で対象ファイルを宣言すること。** R3 が宣言しているファイルは触らない。

## 目的

変更が衝突しやすくレビューが重い巨大ファイルを、**振る舞いを変えずに**分ける。先例は `docs/refactoring/phase1_overview.md` と `phase2_overview.md`（`useParkingApi` を10ファイルに分け、元のファイルを再エクスポートに残した）。

## 原則

1. ロジックを変えない。移すだけにする。移す途中で見つけた不具合は直さず、台帳へ起票する
2. 元のファイルのパスと export を残す。利用側の import を書き換えない（再エクスポートで互換を保つ）
3. 1 PR は1ファイル
4. 既存の spec が無い、または薄い場合は、**分割の前に**特性テストを足して緑にする。その PR を先にマージする
5. Nuxt の自動 import に注意する。`composables/` と `components/` の直下と配下では、自動 import される名前の規則が違う。分割で名前が変わると、型検査は通っても実行時に壊れる。分割後に `.nuxt/imports.d.ts` と `.nuxt/components.d.ts` を見て、公開名が前後で同じことを確かめる

## 対象と順序

安全な順に3段階で進める。各段階の中は並行でよい。

### 段階1: 型ファイル（低リスク）

| ファイル | 行数 | 分け方 |
|---|---|---|
| `frontend/app/types/village.ts` | 1,268 | 機能のまとまりごとのファイルに分け、`village.ts` は再エクスポートだけにする |
| `frontend/app/types/tournament.ts` | 811 | 同上 |

検証は `npm run typecheck` と、型を import している側の差分がゼロであること。

### 段階2: API composable とデータファイル（低〜中リスク）

| ファイル | 行数 | 分け方 |
|---|---|---|
| `frontend/app/composables/useReservationApi.ts` | 710 | `useParkingApi` の先例どおり、リソース別に分けて元ファイルを再エクスポートにする |
| `frontend/app/utils/primevueLocales.ts` | 836 | 言語別のファイルに分ける。読み込みの仕方（同期・遅延）を変えない |

着手前に、同じ composable を R3 が触っていないことを確かめる。

### 段階3: SFC と状態を持つ composable（高リスク。特性テストが先）

| ファイル | 行数 | 注意 |
|---|---|---|
| `frontend/app/composables/useDashboardWidgets.ts` | 891 | ウィジェット定義（データ）と振る舞いを分ける |
| `frontend/app/pages/my/resume/[id]/index.vue` | 957 | 表示のまとまりを子コンポーネントへ |
| `frontend/app/components/signage/SignageManager.vue` | 807 | 第4弾で汎用化済み。再分割の要否から判断する |
| `frontend/app/components/reservation/SlotMatrixPicker.vue` | 873 | **未完の台帳行 CMP-261108-1055（週の表示がロケールに従わない）が同じファイルを対象にしている。その行が完了するまで触らない。** 完了後に、計算部分を純粋関数へ |
| `frontend/app/components/schedule/ScheduleEventForm.vue` | 986 | 下記の提案先行 |
| `frontend/app/components/reservation/WeeklyScheduleManager.vue` | 1,183 | 下記の提案先行 |
| `frontend/app/components/schedule/CalendarWeekGrid.vue` | 1,116 | 下記の提案先行 |
| `frontend/app/pages/calendar.vue` | 1,100 | 下記の提案先行 |
| `frontend/app/composables/useMyCalendarData.ts` | 995 | 下記の提案先行 |

段階3の進め方:

1. まず、テンプレートに触れずに済む抽出だけを行う。純粋な計算・整形・定数を `utils/` か同じディレクトリの `.ts` へ移し、単体テストを足す
2. 次に、状態とウォッチャを持つ部分を composable へ移す。`ref` / `computed` / `watch` の評価順が変わらないことを特性テストで押さえる
3. 子コンポーネントへの分割は最後。props と emits が増えるので、親の公開インターフェースが変わらないことを示す

**スケジュール・カレンダー系の5本は、このレーンでは触らない。** 分割の設計は R7 が提案書 P10 として書き、裁可を待つ（R4 は提案書を書かない。必要な知見があれば R7 への申し送りとして報告に書く）。スケジュール系の composable は8本が互いに絡んでおり、日時は「瞬間」と「壁時計」の2系統を持つため（`docs/architecture/datetime_policy_utc_instant_vs_wallclock.md`）、局所的な分割で挙動が変わりやすい。

## やってはならないこと

- store（`useInboxStore`、`useActionMemoStore`、`useAuthStore`）の分割。このレーンの対象外
- `app/utils/ranch-production-assets.ts`（生成物）と牧場関連
- 分割と同時の改名、props の整理、型の厳格化、スタイルの変更
- 利用側の import の一括書き換え
- 見た目が変わる変更

## Stop And Ask

- 分割の途中で、2つの解釈ができるロジックが見つかった
- 特性テストを書こうとしたら、現在の振る舞いが仕様書と違っていた
- 再エクスポートでは互換を保てない（自動 import の名前が変わる）

## 完了条件

- 段階1と段階2の対象がすべて分割済み
- 段階3は、提案先行でも他戦役の対象でもないファイル（起草時点で3本）について、少なくとも手順1（純粋部分の抽出）が済んでいる。着手時に、対象の全ファイルを台帳で検索し、未完の行があるものは対象から外して報告に載せる
- スケジュール・カレンダー系5本に手を付けていない

## 検証

- `npm run lint`、`npm run typecheck`
- 対象 spec と、足した特性テスト（件数が0でないこと）
- PR 前に `npm run test:unit`
- 段階3は、対象の画面を実機で1回開いて操作する。できなければ PR 本文に未実施と理由を書く
- PR 本文に、分割前後の公開名（export・props・emits）の対照を載せる
