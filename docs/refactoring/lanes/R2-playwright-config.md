# R2 — Playwright の設定の棚卸しと整理

> 親文書: [`../refactor-instructions.md`](../refactor-instructions.md)。先に親文書と [`../decided-facts.md`](../decided-facts.md) を読むこと。進め方は親文書 §4-1 の正規フローに従う。
> 足軽のモデル: Sonnet。
> このレーンの PR は、設定ファイルを変えるので scope が `app` になる（決定 P5）。

## 目的

`frontend/` 直下に27本ある Playwright の設定について、どれが本線で、残りがなぜ別にあるのかを、誰でも言える状態にする。意味の無い設定を無くす。

## 現状（2026-11-17 実測）

- 本線は `playwright.config.ts` と `playwright-real.config.ts`
- 残り25本は、特定の spec のための設定である。**対象の spec はすべて現存する**（対象が無い設定は0本）
- `frontend/package.json` の scripts と `.github/workflows/` は、個別の設定を1本も指していない
- 他の追跡ファイルから参照されない設定が11本ある: `playwright.activity-auto`、`playwright.cmp042`、`playwright-activity-authz`、`playwright-activity-sync`、`playwright-authz-phase3`、`playwright-cmp005`、`playwright-cmp261030-0651`、`playwright-dashboard-jump`、`playwright-f065-archive`、`playwright-f065-subject`、`pw-blog-e2e`
- 命名が3流儀（`playwright-x`、`playwright.x`、`pw-x`）
- 実機 E2E の手順書は `docs/testing/real_e2e_setup.md`。`frontend/README.md` に E2E の節は無い

### 単純には消せない理由

1. **本線が一部の spec を除外している。** 本線の `testIgnore` に載る spec は、「専用の設定で実行する」とコメントされている。専用の設定を消すと、その spec はどこからも実行されなくなる
2. **既定のポートが設定ごとに違う。** FE は 3001・3003・3007・3011・3017・3021、BE は 8081・8083 を既定にするものがある。本線は 3000 と 8080 である
3. **`globalSetup` / `globalTeardown` / `webServer` を持つものがある。** 前提データの作成と後始末を担っている
4. **`.gitleaks.toml` が設定のファイル名を許可リストに持っている**（`playwright-f0121.config.ts`）

### 起草時点で対象外と分かっているもの

| 設定 | 理由 |
|---|---|
| `playwright.config.ts`、`playwright-real.config.ts` | 本線。設定値を変えない |
| `playwright-cmp1507.config.ts`、`playwright-n1p1.config.ts` | 未完の台帳行 CMP-261113-0114（後始末と認証状態のファイル）と CMP-260925-0907（認証状態の既定）が対象にしている。本線が除外している。`globalSetup` を持つ |
| `playwright-f0121.config.ts` | 本線が除外している。`globalTeardown` を持つ。`.gitleaks.toml` が名前を持つ |
| `playwright.restore.config.ts` | 本線が除外している。運用用で、必須の環境変数がある |
| `playwright-gdm.config.ts`、`playwright.password-generator.config.ts` | 本線が除外している |

## 手順

### ステップ1: 棚卸し表を作る

全27本について、次の列を持つ表を `docs/testing/playwright-config-inventory.md` に作る。

| 列 | 取り方 |
|---|---|
| 設定の名前、`testDir`、`testMatch` | ファイルを読む |
| 本線との差 | `baseURL` と既定のポート、`webServer`、`globalSetup`、`globalTeardown`、`storageState`、`projects`、`timeout`、`retries`、`workers`、`use` を本線と比べる |
| 必要な環境変数 | ファイルの中の `process.env.*` |
| 本線が除外しているか | 本線2本の `testIgnore` と照らす |
| 参照元 | `git grep -nF "<設定の名前>"`。範囲に `.gitleaks.toml` と、他の `playwright*.config.ts` を含める |
| 未完の台帳行 | 設定の名前と対象 spec の名前で `docs/task-list.md` を検索する |
| 収集の結果 | `npx playwright test --config <設定> --list` のテスト名の一覧。**まず、サーバ無しで収集できるかを確かめる**（`globalSetup` を持つ設定で起動が走るなら、その設定では `--list` を使わず、その旨を表に書く） |
| 分類 | 下の3つ |

分類:

- **本線で足りる**: 次のすべてを満たす。(a) 本線との差が `testMatch` と既定のポートだけ。(b) 対象の spec が本線の `testIgnore` に載っていない。(c) 未完の台帳行が無い。(d) 本線の設定に spec のパスを渡したときの `--list` の結果が、元の設定と同じテスト名になる
- **固有の設定がある**: 差に意味がある。残す
- **判断できない**: 残し、理由を表に書く

### ステップ2: 寄せる

「本線で足りる」設定について、1 PR あたり5本までで進める。

1. その設定を指している手順書（`docs/` の中）の実行コマンドを、本線の設定と spec のパスの形に書き換える。既定のポートが違っていた場合は、環境変数での指定方法を併記する。`docs/evidence/` の中の記述は書き換えない（過去の記録である）
2. 設定を削除する。`.gitleaks.toml` に名前があれば、同じ PR で同期する
3. 削除の前後で、対象 spec の `--list` の結果が一致することを示す
4. **実機で、対象の spec を本線の設定から1回実行する。** 削除前に元の設定で実行した結果（通る・落ちる）と比べ、同じであることを示す。実機の手順は `docs/testing/real_e2e_setup.md` に従う

### ステップ3: 残す設定に理由を書く

「固有の設定がある」設定のファイル冒頭に、「何のための設定か。いつ不要になるか」を1〜3行のコメントで書く。既にコメントがあるものは足さない。**未完の台帳行がある設定には触らない。**

### ステップ4: 再発を防ぐ

`docs/testing/real_e2e_setup.md` に、次を1節で書く。

- 単一の spec のために設定を足さない。本線の設定に spec のパスを渡す（実行例を1つ）
- 専用の設定が要るのは、前提データの作成や後始末に `globalSetup` / `globalTeardown` が要るときだけ。そのときは本線の `testIgnore` に足し、理由をコメントに書く
- 棚卸し表へのリンク

命名の統一（改名）は行わない。参照の書き換えが広く、得るものが小さい。

## やってはならないこと

- 本線2本の設定値（`testIgnore` を含む）を変えること
- spec の中身を変えること（R3 の担当）
- 対象外の設定に触れること
- `--list` の結果か、実機の実行結果が変わる統合

## Stop And Ask

- 本線の設定から実行すると、結果が元の設定と違う
- 既定のポートの違いに、意図（他の検証と同時に動かすための分離など）がありそうで、消してよいか判断できない

## 完了条件

1. 棚卸し表がある（27本すべて）
2. 「本線で足りる」設定が削除され、手順書が書き換えられている
3. 残した設定に、理由のコメントがある（未完の台帳行があるものを除く）
4. `docs/testing/real_e2e_setup.md` に再発防止の節がある
5. 設定の本数を、着手時と完了時で報告している

## 検証

- 削除した設定ごとの、`--list` の前後比較と、実機での実行結果の前後比較
- `npm run lint`、`NODE_OPTIONS=--max-old-space-size=8192 npm run typecheck`
- 親文書 §9-3 の完了ゲート（`app`）
- CI の全チェックが緑で、検分に合格している
