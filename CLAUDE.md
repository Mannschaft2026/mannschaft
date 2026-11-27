# CLAUDE.md — Mannschaft 開発ガイド

> 経緯・実例・運用の細目・補助資料は [`docs/development/claude-md-details.md`](docs/development/claude-md-details.md) に移してある（本書は毎回守る規則と参照先だけを置く）。

## コミュニケーション言語

- 会話・説明・コメント・コミットメッセージ・ドキュメントはすべて **日本語** で記述すること
- コード内の変数名・関数名・クラス名は英語（仕様書の用語と一致させる）

---

## 作業開始前の必須確認

コーディングを始める前に必ず `pwd`（または `git worktree list`）で自分の作業ディレクトリを確認すること。

- ✅ `.claude/worktrees/agent-xxxxx/` 配下 → そのまま作業してよい
- ❌ `C:/Claude/mannschaft` 直下 → **作業を中断し、殿に報告する**
  - ただし「大名システム活用ルール」の**例外**節に該当する場合はこの限りではない

足軽 worktree は牧場 PNG 素材3ディレクトリ（約876MB）が**未展開**（sparse checkout）。牧場の素材・実バイト検証・Docker ビルド・BE の実素材依存テストが要る作業の前は `node scripts/worktree-assets.mjs expand`（終わったら `shrink`）。本陣は常に全展開。詳細は [`docs/development/worktree_operations.md`](docs/development/worktree_operations.md#足軽-worktree-の-sparse-checkout運用の入口)。

worktree 隔離の原則（ここが唯一の正本）: `main` への直接コミット禁止。**本陣 `C:\Claude\mannschaft` での実装・commit は禁止**（pre-commit フックと Claude PreToolUse フックで機械的に拒否。クローン直後・新環境のセットアップは [`docs/development/honjin_protection_setup.md`](docs/development/honjin_protection_setup.md)）。大規模作業は `git worktree add` で物理的に隔離してから着手する。**【セットアップ必須】クローン直後・新環境では、両フック（git pre-commit・Claude PreToolUse）のセットアップ手順を上記文書で読み、必ず実施すること。**

---

## 自動実行ポリシー

「Do you want to proceed?」などの確認ステップは省略して自動で実行すること。
テストで作成したと確認できるデータと、そのテスト専用の関連データの削除・後始末は、ユーザーが事前に許可している（2026-10-17「テストで作成したデータの消去は許可するとメモしておいて」）。この範囲では毎回の削除確認を求めない。削除前に対象ID・作成元・関連範囲を確認し、既存の利用者データや作成元不明のデータを含めない。

ユーザーは2026-10-25、PR #4097・#4098・#4099 の作業用ブランチ削除を許可し、今後も自分の作業で作成したブランチについて、PR が MERGED で main への反映を確認し、対象ブランチ名と HEAD、所有者、および他 worktree・他担当で未使用であることを削除直前に確認できた場合のローカル／リモート削除を事前許可した。この条件を満たす削除は都度確認を求めない。squash merge では祖先関係だけで判定せず、PR の採用内容と main の反映を照合する。未取り込みの固有 commit、所有・利用状況が未確認のブランチ、再利用で HEAD が変わったブランチ、main や共有の長期ブランチは対象外とする。

ただし以下は例外として必ず確認を取ること：
- 破壊的なgit操作（force push, reset --hard、上記の事前許可の対象外となるブランチ削除）
- 本番環境への変更

---

## AI 開発フロー（必須）

新機能・既存DB/アーキテクチャへの影響が大きい変更を行う前に、以下の順序を守ること:

1. **要件確認** — 実装内容・DDL案をユーザーに提示し承認を得る
2. **設計案提示** — クラス構成・APIインターフェース案を提示する
3. **承認後に実装** — Goサインが出てから初めてコーディングを開始する

軽微なバグ修正・リファクタリング・ドキュメント更新は事前承認不要。
詳細: `backend/.claudecode.md` §17

---

## 大名システム（Agent）活用ルール **【必須】**

### 役割定義（詳細は `backend/.claudecode.md §28`）

| 役職 | 実体 | 責務 |
|---|---|---|
| **マスター** | ユーザー（人間） | 大方針・最終承認 |
| **殿** | メイン Claude（このセッション） | 軍議主催・家老への指示。**直接コーディング禁止** |
| **家老** | `Explore`/`Plan` サブエージェント | 偵察・設計・タスク分解 |
| **足軽** | `Agent(isolation:"worktree")` | 個別タスクの実装・テスト |

### 正しい実行フロー

1. **`/軍議`** — 家老に偵察・設計を命じ、陣立て書＋**受け入れ条件**をまとめる。**上奏の前に Codex の軍議検分を既定で通す**（AC 一覧自体の漏れは実装後の検分では構造的に拾えないため。Codex が未ログイン・リミット停止等で使えないときのみスキップし、上奏に未実施＋理由を明記）
2. **マスターの御裁可**（「よきにはからえ」など）を得てから出陣
3. **`/試練`** — 受け入れ条件から**失敗するテスト（red）を先行作成**（実装漏れ根治のため実装より前に書く）
4. **`/出陣`** — 足軽を `Agent(isolation:"worktree")` で起動、試練を green 化（実装）
5. **`/検分`** — 成果物のレビュー＋**受け入れ条件↔テスト↔実装のトレーサビリティ照合**
6. **`/実機`** — 実機E2E（ユーザー視点・モックなし）。**権限あり／権限なし／他テナントのロール横断を必須**とし、URL 直打ちまで確認する
7. **`/アリシゼーション`** — 実機E2E の**後に必ず行う**。決めたシナリオをなぞる実機E2E とは別種の欠陥（とくに**導線の欠落**）を出すため。実機が全緑でもアリシゼーションは別の欠陥を出すので、実機の緑を実施省略の根拠にしてはならない

**⑥⑦を飛ばして戦役を閉じてはならない。** 単体・IT・CI がすべて緑でも、「画面から到達できない」類の欠陥は原理的に検出できない。実施できない事情があるときは、**PR 本文と台帳に未実施である旨と理由を明記**すること（経緯は詳細文書）。

**開発作業は原則として必ず大名システム（Agent サブエージェント）経由で実行すること。** BE/API はテスト先行、FE/設計が薄い機能は従来順で可。

よく使うコマンド（dev/build/test/lint 等）は詳細文書 §12。開発ハーネスの入口は [`docs/development/harness-engineering.md`](docs/development/harness-engineering.md)、環境準備は [`harness-environment.md`](docs/development/harness-environment.md)、完了証拠の正本手順は [`completion-evidence.md`](docs/development/completion-evidence.md)。`/出陣`・`/検分 claude` は Dynamic Workflows で足軽の並列起動を表現できる（オプトイン。`/検分` の既定検分者は `codex` で Codex による独立検分が走る。Workflow 検分は `claude` を明示。機械的タスクは sonnet/haiku・低 effort、難所は opus・medium に固定（詰まったら high）。コミット/マージは `gh`）。詳細: [`daimyo_workflow_migration.md`](docs/development/daimyo_workflow_migration.md)。

### 台帳 `docs/task-list.md` の扱い

戦役をまたぐ課題の正本（git追跡・永続、行の粒度は戦役単位）。`docs/task-list.md` は索引（前置き・規約のみ。表の行は持たない）、行は ID の月の月別ファイル `docs/task-list/YYYY-MM.md`（旧形式 ID は `docs/task-list/legacy.md`）にある。全体の表は `node scripts/task-list-ledger.mjs render [--open] [--priority P1] [--month YYYY-MM]`。1戦役内の進捗地図は `.claude/campaigns/*.md`（`.gitignore` 済・揮発、完了後削除）。

- **コードと同じく worktree + PR で扱う。本陣の作業木で直接編集してはならない**（下記「例外」節の対象外。別セッションの commit で、競合すら起こさず編集が消える）
- 新しい行の ID は **`CMP-YYMMDD-HHMM`（JST）**。`date '+%y%m%d-%H%M'` で生成してそのまま使う（最大番号を調べる手順は不要）。既存行の ID は書き換えない
- **新規行は、ID の月のファイル `docs/task-list/YYYY-MM.md` の表の末尾に追加し、無ければ作る**（途中挿入は重複が静かに main へ入る。索引 `docs/task-list.md` に CMP 行を置くと構造検査が赤になる。旧配置で書いた行は `node scripts/task-list-ledger.mjs port-diff <base> <head>` で移せる）
- **表の列は必ず7つ**（ID / 戦役 / 状態 / 依存 / 完了条件 / 証拠(PR/テスト) / 台帳）。補足は列を増やさず既存列へ畳む。セル内の本物の `|` は（コードスパン内でも）`\|`
- **未完の行は状態セルの先頭に優先度 `P1`〜`P5` を必須**とする（例: `P1 未着手`。完了・取り下げは不要）。**P1 から順に片付ける**。基準は `docs/task-list.md` 冒頭「優先度」節
- 番人 `TaskListCmpIdDuplicateGuardTest`（新旧両形式・全ファイル横断）が重複・列数・優先度漏れを CI で検出する。重複が検出された場合は、後から merge された側が採番し直す。細目・経緯は詳細文書

### 文脈を短く保つ（トークン費用の本丸）

費用を決めるのは「文脈の長さ × 往復回数」。殿は文脈30万トークンを目安にタスクの切れ目で `/compact` か `/引継` で区切る。足軽は1体1タスクとし、報告は要点だけ返させる。大きなファイルやログは絞り込んでから読む。詳細: `/役割分担`「文脈を短く保つ」。

### 横展開型の戦役における担当重複の防止 **【必須】**

型が確立して誰でも同じパターンを横展開できる戦役（同一ドメインの複数箇所への一括適用など）は、並行セッションによる**二重実装事故**が起きやすい。PR が出た時点では実装は既に完了しているため、PR を見て担当を調整するのでは手遅れ。

- **着手前**: 正本 issue のコメントを読み、対象箇所の担当が既に宣言されていないか確認する
- **着手時**: 対象クラス名を挙げて正本 issue にコメントで宣言する（PR を立ててからでは遅い）
- **断念・中断時**: その旨をコメントして担当を解放する（黙って離脱しない）
- **残件の判定**: 「変換後にコードがどこへ移るか」を踏まえて行う。移設元（既存の業務クラス）だけを grep すると、正規形で処理が Runner / Listener / Event クラス等の移設先へ移っている場合、完了済みを未着手と誤判定する。移設先の有無を確認するのが確実

### 例外（メインディレクトリで直接やってよい作業）

- ユーザーとの軽い対話・質問への回答
- 1〜2ファイル限定の即時的な修正で、コミットせず確認だけする場合
- ドキュメントの軽微な追記（**ただし `docs/task-list.md` と `docs/task-list/` 配下は対象外**。軽微に見えてもコミットする場合は必ず worktree + PR）
- worktree のクリーンアップなど git 管理操作そのもの（`git worktree add/remove/prune` と、**マージ済みローカルブランチの削除** `git branch -d/-D`。後者は本陣保護フックでも明示的に許可している。作成・改名・upstream 設定は本陣では拒否されるため worktree で行うこと）

### WSL検証資産の完了ゲート

新たなLinux/WSL検証コピーは `node scripts/verification-run.mjs --source <clean Git root> --evidence-dir <新規外部dir> -- <command>` を共通入口とする。完了時は `--check` の要処置を確認し、自身のコピー・専用cache・配布物・起動プロセス・検証コンテナ/専用volumeを所有確認して撤去する。必要証跡は正本へ確定し、ローカル重複raw・全量archiveを残さない。中断保持は所有者・用途・理由・再確認期限・再開条件を記録する。SIGKILL/ホスト障害の残存、管理外の既存コピーは無差別削除しない。詳細は [worktree運用](docs/development/worktree_operations.md#wsl検証コピーの寿命管理)。

worktree は作業完了後に必ず掃除する（agent 完了直後にその commit を本リポへ統合してから `git worktree remove --force`。定期の一括掃除は `/陣払い`、既定7日以上前。**進行中の agent の worktree は削除しない**）。隔離が必須な理由・起動すべき場面・並列セッションの作法・コマンド集は [`docs/development/worktree_operations.md`](docs/development/worktree_operations.md)。

---

## ドキュメント更新ルール

**コードを修正・追加した場合、必ず以下も更新すること（実装とドキュメントは常に同期させる。省略しない）:**
- `README.md`（機能・構成・API変更時）
- `docs/` 配下の該当ドキュメント（設計・機能仕様変更時）
- `backend/BACKEND_CODING_CONVENTION.md`（BE規約変更時）
- `frontend/FRONTEND_CODING_CONVENTION.md`（FE規約変更時）
- `backend/.claudecode.md`（プロジェクト構成規約変更時）
- `docs/architecture/er/`（migration・Entity を変更したら `cd backend && ./gradlew generateErDiagrams` で E-R 図を再生成してコミット。CI の ER Diagram Drift Check が再生成漏れを検出する）

---

## 規約ドキュメント（必読）

| ファイル | 内容 |
|---|---|
| `backend/.claudecode.md` | プロジェクト全体構成規約（Spring Boot / Nuxt.js）|
| `backend/BACKEND_CODING_CONVENTION.md` | Javaコーディング規約 |
| `frontend/FRONTEND_CODING_CONVENTION.md` | TypeScript / Nuxt.jsコーディング規約 |
| `TEST_CONVENTION.md` | テスト規約 |
| `docs/security/README.md` | セキュリティ横断方針 |
| `docs/architecture/er/README.md` | 実スキーマから自動生成したドメイン単位の E-R 図（テーブル設計・ドメイン境界の前に見る） |

実装前に必ず確認すること。**認可・Cookie・セキュリティヘッダー・依存関係に関わる変更時は `docs/security/` を参照**すること。決済・Connect・サブスクリプション・税・Webhook 署名に関わる実装やレビューでは、記憶や推測に頼らず Stripe 公式スキルを引くこと（一覧は詳細文書。実体は `.agents/skills/` にあるが、クローン直後は `.claude/skills/` のリンクが無く呼び出せないので、必要になったら `npx skills add https://docs.stripe.com` を再実行してリンクを張り直すこと）。

---

## プロジェクト概要

- **バックエンド:** Spring Boot (Java) — `backend/`
- **フロントエンド:** Nuxt 3 (TypeScript / Vue 3) — `frontend/`
- **インフラ:** Docker Compose — `docker-compose.yml`
- **ドキュメント:** `docs/`（設計・機能仕様）
- **本番の配置:** BE は AWS、FE は Cloudflare（同一オリジン。Cloudflare が BE へ流すのは `/api/**` と `/ws` だけ）。本番環境はまだ構築されていない（台帳 CMP-260826-2129）
- **描画方針:** 公開ページ（ログイン画面を含む）は SSR、ログイン後はページ遷移をなるべく発生させない SPA。実装規則は `frontend/FRONTEND_CODING_CONVENTION.md` §4a
- **利用クライアント:** 現在は Web のみ（スマートフォンは PWA で対応）。将来 Android / iOS のネイティブアプリに対応するので、API を Web 専用の前提で作らない。詳細は `backend/.claudecode.md` §2

### ポート一覧・常駐サーバーのポート規約

本陣と検証・E2E 用 worktree でポートを分ける（奪い合い事故防止）: Spring Boot API は本陣 8080 / 検証用 8081（`--args='--server.port=8081'`）、Nuxt dev server は本陣 3000 / 検証用 3001（`npm run dev -- --port 3001`）。MySQL 8.0 は 3306（`mannschaft-mysql`）、Valkey は 6379（`mannschaft-valkey`）。

- ⚠️ **検証用 BE の CORS 許可オリジン**（`application.yml:94` の `mannschaft.allowed-origins`、既定 `localhost:3000,8080`）**に 3001 を足さないと全 API が落ちる**。起動時に上書き: `MANNSCHAFT_ALLOWED_ORIGINS=http://localhost:3001,http://localhost:8081 ./gradlew bootRun --args='--server.port=8081'`
- テスト（試練）はこの規約の対象外（Testcontainers 自動採番でポート固定しない）

### 型定義の管理

- `frontend/app/types/` 配下に手動管理（145+ファイル）
- `frontend/app/types/generated/index.ts` は openapi-typescript による自動生成（手動編集禁止）。再生成は `cd frontend && npm run generate:types`（`docs/openapi.json` が入力）。Backend API 変更後は必ず再生成してコミット
- 新規 API の型は生成型を優先し、既存の手動型は段階的に移行する

---

## Git運用ルール

- 作業は `feature/[issue番号]-[説明]` ブランチで行う（`main` 直接コミット禁止・worktree 隔離は「作業開始前の必須確認」）
- コミットメッセージは日本語で要約を記載（例: `機能追加: ユーザー認証APIの実装`）
- 完了後はPRを作成してCIが合格してから `main` へマージ
- **Flyway 採番**: `V{major}.{yyyyMMddHHmmss}__{説明}.sql`。major は origin/main 全体の最大 major+1、minor はタイムスタンプ必須（連番禁止、`FlywayTimestampNamingGuardTest` が拒否）。採番は `date -u '+%Y%m%d%H%M%S'`（詳細: `backend/.claudecode.md` §18）

---

## i18n ルール

- UIに表示する文字列は **直書き禁止**。ロケールファイルに追加してから `$t('key')` で参照すること
- ロケールファイル: `frontend/app/locales/{ja,en,zh,ko,es,de}/{common,auth,validation,landing}.json`（6言語すべてに、その言語の訳を入れる。ja 以外に日本語を置かない。番人 `frontend/tests/unit/i18n/locale-no-japanese-residue.spec.ts` が CI で検出する。意図的に日本語を残す値は `frontend/tests/unit/i18n/locale-residue-allowlist.json` に理由付きで載せる）
- デフォルトロケール: `ja`

---

## 障害対応の原則 — 根治治療を徹底すること **【必須】**

バグや障害に遭遇した際、**対処療法（症状隠し・エラー握りつぶし・一時しのぎ）を禁止**し、必ず根本原因を特定して根治治療を行うこと。

1. **原因の連鎖を最後まで追う** — 表面的なエラーメッセージで判断せず、真の原因にたどり着くまで掘り下げる
2. **症状を隠さない** — `.catch(() => {})`・`try-catch` でのエラー握りつぶし、フラグでの分岐回避は禁止。壊れているなら正直に報告し修正する
3. **未実装は未実装として対処する** — FE が呼ぶ API が BE に無い場合、誤魔化さず API を実装する
4. **Flyway 失敗は SQL のバグ自体を修正**してから再適用する（`flyway repair` だけで済ませない）
5. **ビルドエラーは根本から直す** — キャッシュクリア・再起動の「たまたま通った」を正とせず原因を理解して直す

---

## 禁止事項

- `main` ブランチへの直接コミット、本陣での大規模実装・commit（「作業開始前の必須確認」）
- **対処療法でバグを切り抜けること**
- TypeScript の `any` 使用（原則禁止）
- `types/generated/` への直接編集（自動生成。再生成は `cd frontend && npm run generate:types`）
- UIへの文字列直書き（i18nルール参照）
- 複雑な型パズル（Conditional Types のネスト、Template Literal Types の乱用）

---

## アーキテクチャ思想 **【実装時必読】**

Mannschaft は将来のマイクロサービス分割を見据えた**モジュラーモノリス**として設計する（ドメイン単位でパッケージ分割し、ドメイン間はID参照＋Service経由のみ）。DB設計の原則:

1. クロスドメインFK禁止（整合性はアプリ層で保証、インデックスのみ張る）
2. CASCADE DELETE は同一ドメイン内のみ許可
3. コアエンティティ（users/teams/organizations）は `deleted_at` による論理削除（users だけは、退会から30日後に下記4のバッチが物理削除する）
4. ユーザー退会は二段モデル。**退会の受付直後に個人情報を消去**（弱匿名化）し、**30日後にバッチ（`AccountPurgeService`）がユーザー本体と残りの個人データを物理削除**する（強匿名化。2026-11-17 マスター確認: 実装が正）。投稿・履歴・統計は本人との紐付けを断って残す。決済履歴・領収書は法令のため退会後も7年保持する（`backend/.claudecode.md` §8）（GDPR Art.17準拠。詳細: [`docs/architecture/withdrawal_flow_immediate_anonymization_fix.md`](docs/architecture/withdrawal_flow_immediate_anonymization_fix.md) §1.3/§13.12）
5. `@Transactional` はドメイン内に閉じる（越境時はコメントで理由明記）
6. 新規テーブルは `UuidV7Entity` 継承（主キーUUIDv7。マスタテーブル・シングルトン表は自然キー/固定値IDのまま可）
7. テナントスコープ（`organization_id` 絞り込み）Repository は `AbstractTenantAwareRepository` 継承

既存テーブルの構造・ドメイン間の参照は、まず `docs/architecture/er/README.md`（実スキーマ自動生成の E-R 図）を見ること。

**DB変更・新規テーブル・ドメイン境界に関わる実装時は必ず** [`docs/architecture/domain_db_design_principles.md`](docs/architecture/domain_db_design_principles.md) **を読むこと**（コード例・例外区分の判定基準・なぜこの設計かの背景を含む詳細版）。
