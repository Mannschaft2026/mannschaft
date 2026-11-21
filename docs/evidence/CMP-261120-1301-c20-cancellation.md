# CMP-261120-1301: PR 軽量検査の取消観測

状態: **未実施**。この文書は採用済み PR 軽量検査について、自己専用 PR で取消と後続 run の解除を観測する計画と結果を記録する。戦役 CMP-261120-1301 全体の完了を示すものではない。
観測用世代: B（後続 run の待機生成用）。

## 既存の証拠

- [PR #4694](https://github.com/kenta-0420/mannschaft/pull/4694) は旧 FE lockfile workflow を削除し、検査を単一ジョブの `pr-light-checks.yml` に統合した。旧 workflow の `detect` に依存する3ジョブの job-level `always()` は新構成にない。新構成は `cancel-in-progress: false` で、レポート upload には step-level `always()` が残る。[同 PR の run](https://github.com/kenta-0420/mannschaft/actions/runs/44853098632/job/136692913173) は成功し、lockfile 変更判定は成功、変更時の検査は skip された。
- 検証専用 [PR #4695](https://github.com/kenta-0420/mannschaft/pull/4695) の [run 44824644813](https://github.com/kenta-0420/mannschaft/actions/runs/44824644813/job/136594017351) は package.json に空行を加えた変更あり経路で、base 比較、musl の npm ci、成果物削除、glibc の npm ci が成功した。
- 同 PR の [run 44829695264](https://github.com/kenta-0420/mannschaft/actions/runs/44829695264/job/136611204473) は故意の Worktree guard 構文エラー後も後続の gitleaks と lockfile 検査を実行し、ジョブ全体は failure だった。これは変更判定自体の失敗試験ではない。
- これらの run は取消試験ではない。検証両 head と PR #4694 採用コミットの workflow blob は `e4b6be8133e65a76b47065a3a06b592aa44fdd0e` で一致する。

## 今回の観測計画

自己専用の文書 PR で、同一 PR・workflow ID `425452928`・concurrency group の異なる head A、B を使う。run と job の ID、attempt、head SHA、時刻、状態、取得可能な runner と step の情報を記録する。

1. head A の run と単一 job がともに queued であることを確認する。文書を1行更新して head B を作り、B の run が pending であることを確認する。
2. A/B の repository、PR、branch、actor、workflow、head、run ID と attempt 1 を照合し、取消直前に A run/job queued と B pending を再確認する。条件が揃った場合のみ、固定した A run に通常 cancel を1回送る。受理は終端を意味しない。
3. 同一 attempt の jobs を全件取得し、件数1、同一 job ID、A run と job の `completed/cancelled`、queued または in_progress job がないことを確認する。受理後約6分以内に終端を確認できなければ未達として記録する。
4. B の同一 attempt が pending から解放され、最終的に success となることを確認する。観測できなかった中間状態は推測しない。その後 head C に実測と限界を記録し、C 自身の全適用 CI を確認する。

A が先に自然終端する、B の pending を観測できない、対象の一致を確認できないなどの場合は、今回の再現条件を満たさない。観測結果をそのまま記録し、追加の取消や再走で成功に置き換えない。

## 結果と限界

**未実施。** A/B の PR 番号、head SHA、run/job ID、各状態の観測時刻、取消応答、終端と B の結果は、観測後に追記する。

この試行は文書のみの PR で行うため、lockfile の実処理中の取消を測らない。新構成の自己 run の通常取消と、それを待つ後続 run の解除を対象とする。旧 queued 残骸の解消、旧事象の厳密な原因、第三 head による pending 置換、すべての GitHub 障害の再発防止は証明しない。
