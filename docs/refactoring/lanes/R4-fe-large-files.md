# R4 — FE の巨大ファイルの構造分割

> 親文書: [`../refactor-instructions.md`](../refactor-instructions.md)。先に親文書と [`../decided-facts.md`](../decided-facts.md) を読むこと。進め方は親文書 §4-1 の正規フローに従う。
> 足軽のモデル: Sonnet。段階3で実際に詰まったときだけ Opus に上げる。
> **横展開型のレーンである。着手前に #4599 のコメントで対象ファイルを宣言すること。**
> このレーンの PR は scope が `app` になる。

## 目的

変更が衝突しやすくレビューが重い巨大ファイルを、**振る舞いを変えずに**分ける。

根拠となる規約: 1コンポーネントが300行を超えたら composable と子コンポーネントに分ける（FE 規約 §6）。機能ごとの API 呼び出しは `use[Feature].ts` に置き、`useApi.ts` を一枚岩にしない（同 §5）。

先例: `docs/refactoring/phase1_overview.md`（`useParkingApi` を10ファイルに分け、元のファイルを37行の再エクスポートに残した）。

## 原則

1. ロジックを変えない。移すだけにする。移す途中で見つけた不具合は直さない。実証できたものだけを台帳へ起票する
2. 元のファイルのパスと export を残す。利用側の import を書き換えない（再エクスポートで互換を保つ）
3. 1 PR は1ファイル
4. 既存の spec が無いか薄いファイルは、**分割の前に**特性テストを足して緑にする。その PR を先にマージする
5. **自動の配線が黙って壊れることがある**（決定 P9）。Nuxt の自動 import は、`composables/` と `components/` の直下と配下で、公開される名前の規則が違う。分割で名前が変わると、型検査は通っても実行時に壊れる。分割の前後で `.nuxt/imports.d.ts` と `.nuxt/components.d.ts` を比べ、公開される名前が同じことを確かめる
6. 新しく足す spec は、**対象と同じディレクトリに置く**（決定 F12）。日時を扱うテストは時計を固定する（決定 F13。CI は `TZ=UTC`）
7. 日時の整形を移すときは、`useDatetime()` と `utils/localDate.ts` の呼び出しをそのまま保つ。自前の整形に書き換えない（決定 F15）
8. 分割先の名前は規約に従う（composable は `use[Feature].ts`、コンポーネントは PascalCase）

## 対象と順序（2026-11-17 実測。着手時に行数と台帳を取り直す）

### 段階1: 型ファイル（低リスク）

| ファイル | 行数 | 分け方 | 既存の spec |
|---|---|---|---|
| `frontend/app/types/village.ts` | 1,268 | 機能のまとまりごとのファイルに分け、`village.ts` は再エクスポートだけにする | 型を import する spec が2本 |
| `frontend/app/types/tournament.ts` | 811 | 同上 | 同5本ほか |

### 段階2: API composable とデータファイル（低〜中リスク）

| ファイル | 行数 | 分け方 | 既存の spec | 注意 |
|---|---|---|---|---|
| `frontend/app/utils/primevueLocales.ts` | 836 | 言語別のファイルに分ける。読み込みの仕方（同期・遅延）を変えない | `tests/unit/i18n/primevue-locale-parity.spec.ts`、`tests/unit/plugins/primevue-locale.spec.ts` | — |
| `frontend/app/composables/useReservationApi.ts` | 710 | `useParkingApi` の先例どおり、リソース別に分けて元のファイルを再エクスポートにする | `tests/unit/composables/useReservationApi.blocked-impact.spec.ts` ほか | **台帳 CMP-260909-1141（進行中）と CMP-260918-2312 が隣接する。** 着手時に、その行が対象にしているファイルを確かめ、重なっていれば外す |

### 段階3: SFC と、状態を持つ composable（高リスク。特性テストが先）

