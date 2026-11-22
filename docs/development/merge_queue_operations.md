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
| check_response_timeout | 150 分 |
| strict（up-to-date 必須） | **解除** |

必須チェック4本（Compile & Test / OpenAPI Drift Check / Task-List PR Gate / ER Diagram Drift Check）は `merge_group` イベントに対応済み。Task-List PR Gate の merge_group 経路は `.github/workflows/task-list-pr-gate-merge-group.yml`（合成コミットの `docs/task-list.md` 構造検査）。merge group の差分は「そのエントリ自身の変更」で判定する（先行エントリは各自の group で検証済み。失敗時は queue が再構築する）。

## 投入とマージ（queue 有効時）

```bash
# PR 側の必須チェックがすべて緑（mergeStateStatus=CLEAN）になってから投入する
PR_ID=$(gh pr view <PR> --repo Mannschaft2026/mannschaft --json id -q .id)
gh api graphql -f query='mutation($id:ID!){enqueuePullRequest(input:{pullRequestId:$id}){mergeQueueEntry{state position}}}' -f id="$PR_ID"
```

- **このリポジトリは auto-merge が無効**（`allow_auto_merge=false`）。`gh pr merge <PR> --squash` は gh が「チェック未完了」と見なすと auto-merge を有効化しようとして `Auto merge is not allowed for this repository (enablePullRequestAutoMerge)` で失敗する（2026-11-22 #4758 で実測。`mergeStateStatus=CLEAN` の直後でも起きた）。投入は上の `enqueuePullRequest` を正とする（応答の `mergeQueueEntry.state=QUEUED` で投入を確認）。
- **queue 有効時は `--delete-branch` を付けない**（gh 2.92 は拒否する。cli/cli v2.92.0 `pkg/cmd/pr/merge/merge.go`）。ブランチ削除は MERGED（`state=MERGED` かつ `mergedAt` 非 null）確認後に別手順で行い、CLAUDE.md の事前許可条件（PR が MERGED で main 反映済み・HEAD/所有者/他 worktree 未使用を削除直前に確認）を満たすときだけ実施する。
- PR 側の必須チェックが未完了のまま投入しない（auto-merge 無効のため自動では入らない）。チェック完了を低頻度で確認してから投入する。
- **コマンド成功＝マージ済みではない**。必ず下記の状態判定で確認する。
- queue 有効時に限り、`gh pr update-branch` / main 取り込みの追従は**不要**（strict 解除済み。BEHIND でも queue が合成コミットで検証する）。コンフリクト（`mergeable=CONFLICTING`）のときだけ main を取り込んで解消する。
- `--admin` は queue を素通りする（bypass）。常用禁止（下記）。

## 状態判定（4 状態）

```bash
gh pr view <PR> --json state,mergedAt,mergeStateStatus,autoMergeRequest,mergeable
gh api graphql -f query='query{repository(owner:"Mannschaft2026",name:"mannschaft"){pullRequest(number:<PR>){state mergedAt isInMergeQueue autoMergeRequest{enabledAt} mergeQueueEntry{state position}}}}'
```

`gh pr view --json` に merge queue 専用フィールドは無い（gh 2.92 で確認）。脱落判定は timeline を使う（`timelineItems(last:5,itemTypes:[ADDED_TO_MERGE_QUEUE_EVENT,REMOVED_FROM_MERGE_QUEUE_EVENT]){nodes{__typename ... on RemovedFromMergeQueueEvent{reason createdAt}}}`。実 API で取得できることを確認済み）。queue 内の判定は GraphQL の `isInMergeQueue` / `mergeQueueEntry`（`state`: QUEUED / AWAITING_CHECKS / MERGEABLE / UNMERGEABLE / LOCKED、`position`）を使う。

| 状態 | 判定 | 次の動き |
|---|---|---|
| (a) PR 側必須チェック待ち | state=OPEN・`isInMergeQueue=false`・必須チェックに未完了あり（auto-merge 無効のため `autoMergeRequest` は常に null） | 待つ（低頻度の単発確認のみ）。緑になったら投入する |
| (b) queue 内で検証中 | state=OPEN・`isInMergeQueue=true`（`mergeQueueEntry` 非 null） | 待つ。`position` で順番を把握 |
| (c) queue から脱落 | 投入記録（自分で投入した事実）または timeline の `AddedToMergeQueueEvent` があり、その後 `RemovedFromMergeQueueEvent`（`reason`）が出て、state=OPEN・`isInMergeQueue=false`・未マージ | **完了扱いにしない・worktree を撤去しない**。merge_group の失敗 run を確認して原因を直し、再投入する |
| (e) queue 外（未投入／脱落未確定） | state=OPEN・auto-merge 無し・queue 外で投入履歴が不明 | 脱落と断定しない。投入済みか確認する |
| (d) MERGED | **`state=MERGED` かつ `mergedAt` 非 null** | 完了。ここで初めて後片付け |

`gh pr merge` の終了コードやコマンド出力だけでは (d) を判定しない。脱落（c）の原因は `gh run list --event merge_group --limit 5` で merge_group の run を探し、`gh run view <runId> --log-failed` で要点を取る（全ログ取得は禁止）。

## 脱落時の扱い

1. 完了扱いにせず、worktree・ブランチを残す。
2. merge_group の失敗 run から原因を特定し、根治する（rerun だけで済ませない。flaky と判断する場合も証拠を残す）。
3. 修正を push して必須チェックを再度緑にし、上記の `enqueuePullRequest` で再投入する。
4. 先行エントリの失敗による巻き添え（自分の変更に原因が無い）は、queue が再構築するため通常は自動で再検証される。脱落したときだけ再投入する。

## queue 無効時（移行前・障害で無効化している間）

旧来の `--admin` 自動許可は**廃止**した。

1. worktree で `git merge origin/main` → push。
2. CI（必須4本）が緑になるのを待つ。
3. 通常マージ `gh pr merge <PR> --squash --delete-branch`（`--admin` を付けない。queue 無効時のみ `--delete-branch` 可）。

strict が復元されている間は BEHIND になり得る。追いかけっこになる場合は admin で越えず、マスターへ報告して queue 再有効化を待つ。

## `--admin` の格下げ

`--admin` は **merge queue 自体の障害時のみ・マスター確認必須**。検分合格・CI 緑・マスターの明示承認が揃わない限り使わない。承認なしの自動許可はしない。使った場合は上奏に理由と承認を明記する。

## queue を無効化するとき（復旧手順）

queue 自体が障害で動かないとき、マスター（殿）が Ruleset を変更する。

1. **queue を無効化するときは必ず strict（up-to-date 必須）を同時に復元する**。strict なしで queue だけ外すと、合成検証が無いまま並行 PR が main に入り、main が壊れうる。
2. 無効化中は上記「queue 無効時」の手順で運用する。
3. 復旧したら queue を再有効化し、同時に strict を解除する（両者を同じ変更で切り替える）。
4. 無効化・再有効化の日時と理由を台帳または PR に残す。

関連: `.claude/commands/凱旋.md` / `.claude/commands/物見.md`
