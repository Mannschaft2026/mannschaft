<!-- `backend/.claudecode.md` §31 の本文（逐語移設）。節番号・見出しは元のまま。 -->
[← backend/.claudecode.md（索引）に戻る](../../../backend/.claudecode.md)

## 31. CI テストシャードの重み付け振り分け

`backend-ci.yml` の test ジョブは 6 shard の matrix に分割している（`build-logic/src/main/kotlin/mannschaft.backend-conventions.gradle.kts` の
`-Pshard.total` / `-Pshard.index` フィルタ）。

### 振り分けアルゴリズム

- `backend/src/test/resources/shard-weights.properties`（クラス単位の実測秒数）が存在する場合:
  **貪欲法**（重い順に、その時点で合計時間が最小の shard へ割り当てる Longest Processing Time
  First）でトップレベルクラスを 6 分割へ割り付ける。理論上界は最適解の 4/3 倍。
- 重み表に載っていないクラス（新規テスト等）は、従来どおり**完全修飾クラス名の安定ハッシュ
  （`String.hashCode() % shard.total`）**へフォールバックする。
- 重み表ファイル自体が存在しない場合は、全クラスがハッシュ方式へフォールバックする（旧動作と
  完全に同一）。
- **プロファイル別の構成時計算**: 貪欲法は「実際にその CI 実行で走るクラス集合」に対して行う。
  `-PexcludeMigrationTests=true`（通常 PR の大多数）の場合は migration+Flyway クラス（旧XML集計で重いクラス群）を貪欲法の対象から事前に除外してから残りのクラスで均等化する。migration 込みの
  重みでそのまま最適化すると、通常 PR では migration クラスがまるごと除外されて残りだけが走る
  ため、逆にその残りが大きく偏る回帰を招く（実測: 最大/最小比 2.19 倍まで悪化した事例あり）。
- 割り付け・判定ロジックは `build-logic/src/main/kotlin/ShardAssignment.kt` の `ShardAssignment` オブジェクトに一本化されて
  おり、**実際のテスト実行時 `Test.exclude { }` フィルタと `verifyShardCoverage` 検証タスクの
  両方が同じ述語関数（`ShardAssignment.shouldExcludeForShard()` /
  `shouldExcludeMigrationReplay()`）をそのまま呼ぶ**（パス正規化・bucket 判定・否定を丸ごと
  関数内に閉じ込め、呼び出し側で別実装の再計算や外側での `!` 反転を行わない）。フィルタが
  反転・全除外・多重割当・正規化破損のいずれに壊れても、検証は同じ壊れ方の影響を受けて確実に
  fail する構造。
- `verifyShardCoverage` タスクで、コンパイル済み .class ファイルの生パスに対して実際に検証
  できる: `cd backend && ./gradlew verifyShardCoverage -Pshard.total=6`
  （shardIndex = 0..total-1 の全パターンへ実際に述語を適用し、
  1. 各クラスがちょうど1つの shard で実行されること（0=取りこぼし、2以上=重複割当）、
  2. migration 除外プロファイルでは migration+Flyway クラスがどの shard でも実行されないこと、
  3. 同一トップレベルクラスに属する複数 .class ファイル（ネストクラス）が同じ shard に乗ること
     （グルーピングは `ShardAssignment` を経由しない独立した文字列操作で行い、正規化バグの
     自己採点を防ぐ）
  の3点を、重み表あり/なし × migration除外あり/なしの**4プロファイル**全てで assert する）。
- CI では `backend-ci.yml` の `shard-coverage-guard` ジョブが 1 回だけ `verifyShardCoverage` を
  実行し、`gate`（必須チェック「Compile & Test」）の needs に含まれる。6 shard それぞれで
  重複実行はしない。

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

通常 CI と nightly full は `scripts/run-ci-tests-with-observability.sh` を共用し、全6 shard の
クラス開始/終了・elapsed・task終端を専用 runtime artifact（保持7日）へ記録する。
重みの一次入力は通常 `:test` の class suite 開始/終了間の実時間（ミリ秒）であり、JUnit XML の
`testsuite.time` や MFA内部のサービス計測値とは同一視しない。outer suite がある場合は子を
二重加算せず、nested-only は独立した最外 suite を合算する。全6 shardの
正常終端、既存 coverage guard、実行集合内の重複0を確認し、未実行キーを固定ソース・filter・
タグ・既存skip等で分類する（全重みキーとの単純一致を完全性判定にしない）。

未実行・timeout・terminal欠損は `completed=false` / `testStepExitCode=null` として扱う。
Gradleのreturn値だけでなく実際のstep outcomeも記録し、EXIT trapの0だけで成功と判定しない。
既存のFROM-CACHE/UP-TO-DATEはGradleのexit 0を維持するが、freshな採取完了とは扱わない。
採取欠損をgreenや高速化の証拠にせず、既存cap・6 shard・全テスト集合・必須集約gateを維持する。
MFAの固定prefix数値ログとMFA/billingのcase窓のみ追加採取し、テスト出力全量や秘密値は収録しない。

### 重み表の再生成タイミング

- テストクラス数が大幅に増減した（数十クラス規模の追加/削除）とき。
- 特定 shard が恒常的に他より大きく偏るようになった（重み表が古くなり、新規/削除されたテストの
  実行時間を反映できていない）とき。
- 数クラス程度の日常的な増減では、フォールバック（安定ハッシュ）が自動的に吸収するため
  都度の再生成は不要。

再生成手順: `cd backend && ./scripts/regen-shard-weights.sh`（`gh` CLI 必須。既定で
**backend-nightly-full.yml** の直近成功 run の小さい runtime artifact から再構築する。
run ID と既存の第2引数 `nightly-full|backend-ci` を明示できるが、現在 `backend-ci` はfull profileを
メタデータから確認できないため拒否する）。migration/Flyway 実測非0は明白な除外入力を拒否する
補助チェックであり、full profileの証明とはせず、固定workflow/sourceの実行内容を別証拠で確認する。同一run/head/attemptの全6 shard、fresh・exit 0・正常task終端、
class開始/終了の対応と重複0、artifact digest・安全pathを検証し、不正・欠損時は出力を変えない。
検証後のみ同一ディレクトリの一時ファイルから原子的に置換し、XMLへfallbackしない。
historical run と現在HEADの一致は要求せず、現在sourceの未計測・削除・arch/perf/skip分類と
適用判断は別manifestで検分する。生成後は上記の既存4profile coverage guardを実行する。
集計対象の回帰検証: `python3 -m unittest scripts/test_regen_shard_weights.py`。
