<!-- `backend/.claudecode.md` §31 の本文（逐語移設）。節番号・見出しは元のまま。 -->
[← backend/.claudecode.md（索引）に戻る](../../../backend/.claudecode.md)

## 31. CI テストシャードの重み付け振り分け

`backend-ci.yml` の test ジョブは **8 shard**（matrix `0..7`、`env.SHARD_TOTAL: "8"`）に分割している。
`backend-nightly-full.yml` と `backend-deploy.yml` も同じ 8 shard で、3 workflow の `SHARD_TOTAL` と matrix は必ず揃える（2026-11-18・CMP-261111-0508 で 6 → 8。BE テスト CI の時間切れの是正。**step 上限 85/105 分・job 上限 100/120 分・nightly の step 105・job 120・deploy の現行値は変えていない**）。分割は（`build-logic/src/main/kotlin/mannschaft.backend-conventions.gradle.kts` の
`-Pshard.total` / `-Pshard.index` フィルタ）。

### 振り分けアルゴリズム

- `backend/src/test/resources/shard-weights.properties`（クラス単位の実測秒数）が存在する場合:
  **貪欲法**（重い順に、その時点で合計時間が最小の shard へ割り当てる Longest Processing Time
  First）でトップレベルクラスを `shard.total` 分割へ割り付ける。理論上界は最適解の 4/3 倍。
- 割り付ける対象は「**実行対象のトップレベルクラス集合**」。コンパイル済み .class から、抽象クラス・
  テストメソッドを持たないヘルパー・jupiter の `@Tag("perf"/"archunit"/"er-diagram")` 付き（専用タスクが実行）を
  除き、ネストクラスはトップレベルへ寄せて数える（`CompiledTestClassScanner` / `ShardAssignment.executableTopLevelClasses`）。
  `@AnalyzeClasses` のクラスは `archUnitTest` が実行するため対象に含める。
- 重み表に載っていないクラス（新規テスト等）は**ハッシュではなく既定重み付きで同じ貪欲法に参加**する。
  既定重み = 重み表の値の**中央値**（件数が偶数なら中央 2 値の平均。重み表が空・全 0 なら固定 1.0 秒）。
  同じ重みは FQCN 昇順、合計が同じ shard は index の小さい方を選ぶため、**クラスの列挙順に依存しない**。
- 重み表ファイル自体が存在しない場合も、全クラスが既定重み 1.0 で割り付く。
- `shard-weights.properties` に負値・NaN・Infinity があると `loadWeights` がクラス名付きで例外にする。
- **プロファイル別の構成時計算**: 貪欲法は「実際にその CI 実行で走るクラス集合」に対して行う。
  `-PexcludeMigrationTests=true`（通常 PR の大多数）の場合は migration+Flyway クラス（旧XML集計で重いクラス群）を貪欲法の対象から事前に除外してから残りのクラスで均等化する。migration 込みの
  重みでそのまま最適化すると、通常 PR では migration クラスがまるごと除外されて残りだけが走る
  ため、逆にその残りが大きく偏る回帰を招く（実測: 最大/最小比 2.19 倍まで悪化した事例あり）。
- 割り付け・判定ロジックは `build-logic/src/main/kotlin/ShardAssignment.kt` の `ShardAssignment` オブジェクトに一本化されて
  おり、**実際のテスト実行時 `Test.exclude { }` フィルタと `verifyShardCoverage` 検証タスクの
  両方が同じ集合の作り方（`runnableTopLevelClasses`）・同じ割り当て（`assignShards`）・同じ述語関数
  （`ShardAssignment.shouldExcludeForAssignedShard()` / `shouldExcludeMigrationReplay()`）をそのまま呼ぶ**（パス正規化・bucket 判定・否定を丸ごと
  関数内に閉じ込め、呼び出し側で別実装の再計算や外側での `!` 反転を行わない）。フィルタが
  反転・全除外・多重割当・正規化破損のいずれに壊れても、検証は同じ壊れ方の影響を受けて確実に
  fail する構造。
