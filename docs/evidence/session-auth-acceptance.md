# ログアウトと認証セッションの受入記録

CMP-261017-1023 / CMP-261017-1026 の回帰確認を記録する。

修正前の b6ac38971de9 で、Java 21 による先行試験はログアウト HTTP/DB 契約12件中10件がアサーション失敗（error・skip 0）、schema 2件中1件が失敗した。wire の current は修正前から成功していた。修正後 d2ab0dfd では対象13件と schema 2件の計15件、関連4クラス42件が成功（fail・error・skip 0）。先行試験と後続試験の件数は追加ケースにより異なる。公式OpenAPI生成では認証セッションの参照と current を確認し、代理投票25 path・43 componentの不変を確認した。記録は既存のローカル試験結果の要約であり、この文書の作成時に再実行したものではない。

実機6ケース38チェックと3人の探索、およびローカル57件の内訳は [PR #3938](https://github.com/kenta-0420/mannschaft/pull/3938) に記録されている。統合後の検分・実機・探索と最終headのCIは [PR #3954](https://github.com/kenta-0420/mannschaft/pull/3954) を参照する。最後の製品統合後 main の [Docker HTTP・health確認](https://github.com/kenta-0420/mannschaft/actions/runs/39266223625/job/118106376508) は別コミット上の補足であり、実装headのCIではない。

Cookie属性は実装差分で既存のクリア処理を維持したことを検分で確認した。`AuthLogoutScopeContractIT` はCookieのmaxAge=0、偽装queryによる他人の失効防止と本人JTIのTTL範囲、Valkey障害時の本人refreshのDB失効を確認する。属性全体をそのITだけで検証したという意味ではない。
