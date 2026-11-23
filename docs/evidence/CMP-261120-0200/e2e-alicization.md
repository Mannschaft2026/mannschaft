# CMP-261120-0200 実機E2E・アリシゼーション結果（実装 PR #4675）（2026-11-23）

## 実機E2E（全項目合格・欠陥なし）
環境: 検証用 BE 8081（origin/main 5d0d90c110 以降を bootJar 起動）、FE 3001、DB は共有 mannschaft-mysql。本陣 8080 は 17h pull されておらず V265 未適用のため使用せず。
1. V265 適用: 265.20261122105552 success=1。適用前 users に署名付き URL 行1件（id=23, http://localhost:9000/...）→ 適用後 user/23/icon/e266061d-....png の生キーに修復、署名付き URL 行0件。
2. 言語保存を UI から2回連続: PUT /api/v1/users/me 200,200（e2e-user id=23、e2e-member id=90241）。API 直叩き ja→en→ja、en→ja も全 200。
3. 保存後も id=23 の avatar_url は user/23/icon/e266061d-175c-4228-a13a-377e8d630dd2.png のまま（http・X-Amz- を含む値に変わらない）。/settings/profile でアバター画像表示。アバター未設定 id=90241（NULL）も 200,200 で保存、NULL のまま。
4. 未ログインで PUT/GET /api/v1/users/me 直打ち → 401。FE /settings/language 直打ち → /login?redirect=/settings/language。本人ログインで直開き→表示・保存可。member がボディに id=23/userId=23 を付けても id=23 は不変（自分の行のみ更新）。PUT /api/v1/users/23 → 404。API は /me 固定でテナント区別なし（他テナント観点は該当なし）。

## アリシゼーション
環境: origin/main ad04082115（#4675 含む）の BE 8081 / FE 3001。住民2人（390x844 日本語スマホ e2e-member-1785848177、1440x900 英語デスクトップ e2e-pwui-1782136885）。
#4675 の根治は有効（言語保存・/settings/account・/settings/profile の保存でもアバター無傷）。
本件とは別の欠陥（別途起票）:
- D1 中 導線欠落: アップロードしたアバターを UI から削除できない（BE DELETE /api/v1/users/me/profile-media/{role} と FE useProfileMediaApi.deleteMedia はあるが呼ぶ画面なし）
- D2 中: /settings/profile が英語ロケールでも日本語固定（profile.vue:147 等の直書き）
- D3 中: 設定7ページ（security, email, login-history, appearance, social-profiles, seals, contact-request-blocks）に日本語直書き残存（security は PageHeader title="セキュリティ" を確認、他は pages/settings 内の日本語文字列残存まで確認）
- D4 低〜中: ダッシュボード挨拶が英語で「Good morning、Kaiin Taroさん」（DashboardPersonalPanel.vue:72 の読点・さん・'ユーザー' 直書き）。挨拶名と /settings/profile 表示名が食い違う
- D5 低 表示崩れ: /settings/account の「チーム・組織別の通知」が 390px 幅で横はみ出し（scrollWidth 411 > 390、受信中バッジが縦1文字折返し）
- D6 低 導線欠落: /settings/visibility-templates はどこからもリンクなし、個人の /settings/todo-status-labels は設定一覧に項目なし（URL直打ちのみ到達。意図は未確認）
後始末: テスト用アバター削除（204）、言語 ja に戻す、8081/3001・検証コピー撤去済。
