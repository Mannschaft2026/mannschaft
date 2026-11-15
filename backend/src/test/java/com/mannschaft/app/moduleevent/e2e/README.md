# L1-M 実機E2E の検体入り BE

本番には `ReliableEvent` の採用がないため、L1-M の実機E2E は試験用の検体 listener / resolver を載せた BE を正規の実機 BE とする（2026-11-15 マスター裁可 S4、issue #4022）。実機E2E は通常の `/実機` 手順で行う（自作の監督基盤は使わない。S3）。

## 構成

| ファイル | 役割 |
|---|---|
| `ModuleEventRealE2eApplication.java` | 起動入口。`MannschaftApplication` に検体を明示登録して起動し、検体を投入、spec からの制御 command を受け付け、終了時に自己生成分を撤去する |
| `ModuleEventRealE2eFixtures.java` | 検体（actor 11名・組織/チーム・`ProbeEvent` と 2 listener・impact resolver・状態 snapshot・撤去） |
| `ModuleEventRealE2eBootstrapBoundaryTest.java` | 検体が通常の component 走査へ混入せず、明示 source でだけ 4 bean が登録されることの単体試験 |

## 起動

```bash
cd backend
MANNSCHAFT_ALLOWED_ORIGINS=http://localhost:3001,http://localhost:8081 ./gradlew bootRunModuleEventE2e
```

- クラスパスは本番の `main` ＋ 検体パッケージだけの JAR（`moduleEventE2eFixtureJar`）。他の試験用 bean や `application-test.yml` は載らない
- プロファイル・DB・Valkey は通常の `bootRun` と同じ（`local`、`application-local.yml`）。local 以外・ループバック以外の MySQL・`mannschaft.email.simulate=false` では起動を拒否する
- 既定ポートは 8081。実行ディレクトリは既定 `backend/build/l1m-real-e2e`（`L1M_E2E_RUN_DIR` で上書き）
  - `actors.json`: 検体 actor の資格情報（run 固有のメール・ランダムパスワード。停止時に削除）
  - `state.json`: 検体の状態 snapshot（0.5 秒ごとに更新）
  - `controls/`: spec が `release-delayed` / `revoke-and-seed-quarantine` / `release-last` を空ファイルで置くと処理する。`shutdown` で終了
- 停止は Ctrl+C か `controls/shutdown`。検体 gate 解放 → worker 停止 → `final-state.json` 保存 → 自己生成 ID のデータ撤去 → context close の順に行う。撤去失敗は標準エラーに生成 ID 付きで出す

## spec

`frontend/tests/e2e/real/l1m-module-events.real.spec.ts`。BE を上記で起動し、FE を 3001 で起動したうえで実行する。

```bash
cd frontend
BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081 \
  npx playwright test tests/e2e/real/l1m-module-events.real.spec.ts --config=playwright-real.config.ts --project=chromium-real --no-deps
```

検体の状態は起動ごとに 1 回きりのシナリオ（遅延→回復、隔離→再開）なので、spec を再実行するときは BE を再起動する。
