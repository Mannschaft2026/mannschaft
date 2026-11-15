# CMP-261114-1957 完了検証

## 対象と範囲

- scope: `tooling`
- 実装 PR: [#4516](https://github.com/kenta-0420/mannschaft/pull/4516)、実装 head `8051e53007ac4a895aac40b2ecb26fdbb9ae58db`
- 変更対象: GitHub Actions の Task-List gate、完了証拠 validator、そのテスト、完了証拠の設計説明。製品 UI・API・業務処理は変更していない。
- 4ファイルの最終 blob は実装 commit と main 採用後の head で同一。root による全文レビューの追加 finding は 0 件。

## 受け入れ条件とテスト

| AC | 確認内容 | 対応テスト |
|---|---|---|
| AC-1 | trusted base の validator を使い、比較 API エラー、無効・欠落した共通祖先、無効・不完全な変更ファイル一覧を fail-closed にする。 | `pull_request_targetはbase trusted validatorだけを実行しPR headを実行しない`; `1957 AC1/5: ready_for_reviewで実workflowがmain-onlyを許し真削除を拒否`; `AC-9: workflowはfilter all・paginate・世代判定・previous_filename・changed_files照合で実装PRを集める` |
| AC-2 | 共通祖先から変化していない完了行について、main だけの行追加・完了化・証拠リンク追加を削除と誤認しない。 | `1957 AC2: mainだけの完了行・完了化・リンク追加はPRの削除にしない`; 実 workflow の `ready_for_review`・`edited`・`synchronize` 各イベントテスト |
| AC-3 | PR 自身による完了行・証拠リンク・JSONの削除や差替え、main-only 状態と競合する PR 編集を拒否する。 | `1957 AC3: PR自身の行・リンク・JSON削除とmain-onlyへの競合変更は拒否`; `過去完了の証拠link削除・差替えを拒否する` |
| AC-4 | PR 自身が加えた完了状態を共通祖先から検出し、main も同じ行を完了済みにした場合を含めて証拠を検証する。 | `1957 AC4: mainも完了済でもPR自身の新完了は祖先から検出して検査` |
| AC-5 | 3つの pull request イベントで実 workflow を検証し、既存の scope・7列・重複ID・check世代判定を維持する。 | `1957 AC1/5: ready_for_reviewで実workflowがmain-onlyを許し真削除を拒否`; `1957 AC1/5: editedで実workflowがmain-onlyを許し真削除を拒否`; `1957 AC1/5: synchronizeで実workflowがmain-onlyを許し真削除を拒否`; `台帳をエスケープ済み縦棒込みで7列に分解しCMP重複を検出する`; `AC-7: マージ時点の世代でcheckを判定し、#3847の重複PRによるマージ後failureを採らない` |

テスト証拠: [`scripts/completion-evidence.test.mjs`](../../scripts/completion-evidence.test.mjs)。イベントテストは GitHub API をstubし、実 workflow と trusted validator を実行する。

## 回帰の red / green

- main-only の完了行追加を削除と誤認する6ケースの先行実行は、AC-2・AC-4と3イベントで5件が失敗し、真の削除拒否を確認するAC-3は成功した。修正後は同じイベント別テストがmain-only変更を許可し、真の削除を拒否する。
- 不正・欠落・不完全な `changed_files` 件数のガードは、3イベントそれぞれで先行実行時に期待した拒否がなく失敗した。各イベントの最終テストで一覧件数の欠落・不整合を拒否する。
- 修正後の `node --test scripts/completion-evidence.test.mjs` は28/28成功。関連 harness は6 suite・70件成功。補完後の boundary 単体確認 `node --test scripts/boundary-coordination.test.mjs` は36/36成功。
- 初期の CRLF fixture 準備エラーと、sparse 作業コピーに boundary manifest がなく起動できなかった harness 1件は環境準備上の失敗として保持し、製品回帰の red には数えない。manifest 補完後の boundary 確認は上記のとおり成功。

## CI とレビュー

実装 head `8051e53007ac4a895aac40b2ecb26fdbb9ae58db` に対する PR checks は18件中12件成功、6件 skipped。失敗・未完了はなく、必須4 checks は strict success / CLEAN。完了 JSON の `ci[].runId` はすべて実装 head SHAと一致する successful run である。

- [Task-List PR Gate](https://github.com/kenta-0420/mannschaft/actions/runs/43661700361)
- [Harness tests](https://github.com/kenta-0420/mannschaft/actions/runs/43661702289)
- [Compile & Test](https://github.com/kenta-0420/mannschaft/actions/runs/43661702403)
- [追加変更一覧 scan](https://github.com/kenta-0420/mannschaft/actions/runs/43661702400)

実装 PR は head SHA `8051e53007ac4a895aac40b2ecb26fdbb9ae58db` のまま merge 済み。採用後の main push では [ER Diagram Drift Check](https://github.com/kenta-0420/mannschaft/actions/runs/43662427220) と [gitleaks](https://github.com/kenta-0420/mannschaft/actions/runs/43662427230) も成功した（merge commit `9e2f4567dce7896bb8e039346ab6286808d65eb9`）。

## 実機・探索

この実装は完了証拠の CI 判定・validator とそのテストに限定されるため、scope は `tooling`。製品 GUI・API・業務操作に変更がなく、実機での画面操作と業務導線の探索は対象外として `not-applicable` を記録する。

この完了は CMP-261114-1957 の tooling 修正に限る。Signage 購読認可の P1、L1-R の R1/R3 残務、親の164型移設全体の完了を意味しない。