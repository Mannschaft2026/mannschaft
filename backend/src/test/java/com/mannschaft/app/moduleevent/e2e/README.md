# L1-M 実機E2E の検体入り BE

本番には `ReliableEvent` の採用がないため、L1-M の実機E2E は試験用の検体 listener / resolver を載せた BE を正規の実機 BE とする（2026-11-15 マスター裁可 S4、issue #4022）。実機E2E は通常の `/実機` 手順で行う（自作の監督基盤は使わない。S3）。

## 構成

| ファイル | 役割 |
|---|---|
| `ModuleEventRealE2eApplication.java` | 起動入口。`MannschaftApplication` に検体を明示登録して起動し、検体を投入、spec からの制御 command を受け付け、終了時に自己生成分を撤去する |
| `ModuleEventRealE2eFixtures.java` | 検体（actor 11名・組織/チーム・`ProbeEvent` と 2 listener・impact resolver・状態 snapshot・撤去） |
| `ModuleEventRealE2eBootstrapBoundaryTest.java` | 検体が通常の component 走査へ混入せず、明示 source でだけ 4 bean が登録されることの単体試験 |

## 起動

Gradle 配下（`./gradlew bootRunModuleEventE2e` の JavaExec）での起動は、ブラウザ負荷下で JVM が exit -1073741819（0xC0000005）で落ちた（2026-11-16 に3回実測）。**実機E2E では jar を作り、`java` を直接、シェルから切り離して起動する**（Gradle デーモン・ワーカーの分だけメモリを空け、Gradle の停止にも巻き込まれない）。

ただし jar 直接起動でも同日に1回、同じ 0xC0000005 で落ちている（hs_err 無し、Windows のアプリケーションログに記録）。その時点のホストは物理メモリ使用率 90%・コミット残 7.6GB / 91.6GB と逼迫しており、起動方法より**ホストのメモリ逼迫が主因**とみられる。他の重い JVM（並行セッションの Gradle 試験等）と同時に流さない。途中で落ちたら検体データが残るので、`actors.json`・`state.json` の生成 ID で撤去する（後述）。

```bash
cd backend
bash ./scripts/gradle-turnstile.sh ./gradlew bootJar moduleEventE2eFixtureJar
```

```powershell
# backend で実行。暗号鍵・R2 の環境変数（MANNSCHAFT_ENCRYPTION_KEY / MANNSCHAFT_HMAC_KEY / MANNSCHAFT_R2_*）は
# 本陣の起動用 bat と同じ値を事前に設定しておく（値はログ・commit に出さない）。
$env:MANNSCHAFT_ALLOWED_ORIGINS = 'http://localhost:3001,http://localhost:8081'
$env:MANNSCHAFT_EMAIL_SIMULATE = 'true'
$jar = (Get-ChildItem build\libs -Filter *.jar | Where-Object Name -notlike '*-plain.jar' | Select-Object -First 1).Name
Start-Process java -WindowStyle Hidden -PassThru -ArgumentList @(
  '-Xmx1g', '-XX:+UseG1GC', '-Dcom.mysql.cj.disableAbandonedConnectionCleanup=true',
  '-Dloader.path=build/module-event-e2e',
  '-Dloader.main=com.mannschaft.app.moduleevent.e2e.ModuleEventRealE2eApplication',
  '-cp', "build/libs/$jar", 'org.springframework.boot.loader.launch.PropertiesLauncher',
  '--server.port=8081', '--server.address=::')
```

- 本番の bootJar に検体 JAR を `PropertiesLauncher`（Spring Boot 3.2 以降のパッケージ `org.springframework.boot.loader.launch`）の `loader.path` で足し、`loader.main` で検体入口を指定する（`loader.path` はディレクトリ指定で配下の検体 JAR `module-event-e2e-fixture-<版>.jar` を拾う）。Gradle の JavaExec と同じクラスパス構成になる
- 必須の環境変数・引数（手順から漏れると失敗する）:
  - `MANNSCHAFT_EMAIL_SIMULATE=true` — 無いと起動を拒否する（実メール送信の防止）
  - `MANNSCHAFT_ALLOWED_ORIGINS=http://localhost:3001,http://localhost:8081` — 無いと FE 3001 からの API が CORS で全滅する
  - 暗号鍵・R2 の環境変数 — 本陣 BE と同じ値でないと既存データの復号・画像 URL 解決が壊れる
  - `--server.address=::` — 無いと Node が `localhost` を `::1` に解決した時に ECONNREFUSED になる