| ファイル | 行数 | 既存の spec | 扱い |
|---|---|---|---|
| `frontend/app/composables/useDashboardWidgets.ts` | 891 | 同居2本、`tests/unit` に1本 | 対象。ウィジェットの定義（データ）と振る舞いを分ける |
| `frontend/app/pages/my/resume/[id]/index.vue` | 957 | **無い** | 対象。先に特性テストを足す |
| `frontend/app/components/signage/SignageManager.vue` | 807 | **無い** | 対象。先に特性テストを足す。第4弾で汎用化済みなので、再分割が要るかから判断する |
| `frontend/app/components/reservation/SlotMatrixPicker.vue` | 873 | あり | **対象外。** 未完の台帳行 CMP-261108-1055 が同じファイルを対象にしている |
| `frontend/app/components/schedule/ScheduleEventForm.vue` | 986 | あり（2本） | **対象外。** R7 の P10 の裁可を待つ |
| `frontend/app/components/reservation/WeeklyScheduleManager.vue` | 1,183 | あり | 同上 |
| `frontend/app/components/schedule/CalendarWeekGrid.vue` | 1,116 | あり（5本） | 同上 |
| `frontend/app/pages/calendar.vue` | 1,100 | あり（2本） | 同上。未完の台帳行 CMP-260925-0904 と CMP-261010-1811 もある |
| `frontend/app/composables/useMyCalendarData.ts` | 995 | あり（5本） | 同上 |

段階3の進め方:

1. まず、テンプレートに触れずに済む抽出だけを行う。純粋な計算・整形・定数を、`utils/` か同じディレクトリの `.ts` へ移し、単体テストを足す
2. 次に、状態と監視を持つ部分を composable へ移す。`ref`・`computed`・`watch` の評価の順序が変わらないことを、特性テストで押さえる
3. 子コンポーネントへの分割は最後にする。親が公開する props と emits が変わらないことを示す

スケジュール・カレンダー系の5本は、composable 8本が互いに絡み、日時が「瞬間」と「壁時計」の2系統を持つ（`docs/architecture/datetime_policy_utc_instant_vs_wallclock.md`）。時刻設計の戦役（CMP-023）と、`dayjs.tz` の直接呼び出しの是正（CMP-261103-0820）も進行中である。**分割の設計は R7 が提案書 P10 として書く。** R4 は書かない。知見があれば、R7 への申し送りとして報告に書く。

## やってはならないこと

- store（`useInboxStore`・`useActionMemoStore`・`useAuthStore`）の分割
- `app/utils/ranch-production-assets.ts`（生成物）と牧場の関連ファイル
- 分割と同時の改名、props の整理、型の厳格化、スタイルの変更、見た目が変わる変更
- 利用側の import の一括の書き換え
- `nuxt.config.ts` の変更（新しいロケールのファイルが要る事態になったら、止める）
- 対象外のファイルに触れること

## Stop And Ask

- 分割の途中で、2通りに読めるロジックが見つかった
- 特性テストを書こうとしたら、現在の振る舞いが仕様書と違っていた
- 再エクスポートでは互換を保てない（自動 import の名前が変わる）

## 完了条件

1. 段階1の2本が分割されている
2. 段階2の `primevueLocales.ts` が分割されている。`useReservationApi.ts` は、分割されているか、所管があるため外したと報告にある
3. 段階3の対象3本について、特性テストがあり、少なくとも手順1（純粋な部分の抽出）が済んでいる
4. 対象外のファイルに手を付けていない
5. 分割した各ファイルについて、公開される名前の前後の対照が PR 本文にある

## 検証

- `npm run lint`、`NODE_OPTIONS=--max-old-space-size=8192 npm run typecheck`
- 対象の spec と、足した特性テスト（件数が0でないこと）。PR 前に `npm run test:unit`
- `.nuxt/imports.d.ts` と `.nuxt/components.d.ts` の、分割の前後の差分
- 親文書 §9-3 の完了ゲート（`app`）。実機では、対象の画面を開いて主な操作を踏む。ブラウザは `localhost` で開く
- CI の全チェックが緑で、検分に合格している
