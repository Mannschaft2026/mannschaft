# CMP-261121-0728 受入確認

実装は [PR #4702](https://github.com/kenta-0420/mannschaft/pull/4702)、head `1d702c5811f3be81c0bb493636b1dcec09da2080`。Git管理外を想定するfixtureを初めからGit未初期化で作り、対象の呼出しだけ `GIT_CEILING_DIRECTORIES` に実親を設定する。実装差分はテスト1ファイルのみ。

- AC-1: `指摘4: git リポジトリの外（.git 無しの検証コピー）で素材が全て在れば従来どおり全検査を走らせる`、`指摘4: git リポジトリの外で1つでも欠ければ省略せず失敗（bytes は呼ばない）`、`AC-14: 書き込み前の展開要求。sparse なら expand の手順を出して失敗、全展開と git 管理外は通る、壊れた git は失敗` が完全・欠損・書込を検査する。
- AC-2: `C19: 祖先 core.worktree が子を指すGit管理外コピーで欠損を拒否する` は実Gitの親とplain子を作り、親のlocal `core.worktree` を子に向ける。回帰中の `env: outsideEnv(dir)` を `env: quiet` に変えて `node --test --test-name-pattern='C19:' frontend/scripts/lib/sparse-assets.test.mjs` を実行した正式redは、`/missing-outside-git/` 期待に `no-tracked-paths` を返して1件失敗。元に戻した正式greenは1件成功、0失敗・0 skip。欠損時にbytesは呼ばれない。
- AC-3: `AC-10: 本物の cone sparse で全て範囲外なら omitted`、`AC-11/指摘3: CI では省略しない（CI=true・1、GITHUB_ACTIONS=true かつ CI=false/空 のいずれも失敗）`、上記AC-14がsparse・CI・書込の既存契約を検査。本番 `sparse-assets.mjs` のGit blob `ba6cadb4054560117de72e64f536d332190601fd` は不変。別の実Git probeでも正規の外部gitdir sparseについて、通常時omitted、CI時拒否、書込・成果物の両ガードが展開を要求して停止することを確認した。
- AC-4: `node --test frontend/scripts/lib/sparse-assets.test.mjs` の候補全26件は26成功・0失敗・0 skip。候補と実commitのテスト内容のSHA-256は共に `e942bea3b4b2cf5b8540f061c3162d767707b0908d7afa9d0ac53474c34d448d`。同headの[Harness CI](https://github.com/kenta-0420/mannschaft/actions/runs/44840111920) は186/186成功、適用された11 workflowすべて成功した。

過去の観測では [PR #4684 run 44750680580 attempt 1](https://github.com/kenta-0420/mannschaft/actions/runs/44750680580/attempts/1) の欠損コピーが `missing-outside-git` 期待に対し `no-tracked-paths`、[PR #4696 run 44831293932 attempt 1](https://github.com/kenta-0420/mannschaft/actions/runs/44831293932/attempts/1) の完全コピーが `git-failed` となった。両者のrunner環境は同一ではない。Git maintenanceと削除処理の競合は説明可能な仮説だが、当時の削除時系列・実ロードライブラリが記録されておらず、過去の原因は確定していない。

正式red・greenと全件試験は専用の所有fixture rootで実行し、子プロセス終了と内容不変を確認してから所有rootを撤去、親が不存在を確認した。親が実commitと候補、各試験結果、module不変、後始末を独立検分し指摘0件。画面・API・DBおよび導線を変更しないtest-onlyのため、実機と探索は対象外。
