# CMP-261115-1635 アリシゼーション記録（組織の確認通知設定画面が組織 ID 空で API を叩く欠陥 D-F1 の修正）

- 修正: PR #4531（`8e760380f1`）。画面が組織詳細を自前で取得して数値 ID を解決する。

## 画面層（2026-11-15、D-F1 修正後）

- 実施日: 2026-11-15（JST）。手段: Playwright（Chromium、実ブラウザ・モックなし）。spec は `frontend/tests/e2e/real/groupf-rescreen-261115b.real.spec.ts`、設定は `frontend/playwright-groupf-rescreen-261115b.config.ts`
- 環境: BE 8081 は **origin/main `8e760380f1`（#4531＝D-F1 修正を含む）から bootJar を新規ビルド**して WSL で起動（出所不明の旧 jar ではない）/ FE 3017（本作業木＝上記 main 取込み済みの dev サーバー）/ 実 MySQL。CORS は OPTIONS で 3017 が 200 になることを実測してから使用
- 前提データは自前（名前・メールに `e2e-261115b` / `e2e261115b`）。組織・チーム・大会は後始末で削除済み（globalTeardown で組織・チーム 4/4 件、大会の削除は 204）
- スクリーンショット: `docs/evidence/screenshots/groupF-261115b/`

### 住民として触った範囲と観測

- 他の組織設定画面 6 つ（billing・faq-settings・notification-credits・public-settings・team-affiliation・todo-status-labels）を、組織A管理者で URL 直打ちした。すべて、見出しは 1 つ（想定どおりの画面名）・`header` 要素 1 つ（ヘッダの被りなし）・横スクロールなし・組織 ID 空の要求 0 件・4xx/5xx 0 件・エラートースト 0 件・JS エラー 0 件。同型の欠陥・退行は無し。画像 `1635-*-direct.png`
- ドロワーを開いた状態でも、この 6 画面へのリンクを自動操作では見つけられず、アプリ内遷移での確認は**未実施**（リンクは `OrganizationSidebar.vue` 等に定義されているが、折りたたみの内側にあると見られ、今回の操作では展開していない）。直打ちのみで合格とし、導線の存在は証明していない。
- 確認通知設定画面本体（アプリ内遷移・各区画の操作）では D-F2・D-F3 を観測（詳細は CMP-261002-1604-exploratory.md）。D-F1 とは無関係の別欠陥。

### 判定

画面探索: **passed**（D-F1 の同型欠陥・退行なし。ただし他設定画面のアプリ内遷移は未実施。確認通知設定画面の共通部品の欠陥 D-F2・D-F3 は別戦役の対象として未起票）。
