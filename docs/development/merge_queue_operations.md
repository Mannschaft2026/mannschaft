# merge queue 運用（main の必須チェック・凱旋・物見の前提）

> 正本: 本書。`/凱旋`・`/物見`・`/手助け`・`/手助けモーダル`（deepseek 版含む）はここに従う。Ruleset の変更は殿（マスター）が行う。**queue が有効か無効かは都度 Ruleset で確認し、両方で正しく動く手順を使う**（移行前・障害で無効化した間は「queue 無効時」節）。

## なぜ導入したか

旧運用は main の strict（up-to-date 必須）で、並行 PR が着地するたび自分の PR が BEHIND に落ち、追従→CI 再走（数十分）→また BEHIND の「追いかけっこ」になった。これを `--admin` で越える運用は検証を素通りする。merge queue は、各 PR を「直前までのエントリを取り込んだ合成コミット」で検証してから main へ入れるため、strict を解除しても main の健全性を保ったまま直列にマージできる。

## 設定値（予定）

| 項目 | 値 |
|---|---|
| merge_method | SQUASH |
| grouping | ALLGREEN |
| max_entries_to_build / max_entries_to_merge | 5 / 5 |
| min_entries_to_merge | 1 |
| wait | 5 分 |
| check_response_timeout | 360 分（2026-11-23 に 150 分から延長。混雑時の BE PR の `checks_timed_out` 脱落対策） |
| strict（up-to-date 必須） | **解除** |

必須チェック4本（Compile & Test / OpenAPI Drift Check / Task-List PR Gate / ER Diagram Drift Check）は `merge_group` イベントに対応済み。Task-List PR Gate の merge_group 経路は `.github/workflows/task-list-pr-gate-merge-group.yml`（合成コミットの `docs/task-list.md` 構造検査）。merge group の差分は「そのエントリ自身の変更」で判定する（先行エントリは各自の group で検証済み。失敗時は queue が再構築する）。

## 投入とマージ（queue 有効時）

```bash
# PR を出したら予約する。必須チェック未完了なら auto-merge が予約され、緑になると自動で queue へ入る。緑なら直接 queue へ入る
gh pr merge <PR> --repo Mannschaft2026/mannschaft --squash
```

- **auto-merge はリポジトリで有効**（2026-11-23 `allow_auto_merge=true`）。予約後は追従も投入も不要で、queue が最新 main との合成で検証してからマージする。
- `gh pr merge` が失敗したときの代替として、GraphQL で直接投入できる（必須チェック緑の後）:
  ```bash
  PR_ID=$(gh pr view <PR> --repo Mannschaft2026/mannschaft --json id -q .id)
  gh api graphql -f query='mutation($id:ID!){enqueuePullRequest(input:{pullRequestId:$id}){mergeQueueEntry{state position}}}' -f id="$PR_ID"
  ```
  2026-11-22 は auto-merge が無効だったため、`gh pr merge` が `Auto merge is not allowed` で失敗した（#4758）。
- **queue 有効時は `--delete-branch` を付けない**（gh 2.92 は拒否する。cli/cli v2.92.0 `pkg/cmd/pr/merge/merge.go`）。ブランチ削除は MERGED（`state=MERGED` かつ `mergedAt` 非 null）確認後に別手順で行い、CLAUDE.md の事前許可条件（PR が MERGED で main 反映済み・HEAD/所有者/他 worktree 未使用を削除直前に確認）を満たすときだけ実施する。
- **コマンド成功＝マージ済みではない**。必ず下記の状態判定で確認する。
- queue 有効時に限り、`gh pr update-branch` / main 取り込みの追従は**不要**（strict 解除済み。BEHIND でも queue が合成コミットで検証する）。コンフリクト（`mergeable=CONFLICTING`）のときだけ main を取り込んで解消する。
- **`--admin` は使えない**（2026-11-23 Ruleset の bypass を空にした）。queue の外で main を動かすと queue 内の全 group が作り直されて詰まるため（#4720・#4796・#4799 の bypass で #3655 が7時間 queue を出られなかった）。

## 状態判定（4 状態）

```bash
gh pr view <PR> --json state,mergedAt,mergeStateStatus,autoMergeRequest,mergeable
gh api graphql -f query='query{repository(owner:"Mannschaft2026",name:"mannschaft"){pullRequest(number:<PR>){state mergedAt isInMergeQueue autoMergeRequest{enabledAt} mergeQueueEntry{state position}}}}'
```

`gh pr view --json` に merge queue 専用フィールドは無い（gh 2.92 で確認）。脱落判定は timeline を使う（`timelineItems(last:5,itemTypes:[ADDED_TO_MERGE_QUEUE_EVENT,REMOVED_FROM_MERGE_QUEUE_EVENT]){nodes{__typename ... on RemovedFromMergeQueueEvent{reason createdAt}}}`。実 API で取得できることを確認済み）。queue 内の判定は GraphQL の `isInMergeQueue` / `mergeQueueEntry`（`state`: QUEUED / AWAITING_CHECKS / MERGEABLE / UNMERGEABLE / LOCKED、`position`）を使う。

