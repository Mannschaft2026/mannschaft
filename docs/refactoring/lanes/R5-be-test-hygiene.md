# R5 — BE テスト基盤の規約違反の是正

> 親文書: [`../refactor-instructions.md`](../refactor-instructions.md)。先に親文書を読むこと。
> 推奨モデル: Sonnet。
> このレーンが触るのは `backend/src/test/` だけである。`backend/src/main/` は1行も変えない。

## 目的

`TEST_CONVENTION.md` §10 が禁じているのに残っている `Thread.sleep`（35 ファイル・49 件）を無くし、滞留している `@Disabled`（13 ファイル・33 件）を見える形にする。

## 先に読むもの

- `TEST_CONVENTION.md`（特に §2.4 の日付の固定、§9.7、§10）
- `docs/conventions/backend/30-arch-guard-tests.md`（**凍結ストアの節は全文**）
- `backend/.claudecode.md` §30
- Awaitility を使っている既存のテスト（141 ファイルある。同じドメインのものを手本にする）

## 最重要の注意

- **`common/architecture/` 配下と、名前に `Arch` / `Guard` を含むテストには触らない。** 番人であり、#4022 の所管である。`Thread.sleep` や `@Disabled` がその中にあっても、一覧に載せるだけにする
- **ArchUnit の番人を含むテストを `--tests` で絞って実行しない。** 凍結ストアから違反行が消える。ローカルの確認は `./gradlew compileJava compileTestJava --no-daemon` に留め、テストの実行結果は CI で見る
- 自分の変更で `backend/src/test/resources/archunit_store/` に差分が出たら、**commit せずに止める**
- **テストクラスを改名・移動・追加しない。** shard の割当が変わる。どうしても要るなら `./gradlew verifyShardCoverage -Pshard.total=6` を通す
- 重い Gradle 実行は、`backend/` の中で `bash scripts/gradle-turnstile.sh bash ./gradlew …` として直列化する

## 手順

### ステップ1: `Thread.sleep` の一覧と分類

```bash
git grep -n 'Thread\.sleep' -- backend/src/test
```

1件ずつ、何を待っているかで分ける。

| 種別 | 例 | 直し方 |
|---|---|---|
| ① 非同期処理の完了待ち | `@Async` のリスナー、イベント配送、outbox の取り込み | Awaitility で**観測できる条件**（DB の行、モックの呼び出し回数、状態値）を待つ。タイムアウトは手本に合わせる |
| ② 時刻を進めるための待ち | 「1ミリ秒後の `created_at`」「有効期限切れ」 | 待たずに時刻を与える。プロジェクトが `Clock` の差し替えを用意していればそれを使う。無ければ、テストデータの時刻を直接設定する |
| ③ 並行性のテストでタイミングを作る待ち | 2スレッドの競合を起こす | `CountDownLatch` などで順序を決める。難しければ直さずに一覧へ残す |
| ④ 番人・基盤の中 | `common/architecture/` 等 | 触らない |

### ステップ2: 置換

1. 1 PR は**同じドメインの最大5ファイル**
2. 置換のたびに、そのテストが検証している内容が変わっていないことを確かめる。待ち条件を緩くして、失敗するはずのテストが通るようにしてはならない
3. 「待った後に何も起きていないこと」を確かめるテスト（否定の検証）は、Awaitility の `during` 系で「一定時間ずっと成り立つ」形にする。単に待ち時間を消してはならない
4. `compileTestJava` を通す。PR を出して CI の全 shard と「Non-JST timezone」を確かめる
5. CI で不安定になったら、再実行で通して済ませない。待ち条件が実装の本当の完了条件と合っているかを調べ直す

### ステップ3: `@Disabled` の棚卸し（表を作るだけ）

`docs/refactoring/lanes/R5-disabled-inventory.md` に次の表を作る。

| テスト | 無効化の理由（原文） | 分類 | 推奨 |
|---|---|---|---|

分類は、**DB の仕様による制約**（MySQL の CHECK、NULL を含む UNIQUE）／**実装ギャップ**（Bean Validation しか無いなど）／**未実装の機能**／**再構築待ち**／**不明**。

有効化も削除もしない。「実装ギャップ」「未実装」は、仕様として必要かどうかの判断が要るので、質問として報告に載せる。

## やってはならないこと

- `backend/src/main/` の変更（テストを通すための本体修正を含む。本体の不具合を見つけたら台帳へ起票する）
- テストの削除、`@Disabled` の追加、アサーションの削除や緩和
- タイムアウトをむやみに伸ばして通すこと
- `build.gradle.kts`、`build-logic/`、テストの JVM 設定、`shard-weights.properties` の変更
- 巨大テストの `@Nested` 分割（効果が薄く、shard に影響する。対象外）

## Stop And Ask

- 待ちを外すとテストが落ち、本体側の競合や順序の不具合に見える
- 何を待っているのか読み取れない
- 凍結ストアに差分が出た

## 完了条件

- ①②の `Thread.sleep` が置換されている。③④と直せなかったものは、理由付きで一覧にある
- `@Disabled` の棚卸し表がある
- 着手時と完了時の件数が報告にある

## 検証

- `./gradlew compileJava compileTestJava --no-daemon`
- CI: 「Compile & Test」の全 shard、「Non-JST timezone」、「Shard coverage guard」、「arch-test」が緑
- `git diff --stat` に `archunit_store/`、`*_freeze.txt`、`src/main/` が無いこと