- `verifyShardCoverage` タスクで、コンパイル済み .class ファイルの生パスに対して実際に検証
  できる: `cd backend && ./gradlew verifyShardCoverage -Pshard.total=8`
  （shardIndex = 0..total-1 の全パターンへ実際に述語を適用し、
  1. 実行対象集合の各クラスがちょうど1つの shard で実行されること（0=取りこぼし、2以上=重複割当）、
  2. migration 除外プロファイルでは migration+Flyway クラスがどの shard でも実行されないこと、
  3. 同一トップレベルクラスに属する複数 .class ファイル（ネストクラス）が同じ shard に乗ること
     （グルーピングは `ShardAssignment` を経由しない独立した文字列操作で行い、正規化バグの
     自己採点を防ぐ）
  の3点を、重み表あり/なし × migration除外あり/なしの**4プロファイル**全てで assert する）。
- CI では `backend-ci.yml` の `shard-coverage-guard` ジョブが 1 回だけ `verifyShardCoverage` を
  実行し、`gate`（必須チェック「Compile & Test」）の needs に含まれる。shard それぞれで
  重複実行はしない。同ジョブは `gradlew -p build-logic test`（`ShardAssignment` / `ShardBalanceGuard` / `CompiledTestClassScanner` の単体テスト）と
  `python3 -m unittest scripts/test_regen_shard_weights.py` も実行する。

### 番人（重み表の鮮度と shard 偏り）

`verifyShardCoverage` は上の取りこぼし検査に続けて `ShardBalanceGuard`（`build-logic`）を呼び、違反でタスクを失敗させる。
閾値は `ShardBalanceGuard` の定数 1 か所（テストもこの定数を参照する）。**閾値の変更は殿の判断事項**。

| 検査 | 閾値（定数） | 対象 |
|---|---|---|
| 重み表に未掲載の実行対象トップレベルクラス数 | `MAX_UNLISTED_CLASSES` = 100（ちょうどは合格） | full 集合で 1 回 |
| 予測 max/avg 比（LPT 割り当て後の shard 合計重みの最大 / 平均） | `MAX_IMBALANCE_RATIO` = 1.10（ちょうどは合格） | full と migration 除外の両集合 |

失敗メッセージにはプロファイル名（`full` / `migration-excluded`）と再生成手順 `backend/scripts/regen-shard-weights.sh` を含む。
未掲載が閾値を超えたら重み表を再生成する（日常的な数クラスの増減は既定重みが吸収する）。

### 導入の背景

安定ハッシュのみによる振り分けは実行時間を考慮しないため、shard 間で最長/最短の壁時計に
1.3〜1.8 倍のばらつきが生じていた（2026-08 実測）。CI 全体の壁時計は最長 shard で決まるため、
重い Testcontainers IT が偶然同じ shard へ集中すると、他の shard が早く終わっても全体は縮まらない。
貪欲法による重み付け振り分けで、この偏りをほぼ解消できる。実測重み表での試算（各プロファイルを
独立に均等化した旧XML重みの試算であり、新しい実時間重みでの壁時計短縮を保証しない）:

| プロファイル | 安定ハッシュ相当の偏り（最大/最小比） | 貪欲法適用後 |
|---|---|---|
| migration込み（backend-nightly-full.yml） | 1.32倍 | 1.00倍 |
| migration除外（通常PR・大多数の経路） | 1.85倍 | 1.00倍 |

### 全 shard の実時間の採取