| 状態 | 判定 | 次の動き |
|---|---|---|
| (a) PR 側必須チェック待ち | state=OPEN・`isInMergeQueue=false`・`autoMergeRequest` 非 null（予約済み） | 待つ（低頻度の単発確認のみ）。緑になると自動で queue へ入る |
| (b) queue 内で検証中 | state=OPEN・`isInMergeQueue=true`（`mergeQueueEntry` 非 null） | 待つ。`position` で順番を把握 |
| (c) queue から脱落 | 投入記録（自分で投入した事実）または timeline の `AddedToMergeQueueEvent` があり、その後 `RemovedFromMergeQueueEvent`（`reason`）が出て、state=OPEN・`isInMergeQueue=false`・未マージ | **完了扱いにしない・worktree を撤去しない**。merge_group の失敗 run を確認して原因を直し、再投入する |
| (e) queue 外（未投入／脱落未確定） | state=OPEN・auto-merge 無し・queue 外で投入履歴が不明 | 脱落と断定しない。投入済みか確認する |
| (d) MERGED | **`state=MERGED` かつ `mergedAt` 非 null** | 完了。ここで初めて後片付け |

`gh pr merge` の終了コードやコマンド出力だけでは (d) を判定しない。脱落（c）の原因は `gh run list --event merge_group --limit 5` で merge_group の run を探し、`gh run view <runId> --log-failed` で要点を取る（全ログ取得は禁止）。

## 脱落時の扱い

1. 完了扱いにせず、worktree・ブランチを残す。
2. merge_group の失敗 run から原因を特定し、根治する（rerun だけで済ませない。flaky と判断する場合も証拠を残す）。
3. 修正を push し、`gh pr merge <PR> --squash` で予約し直す（緑になると自動で queue へ入る）。
4. 先行エントリの失敗による巻き添え（自分の変更に原因が無い）は、queue が再構築するため通常は自動で再検証される。脱落したときだけ再投入する。
5. **台帳の行は変更申告で足すので、台帳が原因の脱落は原則起きない。旧手順の PR と台帳（`docs/task-list.md` と月別 `docs/task-list/YYYY-MM.md`）だけの衝突は自動修復される**（CMP-261124-1222 ④: 行の追加・更新は `node scripts/task-list-ledger.mjs add` / `update` で `docs/task-list/changes/` に PR ごとに別ファイルの申告を作るため、PR どうしが台帳で衝突しない。月別ファイルの直接編集は段3 から PR ゲートが赤にする。月別ファイルへの反映は台帳 bot の畳み込み PR〔`task-list-fold.yml`〕だけが行い、畳み込み PR が脱落しても実効台帳は変わらないので次の起動で作り直される）（CMP-261124-1222。`.github/workflows/task-list-conflict-autofix.yml`、本体 `scripts/task-list-conflict-autofix.mjs`）。main への push ごと（取りこぼしは30分毎の schedule）に、GitHub App（`vars.LEDGER_BOT_APP_ID`・`secrets.LEDGER_BOT_PRIVATE_KEY`、権限は contents:write・pull_requests:write・workflows:write の3つ）が次を行う。
   - **対象条件**: open・base=main・同一リポジトリの head（fork は除外）・`no-ledger-autofix` ラベルなし・作者が台帳 bot（`mannschaft-ledger-bot`）でない PR のうち、次のどちらか。(a) `mergeable=CONFLICTING` で、マージ待ちの意思がある（auto-merge 予約済み、または timeline の最新の merge queue イベントが `RemovedFromMergeQueueEvent` で `reason` が `merge_conflict`）。予約が無く、`manual`（人が外した）や `failed_checks` で脱落した PR は (a) の対象にしない（`reason` は GraphQL では String。実測値は `merge_conflict` / `failed_checks` / `manual` / `merged`）。(b) 段3: head が月別ファイル・索引を変えている（PR の files に `docs/task-list.md` か `docs/task-list/*.md`〔`changes/` を除く〕がある。100件超で一覧が欠けるときも対象にして git で確かめる）。(b) は `mergeable`・予約に関係なく対象で、変換後も auto-merge は予約しない（マージ待ちの意思が分からないため）。
   - **修復**: `.git/info/attributes` に `docs/task-list.md merge=union` と `docs/task-list/*.md merge=union`（台帳の新配置の月別・legacy ファイル。CMP-261124-1222）を書き（PR 側の `.gitattributes` に依存しない）、`git merge-tree --write-tree` で head と main をマージする（作業木を使わず PR のコードは実行しない。失敗時は何も書かないので `merge --abort` 相当の後始末は不要）。台帳以外にも衝突が残る・merge 失敗場合はスキップしてログに残す。main の取り込みで `.github/workflows/` が変わる merge も push を試みる（App に workflows:write があるため）。push が workflows 権限不足で拒否された場合だけ `skip-workflow-permission` としてログに残す。
   - **構造検査**: union 後の台帳を main 側の `scripts/task-list-structure-check.mjs` で検査し（両配置対応。新配置では索引と月別・legacy の全ファイルを横断し、配置違反も見る。同じ月ファイルを2つの PR が同時に新規作成した add/add も union で両行が残ることを統合試験で確かめてある）、合格したときだけ merge commit を fast-forward で push する（force しない。先に head が動いていれば次回に回す）。push 後、予約が消えていれば `enablePullRequestAutoMerge(SQUASH, expectedHeadOid=作成した merge commit)` で予約し直し（push 後に誰かが head を進めていたら予約せず、その旨をコメントする）、PR に merge commit SHA を書いたコメントを1件残す（予約失敗もそのコメントに書く）。
   - **構造検査 NG は人手**: 同じ行を PR と main の両方で編集すると union は旧行と新行を両方残し、CMP-ID 重複になる。このときは push せず PR にコメントする（同じ head には1回だけ）。main を取り込んで手で解消し、`gh pr merge <PR> --squash` で予約し直す。
   - **除外と上限**: 自動修復させたくない PR には `no-ledger-autofix` ラベルを付ける。暴走防止に、同じ PR を直近24時間で3回直したらそれ以上は直さず、その旨をコメントする（以後は人手）。回数はコメントではなく PR の commit 履歴で数える（作者/コミッタが App の bot、またはメッセージに固定印 `Ledger-Autofix: task-list-conflict-autofix` がある commit）。コメント投稿の失敗は握りつぶさず run を赤にする。
   - **旧手順の PR の申告への変換**（段3。`planChangePort`）: 月別ファイル・legacy.md、または旧単一ファイル配置の `docs/task-list.md` に行を直接書いた PR は、分岐点（merge-base）の行→head の行の差分を PR の編集とみなし、行追加を `add` 申告、行更新を `update` 申告（`base` は分岐点の実効行の sha256）へ変換して、月別ファイル（旧単一ファイル配置なら索引も）を main の内容に戻した merge commit を同じ手順（構造検査・施行後の等式検査・fast-forward push・24時間3回の上限・固定印・head 反映待ち・expectedHeadOid の再予約）で push する。三者比較: main の実効行が分岐点と同じなら申告へ変換 / main が既に PR と同じ結果なら申告不要（月別ファイルを戻すだけ）/ 分岐点・main・PR の三者が異なれば push せずコメントのみ。行の削除・月別ファイルの行以外（見出し・表ヘッダ）の変更・旧単一ファイルの前置きの変更・分岐点で未畳み込みの申告がある行の直接編集も変換せず、PR に「`add` / `update` の申告で手で直して」とコメントする（同じ head には1回だけ）。新配置の PR の索引の前置きの変更（規約文書の変更）は変換の対象外でそのまま残す。
   - **手元で直すとき**: 月別ファイルへの直接編集を `git checkout origin/main -- docs/task-list/<月>.md` で main に戻し、同じ内容を `node scripts/task-list-ledger.mjs add` / `update` の申告で作り直す。申告が fork・孤児で赤なら `node scripts/task-list-ledger.mjs rebase <申告ファイル>` で base を取り直す（git の rebase ではない）。続けて `node scripts/task-list-structure-check.mjs docs/task-list.md --base origin/main` を通してから push する。
   - 手動実行は Actions の workflow_dispatch（`dry_run` 既定 true。判定だけ出す）。App の設定が無い環境では失敗させずにスキップする。

