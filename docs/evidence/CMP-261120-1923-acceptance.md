# CMP-261120-1923 受入確認

実装: [PR #4689](https://github.com/kenta-0420/mannschaft/pull/4689)、head `392f549c47f420dbedfa530930663dcd5988a79b`。台帳と証拠等を読む通常CLIの `git show` 上限を有限の16 MiBに変更した。判定ロジックとCI条件は変更していない。

- AC-1・AC-2: 1 MiB超の7列台帳を含む実Git fixtureで、修正前は有効な完了遷移が `spawnSync git ENOBUFS` で失敗。修正後は成功し、証拠なし完了は証拠JSONの欠落を理由に拒否した。
- AC-3: 通常CLIは17 MiBの台帳を容量エラーで拒否する。証拠なし完了も拒否し、既存のschema・API検査とCI条件は変更していない。
- AC-4: `node --test scripts/completion-evidence.test.mjs` は既存28件と新規3件の計31件が成功した。実装最終headのHarness CIは185件すべて成功し、17 MiB拒否ケースも含む。

Codexが差分と各条件を独立検分し指摘0件、実装commit 05aba483の31件成功を確認した。最終head 392f549cの対象3ファイルは同一である。Claudeによる外部検分は承認レビューに拒否されたため実施せず、Codexが代替した。アプリの画面・APIや導線を変更しないtoolingのため、画面実機・探索は対象外。ローカルCLIはGitHub API上のPR/CI状態を検証せず、全履歴を対象とした長時間CLI走査は実施していない。
