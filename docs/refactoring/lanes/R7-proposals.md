# R7 — 大型負債の提案書の作成（実装しない）

> 親文書: [`../refactor-instructions.md`](../refactor-instructions.md)。先に親文書を読むこと。
> 推奨モデル: 偵察は Sonnet、設計の判断が重い提案は Opus。
> **このレーンはコードを1行も変えない。** 成果物は `docs/refactoring/proposals/` の提案書だけである。

## 目的

実装すると凍結ストア・課金・認可・本番設定に触れる負債について、マスターが「やる・やらない・いつ・誰の戦役で」を決められる材料を作る。

## 提案書の形式

1提案1ファイル。`docs/refactoring/proposals/P<番号>-<名前>.md`。

```markdown
# P<番号> — <題>

## 現状（事実）
<ファイル・行・件数。計測コマンドと計測日>

## なぜ負債か
<具体的に何が困るか。過去の事故や台帳の行があれば引用>

## 影響範囲
<触るファイル群、依存している側、公開 API・DB・凍結ストア・番人への波及>

## 案
<案A・案B…。それぞれの手順、PR の切り方、所要の見積もり>

## リスクと安全網
<壊れ方と、それを先に検出するテスト。安全網が無いなら、先に作るもの>

## 既存戦役との関係
<#4022 のどのレーン・どの領地に当たるか。単独でやれるか、所管へ渡すべきか>

## 推奨
<1つに絞る。やらないという推奨も可>

## 判断してほしいこと
<マスターへの質問>
```

事実と推測を分けて書く。件数は自分で計測する。親文書の数値は起草時点のもので、鵜呑みにしない。

## 提案の一覧

優先度は「放置の害が大きく、提案が判断を助けるもの」から。