## queue 無効時（移行前・障害で無効化している間）

旧来の `--admin` 自動許可は**廃止**した。

1. worktree で `git merge origin/main` → push。
2. CI（必須4本）が緑になるのを待つ。
3. 通常マージ `gh pr merge <PR> --squash --delete-branch`（`--admin` を付けない。queue 無効時のみ `--delete-branch` 可）。

strict が復元されている間は BEHIND になり得る。追いかけっこになる場合は admin で越えず、マスターへ報告して queue 再有効化を待つ。

## `--admin` の格下げ

Ruleset の bypass は空なので、`--admin` は通常は失敗する。**merge queue 自体の障害時に限り、マスター確認のうえで** Ruleset の `bypass_actors` に OrganizationAdmin（`bypass_mode: always`）を一時的に戻し、対処が終わったら直ちに空へ戻す（どちらも GET で照合する）。承認なしの自動許可はしない。使った場合は上奏に理由と承認を明記する。

## queue を無効化するとき（復旧手順）

queue 自体が障害で動かないとき、マスター（殿）が Ruleset を変更する。

1. **queue を無効化するときは必ず strict（up-to-date 必須）を同時に復元する**。strict なしで queue だけ外すと、合成検証が無いまま並行 PR が main に入り、main が壊れうる。
2. 無効化中は上記「queue 無効時」の手順で運用する。
3. 復旧したら queue を再有効化し、同時に strict を解除する（両者を同じ変更で切り替える）。
4. 無効化・再有効化の日時と理由を台帳または PR に残す。

関連: `.claude/commands/凱旋.md` / `.claude/commands/物見.md`