通常 CI と nightly full は `scripts/run-ci-tests-with-observability.sh` を共用し、全 shard（現在 8）の
クラス開始/終了・elapsed・task終端を専用 runtime artifact（保持7日）へ記録する。
重みの一次入力は通常 `:test` の class suite 開始/終了間の実時間（ミリ秒）であり、JUnit XML の
`testsuite.time` や MFA内部のサービス計測値とは同一視しない。outer suite がある場合は子を
二重加算せず、nested-only は独立した最外 suite を合算する。全 shard の
正常終端、既存 coverage guard、実行集合内の重複0を確認し、未実行キーを固定ソース・filter・
タグ・既存skip等で分類する（全重みキーとの単純一致を完全性判定にしない）。

未実行・timeout・terminal欠損は `completed=false` / `testStepExitCode=null` として扱う。
Gradleのreturn値だけでなく実際のstep outcomeも記録し、EXIT trapの0だけで成功と判定しない。
既存のFROM-CACHE/UP-TO-DATEはGradleのexit 0を維持するが、freshな採取完了とは扱わない。
採取欠損をgreenや高速化の証拠にせず、既存cap（step 85/105 分・job 100/120 分。nightly は step 105・job 120）・全テスト集合・必須集約gateを維持する（shard 数は 2026-11-18 に 6 から 8 へ変更した。上限値は変えていない）。
MFAの固定prefix数値ログとMFA/billingのcase窓のみ追加採取し、テスト出力全量や秘密値は収録しない。

### 重み表の再生成タイミング

- テストクラス数が大幅に増減した（数十クラス規模の追加/削除）とき。
- 特定 shard が恒常的に他より大きく偏るようになった（重み表が古くなり、新規/削除されたテストの
  実行時間を反映できていない）とき。
- 数クラス程度の日常的な増減では、既定重み（中央値）が自動的に吸収するため都度の再生成は不要。
  未掲載数が番人の閾値（100）を超える前に再生成する。

再生成手順: `cd backend && ./scripts/regen-shard-weights.sh [RUN_ID[,RUN_ID...]] [nightly-full]`（`gh` CLI 必須。既定で
**backend-nightly-full.yml** の直近成功 run の小さい runtime artifact から再構築する。
`RUN_ID` は**カンマ区切りで複数**渡せ、クラスごとに「観測できた run」だけで平均する（ある run で観測されなかったクラスを
0 秒として分母に入れない。観測 run が全 run より少ないクラスは重み表ヘッダーに `observed=k/n` を残す）。
全 run を検証し終えてから 1 回だけ原子的に置換し、1 本でも失敗すれば既存の重み表は変わらない（メッセージに失敗した run ID を含む）。
shard 数 N は run の job 名・step 名（`Run tests (shard n / N`）から導出し、job 数・step の N・artifact の index 集合 `0..N-1` が
一致しなければ拒否する（6 shard の run も 8 shard の run も受け付ける）。run ID と既存の第2引数 `nightly-full|backend-ci` を明示できるが、現在 `backend-ci` はfull profileを
メタデータから確認できないため拒否する）。migration/Flyway 実測非0は明白な除外入力を拒否する
補助チェックであり、full profileの証明とはせず、固定workflow/sourceの実行内容を別証拠で確認する。同一run/head/attemptの全 shard、fresh・exit 0・正常task終端、
class開始/終了の対応と重複0、artifact digest・安全pathを検証し、不正・欠損時は出力を変えない。
検証後のみ同一ディレクトリの一時ファイルから原子的に置換し、XMLへfallbackしない。
historical run と現在HEADの一致は要求せず、現在sourceの未計測・削除・arch/perf/skip分類と
適用判断は別manifestで検分する。生成後は上記の既存4profile coverage guardを実行する。
集計対象の回帰検証: `cd backend && python3 -m unittest scripts/test_regen_shard_weights.py`（`ShardTotalDerivationTest` / `MultiRunAverageTest` / `CiShardWorkflowStaticTest` を含む）。割り当て・番人の単体テスト: `cd backend && ./gradlew -p build-logic test`。
生成後は `verifyShardCoverage`（番人込み）が通ることを確認する。来歴（全 run の id / head / attempt）は重み表ヘッダーに残る。