| # | 題 | 起点になる事実 | 特に書くこと |
|---|---|---|---|
| P1 | 手動型から生成型への移行の前提整備 | 手動型 178 ファイル。生成型は、注釈が整った DTO では nullable・必須・文字列リテラルの union を出しているが、未整備の DTO では出ない | **整備済みと未整備を分けて数える。** スキーマごとに、nullable の有無・必須の有無・enum が union か `string` か・スキーマ名の衝突を表にする。整備済みのドメインで1つ移行を試し、何が足りないかを示す |
| P2 | `GlobalExceptionHandler`（3,361 行）の分割 | `@ExceptionHandler` 23個。`common/` にあり #4022 の L1-R の領地 | 行数の大半が何か（写像表か、ハンドラ本体か）。`GlobalExceptionHandlerTest`（2,164 行）が安全網になるか。L1-R と衝突しない時期 |
| P3 | `UserRoleRepository`（3,115 行・`@Query` 81本）の分割 | 認可の中心。D-5 の凍結行に多数現れるはず | 凍結ストアに何行現れるかを数える。分割で凍結行がどう動くか。読み取り専用の窓口に分ける案 |
| P4 | Service が `ApiResponse<Entity>` を返す12ファイルの DTO 化 | `gamification` 5、`knowledgebase` 5、`skill` 2。計36箇所 | DTO 化で JSON の形が変わるか（`docs/openapi.json` の該当スキーマと Entity のフィールドを比べる）。`knowledgebase` は #4022 第1波の担当 |
| P5 | `ScopeType` enum の重複（7＋類似4） | `bulletin` / `dashboard` / `faq` / `line` / `membership` ほか | 各 enum の値の一覧表と、DB に保存される文字列。差が仕様かどうか。統合するなら互換の保ち方（`PersistedEnumCompatibilityArchTest`） |
| P6 | Stripe 呼び出しと Webhook 入口の集約 | `payment/`・`billing/`・`advertising/` が SDK を直接呼ぶ。Webhook の入口が5系統以上 | 入口ごとに、署名検証と冪等処理（`WebhookIdempotencyService`）を通っているかの表。**通っていない入口があれば、提案ではなく不具合として台帳へ起票する**。CMP-260918-1359 との関係 |
| P7 | 暗号文への鍵バージョンの導入 | `common/EncryptionService` は単一鍵。`docs/security/09` に計画がある | 計画が実装済みかどうか。暗号化している列の一覧。本番に実データが無い今のうちに形式を変える利点 |
| P8 | `application.yml`（670 行・6プロファイル）と `application-test.yml` の二重配置 | テスト用の yml が `src/main` にもある | プロファイル別のキーの差分表。`mannschaft.allowed-origins` を本番で必須にする案。`TestProfileKeyCoverageGuardTest` との関係 |
| P9 | `nuxt.config.ts` の locale 列挙の生成化 | 77 ファイル × 6 言語を直書き | 登録漏れを検出する番人を先に置く案と、列挙を生成する案の比較 |
| P10 | スケジュール・カレンダー系 FE の分割設計（**このレーンが書く。R4 は書かない**） | R4 の段階3で対象外にした5本（約5,400 行）と、絡み合う composable 8本 | 依存の図、分割の順序、特性テストの計画。R4 からの申し送りがあれば取り込む |
| P11 | `README.md`（126KB）の構成 | 冒頭に個別 PR の進捗が積もる | 進捗を別文書へ分ける案と、`CLAUDE.md` の「README を更新せよ」の言い換え案 |
| P12 | 広域 catch と `@Deprecated` の棚卸し | `catch (Exception\|Throwable` が 388 ファイル・742 行。`@Deprecated` が 16 ファイル・63 行 | catch は「握りつぶし」「ログだけ」「包み直し」に分けた件数。`@Deprecated` は要素ごとの呼び出し元の件数。番人化できるか |
| P13 | FE を Cloudflare 上で SSR する構成の実現と、デプロイ認証の統一 | 方針は「BE は AWS、FE は Cloudflare。ログイン画面は SSR、ログイン後はページ遷移をなるべく発生させない SPA」。現状の FE は Node サーバ向けのビルドで、Cloudflare 向けの Nitro preset も wrangler の workflow も無い。`frontend-deploy.yml` は ECR / ECS 向け。認証は infra 系が OIDC、deploy 系が長期キー | **SSR が要る画面の範囲を先に確定する**（ログインだけか、LP・公開チームページ・公開ブログなど未ログイン向けの画面も含むか）。範囲が狭いなら、その画面だけをビルド時に事前生成し、残りを SPA として静的に配る案が取れ、Cloudflare 上でサーバ処理を動かさずに済む。この案と、Workers で SSR する案を比較する。Cloudflare の実行環境（Workers）で動かない依存や Node 専用 API の洗い出し。SSR 時に BE を呼ぶ経路（`NUXT_INTERNAL_API_BASE`）をどうするか。同一オリジン・Cookie・CSP の不変条件（親文書 §3-1）を保つ方法。PWA（service worker）との両立。使われていない `frontend-deploy.yml` と `frontend/Dockerfile` の扱い。段階的な切り替えと戻し方 |
| P14 | 起動スクリプトの個人環境依存の解消 | `start-backend.vbs` / `start-backend.ps1` / `backend-loop.sh` が `C:\Claude\mannschaft` と `/home/kenta` を直書き。`/陣立て`・`/伝令`・`/撤収` が使う正系 | パスを環境から解決する案。`.claude/commands/` 直下と `deepseek/` 配下の同名コマンドの二重管理も併せて整理案を出す |

P6・P7・P13 は、公開リポジトリに書く内容に注意する。**悪用の手掛かりになる書き方をしない**（どの入口が未検証か、といった具体は Issue や PR 本文に書かず、マスターへ直接報告する）。

## 手順

1. 提案ごとに事実を集める。読み取りと計測だけを行う
2. 既存の設計書（`docs/architecture/`、`docs/security/`、`docs/features/`）と台帳に、同じ論点の決定や進行中の行が無いかを確かめる。あれば新しい提案を書かず、「既に CMP-xxx が所管」と一覧に記す
3. 提案書を書く。1 PR に複数の提案をまとめてよい（文書だけなので）
4. 最後に `docs/refactoring/proposals/README.md` に一覧表（題・推奨・判断待ちの有無）を作る

## やってはならないこと

- 提案のついでの実装（「小さいから」は理由にならない）
- 計測のためのテストの絞り込み実行（凍結ストアを壊す）
- 推測を事実のように書くこと
- 「全部書き直す」という提案。段階と安全網の無い案は提案にならない

## 完了条件

- P1〜P14 のそれぞれについて、提案書があるか、「既存の戦役が所管」「事実の整理のみ」と一覧に記されている
- 一覧表がある
- 調査の途中で見つけた不具合が、台帳へ起票されている