- `application-local.yml`（git 未追跡）は本陣 `backend/src/main/resources/` からコピーしておく（bootJar に同梱される）
- 起動後、`/actuator/health` が 200、`OPTIONS` で `Access-Control-Allow-Origin: http://localhost:3001` が返ることを実測してから spec を流す
- 停止は `controls/shutdown` を置く（撤去してから終了する）。`Stop-Process` で強制終了すると撤去されないため使わない
- 異常終了で撤去されなかった場合は、残った `actors.json` の userId、`state.json` の operationId・publication ID、run 固有の slug（`l1m-e2e-<run先頭12桁>-a/b`）で対象 ID を確定してから、`ModuleEventRealE2eFixtures.Domain#cleanup` と同じ順に ID 指定で消す（ワイルドカード DML は使わない）
- 開発中の簡易起動として `MANNSCHAFT_ALLOWED_ORIGINS=http://localhost:3001,http://localhost:8081 MANNSCHAFT_EMAIL_SIMULATE=true ./gradlew bootRunModuleEventE2e` も残している（ブラウザ負荷をかける実機E2E には使わない）

### 共通事項

- クラスパスは本番の `main` ＋ 検体パッケージだけの JAR（`moduleEventE2eFixtureJar`）。他の試験用 bean や `application-test.yml` は載らない
- プロファイル・DB・Valkey は通常の `bootRun` と同じ（`local`、`application-local.yml`）。local 以外・ループバック以外の MySQL・`mannschaft.email.simulate=false` では起動を拒否する
- 既定ポートは 8081。実行ディレクトリは既定 `backend/build/l1m-real-e2e`（`L1M_E2E_RUN_DIR` で上書き）
  - `actors.json`: 検体 actor の資格情報（run 固有のメール・ランダムパスワード。停止時に削除）
  - `state.json`: 検体の状態 snapshot（0.5 秒ごとに更新）
  - `controls/`: spec が `release-delayed` / `revoke-and-seed-quarantine` / `release-last` を空ファイルで置くと処理する。`shutdown` で終了
- 停止は Ctrl+C か `controls/shutdown`。検体 gate 解放 → worker 停止 → `final-state.json` 保存 → 自己生成 ID のデータ撤去 → context close の順に行う。撤去失敗は標準エラーに生成 ID 付きで出す

## spec

`frontend/tests/e2e/real/l1m-module-events.real.spec.ts`。BE を上記で起動し、FE を 3001 で起動したうえで実行する。FE は検証用 BE を向けるため `NUXT_PUBLIC_API_BASE=http://localhost:8081` が必須（無いと本陣 BE 8080 へ行き、検体 actor でログインできない）。

```bash
cd frontend
NUXT_PUBLIC_API_BASE=http://localhost:8081 npm run dev -- --port 3001
```
検体入り BE が無いと必ず失敗するため、`playwright-real.config.ts` は `L1M_E2E=1` が無い時に本 spec を除外する（全real一括実行には混ざらない）。

```bash
cd frontend
L1M_E2E=1 BASE_URL=http://localhost:3001 API_BASE_URL=http://localhost:8081 \
  npx playwright test tests/e2e/real/l1m-module-events.real.spec.ts --config=playwright-real.config.ts --project=chromium-real --no-deps
```

spec は最初の段で、UI 段の遷移先（`/dashboard`・`/system-admin`・`/system-admin/batches`・`/notifications`）を system actor で一度ずつ開く。Nuxt dev はルートの page・middleware をブラウザの初回要求時に変換し、vue-router はそれを読み終えるまで URL を確定しないため、起動直後の FE では SYSTEM クリックから URL 確定まで 11.5 秒かかり、期待値の既定 8 秒を超えて「`/dashboard` のまま」に見えた（2026-11-16 実測。変換済みなら 0.2〜0.6 秒。本番ビルドにこの待ちは無い）。

検体の状態は起動ごとに 1 回きりのシナリオ（遅延→回復、隔離→再開）なので、spec を再実行するときは BE を再起動する。
