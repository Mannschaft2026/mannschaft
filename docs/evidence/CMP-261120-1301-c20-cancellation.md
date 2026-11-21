# CMP-261120-1301: PR 軽量検査の取消観測

状態: **取消の実測は未達、通常取消 POST は総数0回**。この文書は採用済み PR 軽量検査について、自己専用 PR で取消と後続 run の解除を観測する計画と結果を記録する。戦役 CMP-261120-1301 全体の完了を示すものではない。
過去の観測用世代 B・D は、それぞれ初回・追加観測の後続 run を生成するために作成した。

## 既存の証拠

- [PR #4694](https://github.com/kenta-0420/mannschaft/pull/4694) は旧 FE lockfile workflow を削除し、検査を単一ジョブの `pr-light-checks.yml` に統合した。旧 workflow の `detect` に依存する3ジョブの job-level `always()` は新構成にない。新構成は `cancel-in-progress: false` で、レポート upload には step-level `always()` が残る。[同 PR の run](https://github.com/kenta-0420/mannschaft/actions/runs/44853098632/job/136692913173) は成功し、lockfile 変更判定は成功、変更時の検査は skip された。
- 検証専用 [PR #4695](https://github.com/kenta-0420/mannschaft/pull/4695) の [run 44824644813](https://github.com/kenta-0420/mannschaft/actions/runs/44824644813/job/136594017351) は package.json に空行を加えた変更あり経路で、base 比較、musl の npm ci、成果物削除、glibc の npm ci が成功した。
- 同 PR の [run 44829695264](https://github.com/kenta-0420/mannschaft/actions/runs/44829695264/job/136611204473) は故意の Worktree guard 構文エラー後も後続の gitleaks と lockfile 検査を実行し、ジョブ全体は failure だった。これは変更判定自体の失敗試験ではない。
- これらの run は取消試験ではない。検証両 head と PR #4694 採用コミットの workflow blob は `e4b6be8133e65a76b47065a3a06b592aa44fdd0e` で一致する。

## 初回の観測計画

自己専用の文書 PR で、同一 PR・workflow ID `425452928`・concurrency group の異なる head A、B を使う。run と job の ID、attempt、head SHA、時刻、状態、取得可能な runner と step の情報を記録する。

1. head A の run と単一 job がともに queued であることを確認する。文書を1行更新して head B を作り、B の run が pending であることを確認する。
2. A/B の repository、PR、branch、actor、workflow、head、run ID と attempt 1 を照合し、取消直前に A run/job queued と B pending を再確認する。条件が揃った場合のみ、固定した A run に通常 cancel を1回送る。受理は終端を意味しない。
3. 同一 attempt の jobs を全件取得し、件数1、同一 job ID、A run と job の `completed/cancelled`、queued または in_progress job がないことを確認する。受理後約6分以内に終端を確認できなければ未達として記録する。
4. B の同一 attempt が pending から解放され、最終的に success となることを確認する。観測できなかった中間状態は推測しない。その後 head C に実測と限界を記録し、C 自身の全適用 CI を確認する。

A が先に自然終端する、B の pending を観測できない、対象の一致を確認できないなどの場合は、今回の再現条件を満たさない。観測結果をそのまま記録し、追加の取消や再走で成功に置き換えない。

## 結果と限界

初回は取消条件が揃わず、通常取消の POST は **0回**。PR [#4713](https://github.com/kenta-0420/mannschaft/pull/4713) の記録は次のとおり（時刻は UTC）。

- A（head `7e43c7abcdd6aa9a57c8ae83411b29348e5bcb7d`）の [run 44863327854](https://github.com/kenta-0420/mannschaft/actions/runs/44863327854) は 03:53:13 に queued、job `136721381117` は 03:53:15 に queued と取得された。その後、job は 03:54:10 に開始し 03:55:54 に終了、run/job とも自然に completed/success となった。
- B（head `1997aeb71bbc5d6900bc98cdc54b9fef33458a05`）の [run 44863552690](https://github.com/kenta-0420/mannschaft/actions/runs/44863552690) は 03:55:43 に作成され、03:56:38 の取得時は in_progress、後に completed/success。B の pending は観測されなかった。B の作成は A job 終了より前なので、pending が存在しなかったとは断定できない。

したがって、初回は先行 run を取消して後続 pending が解放される条件を観測できなかった。

## 追加観測の結果

C が active の間に D の pending を観測し、条件成立時に C を通常取消する計画だった。実際には最終確認時に C がすでに成功しており、active 条件の検査で停止した。取消処理には進まず、初回を含む通常取消 POST は総数 **0回**。

- C（head `151bf481f30b4366c664abc6cc3af2aee37d3812`）の [run 44864404601](https://github.com/kenta-0420/mannschaft/actions/runs/44864404601) は初期取得で in_progress。job `136724721804` は 04:06:05 に開始し 04:07:13 に completed/success、run は 04:07:16 の取得時に completed/success だった。
- D（head `1e6f52049ce088cbe40e204de2fde3d51082a8de`）の [run 44864476897](https://github.com/kenta-0420/mannschaft/actions/runs/44864476897) は 04:06:58 に生成され、04:07:15 の取得時に queued。pending は観測されず、job `136725021534` は 04:07:16 に開始して 04:08:32 に completed/success、run も completed/success だった。時刻は UTC、run の状態は各取得時点、job の開始・終了は API の記録である。

追加観測でも先行 run の取消終端と、それを待つ後続 run の解除は実測できなかった。pending が存在しなかったとは断定できない。A/B・C/D の結果を保持し、追加の観測ペア、再走、取消は行わない。

## 限界

この試行は文書のみの PR で行うため、lockfile の実処理中の取消を測らない。新構成の自己 run の通常取消と、それを待つ後続 run の解除を対象とする。旧 queued 残骸の解消、旧事象の厳密な原因、第三 head による pending 置換、すべての GitHub 障害の再発防止は証明しない。
