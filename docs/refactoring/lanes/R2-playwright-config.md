# R2 — Playwright config の棚卸しと統合

> 親文書: [`../refactor-instructions.md`](../refactor-instructions.md)。先に親文書を読むこと。
> 推奨モデル: Sonnet。

## 目的

`frontend/` 直下に27本ある Playwright config を、意図のある少数に減らす。どれが本線で、差が何のためにあるかを誰でも言える状態にする。

## 現状（2026-11-17 実測）

- 本線は `playwright.config.ts`（他ファイルからの参照 30）と `playwright-real.config.ts`（同 39）
- `frontend/package.json` の scripts と `.github/workflows/` は、個別 config を1本も参照していない
- 追跡ファイル全体で参照ゼロの config が11本:
  `playwright.activity-auto` / `playwright.cmp042` / `playwright-activity-authz` / `playwright-activity-sync` / `playwright-authz-phase3` / `playwright-cmp005` / `playwright-cmp261030-0651` / `playwright-dashboard-jump` / `playwright-f065-archive` / `playwright-f065-subject` / `pw-blog-e2e`（いずれも `.config.ts`）
- 参照が1〜9件の config が14本。参照元の多くは文書と完了証拠で、実行手順として生きているかは未確認
- 命名が3流儀（`playwright-x`、`playwright.x`、`pw-x`）

## 手順

### ステップ1（回答不要）: 棚卸し表を作る

全27本について、次の列を持つ表を `docs/refactoring/lanes/R2-inventory.md` に作る。

| 列 | 取り方 |
|---|---|
| config 名 | — |
| `testDir` / `testMatch` | ファイルを読む |
| 本線との差 | `baseURL`、`webServer`、`globalSetup`、`storageState`、`projects`、`timeout`、`retries`、`workers`、`use` の各項目を本線と比べる |
| 必要な環境変数 | ファイル内の `process.env.*` |
| 参照元 | `git grep -nF "<config 名>"` |
| 対象 spec の現存 | `testMatch` の先のファイルがあるか |
| 収集結果 | `npx playwright test --config <config> --list` の件数（サーバは不要） |
| 分類 | 下の3分類 |

分類は次の3つ。

- **本線で足りる**: 本線との差が `testMatch` だけ。`--config playwright-real.config.ts <spec パス>` で同じ spec を収集できる
- **固有設定あり**: 差に意味がある（専用の `webServer`、認証状態、必須環境変数など）。残す
- **対象が無い**: `testMatch` の先の spec が存在しない、または収集が0件

### ステップ2: 寄せる・消す（裁可済み: 内容を確認して不要なら削除）

1. 「本線で足りる」config を1本ずつ消す。消す前後で、対象 spec の `--list` の結果（テスト名の一覧）が一致することを示す
2. その config を指している文書の実行コマンドを、本線の config と spec パスの形に書き換える。完了証拠（`docs/evidence/`）の中の記述は書き換えない（過去の記録である）
3. 「対象が無い」config を消す
4. 「固有設定あり」は残し、ファイル冒頭に「何のための config か、いつ不要になるか」を1〜3行のコメントで書く
5. 命名の統一（改名）は、参照の書き換えが完全にできるものだけ行う。迷うなら改名しない

### ステップ3: 再発防止

`frontend/README.md` の E2E 節に（`TEST_CONVENTION.md` は R6 だけが編集する。そちらにも要るなら R6 への依頼として報告に書く）、「単一 spec のために config を足さない。本線の config に spec パスを渡す」と、その実行例を1つ書く。

## やってはならないこと

- 本線の2本（`playwright.config.ts`、`playwright-real.config.ts`）の設定値を変えること
- spec ファイルの中身を変えること
- `--list` の結果が変わる統合
- `playwright.restore.config.ts` のような運用用 config を、用途を確かめずに消すこと

## 完了条件

- 棚卸し表がある
- 「本線で足りる」「対象が無い」の config が消えている。判断に迷って残したものは、理由付きで棚卸し表にある
- §6-5 の本数を着手時と完了時で報告する

## 検証

- 消した config ごとの `--list` の前後比較
- `npm run lint`、`npm run typecheck`
- `frontend-e2e-ci.yml` を含む CI の全チェックが緑
