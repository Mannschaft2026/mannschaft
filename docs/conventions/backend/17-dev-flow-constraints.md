<!-- `backend/.claudecode.md` §17 の本文（逐語移設）。節番号・見出しは元のまま。 -->
[← backend/.claudecode.md（索引）に戻る](../../../backend/.claudecode.md)

## 17. 開発フローの制約

### AI (Claude Code) 向けの制約（必須）
新しい機能の実装に取り掛かる際は、勝手にコードを書き始めず、必ず以下の手順を踏むこと：

1. **要件の確認とすり合わせ**: 実装する機能の詳細と、必要なテーブル定義（DDL）の案を提示し、ユーザーの承認を得ること。
2. **設計案の提示**: 機能パッケージ内のクラス構成や、APIのインターフェース案（エンドポイント名など）を提示すること。
3. **承認後の実装**: ユーザーから「Goサイン」が出た後に、初めて実際のコーディングを開始すること。

AIによる予期せぬ大規模な破壊的変更を防止し、設計の意図を正確に反映させるための制約。

### コーディングスタイルの原則（AI・人間共通）

本プロジェクトのコードは **実務経験3〜5年のプログラマが無理なく読めるレベル** を基準とする。
以下の原則をすべてのコーディング作業で遵守すること。

#### 原則1: 素直で標準的な書き方を選ぶ
- Java / TypeScript の **標準API・定番パターン** を優先して使う。ニッチなライブラリメソッドや最先端の言語機能よりも、広く知られた書き方を選ぶこと。
- **Stream・ラムダ式・インターフェースは積極的に使ってよい**。処理効率や可読性が向上する場面では for ループより Stream を優先する。ただし、`reduce` の多重ネストや `Collectors.teeing` のような使用頻度の低いメソッドは避け、`filter`, `map`, `collect`, `forEach` 等の定番オペレーションを中心に組み立てる。
- 例（TypeScript/Vue）: 高度な型パズル（Conditional Types のネスト、Template Literal Types の乱用）を避け、型が追いやすい素直な定義にする。
- **判断基準**: 「このコードを見た人が、ドキュメントやGitHub Copilotの補助なしで意味を理解できるか？」を常に自問する。

#### 原則2: コードで仕様の意図を語る
- 変数名・メソッド名・クラス名を **仕様書の用語と一致** させる。コードを読むだけで「この処理は仕様書のどの部分を実装しているか」が分かることを目指す。
- 略語は業界標準のもの（`id`, `url`, `dto` 等）に限定し、プロジェクト固有の略語は作らない。
- Boolean を返すメソッドは `is〜` / `has〜` / `can〜` で始め、条件の意味を名前だけで伝える。
- Enum の値名は仕様書の区分名をそのまま反映する。

#### 原則3: 複雑な箇所にはコメントを残す
以下に該当する箇所には **必ず日本語コメントを記載** すること:
- **ビジネスルールの分岐**: なぜその条件で分岐するのか（Why）を説明する。
  ```java
  // 安否確認は「全員回答済み」で自動クローズするが、
  // 24時間経過しても未回答者がいる場合は管理者にリマインド通知を送る
  if (response.allAnswered()) {
      safetyCheck.autoClose();
  } else if (safetyCheck.isOverdue()) {
      notifyAdminReminder(safetyCheck);
  }
  ```
- **複数テーブルを跨ぐクエリや集計ロジック**: 何のデータをどう結合しているかの全体像。
- **パフォーマンス上の理由で直感的でない書き方をしている箇所**: なぜこの書き方を選んだか。
- **外部サービス連携の前提条件や制約**: API仕様のバージョンや制限値。
- ただし、**コードを読めば自明なこと**にはコメントを付けない（`// ユーザーを取得する` のような無意味なコメントは禁止）。

#### 原則4: メソッドの構造を予測可能にする
- メソッドの冒頭で **バリデーション → データ取得 → ビジネスロジック → 結果返却** の流れを一貫させる。
- 1つのメソッドが1つの仕事だけをする。読み手が「このメソッドは何をするか」をメソッド名だけで理解でき、中身を読んで裏切られないこと。
- 途中で処理の目的が変わる場合はメソッドを分割する。

#### 原則5: 付随通知は業務トランザクションの外で発火する（Issue #2834 / CMP-056）

業務処理（予定・申請・時間割反映など）に付随する通知は、**業務コミット後に起動する**。通知永続化の失敗隔離は、**配送層の1件単位独立トランザクション**で行う。

**業務サービスから通知 Runner（`REQUIRES_NEW` の実行 Bean）を直接呼んではならない。** `@Transactional` な業務メソッドの内側で `NotificationService#createNotification` を直接呼ぶと、通知の DB 例外で業務トランザクションに rollback-only が立ち、`try/catch` で握りつぶしても業務処理（本来の保存対象）ごと巻き戻る欠陥が繰り返し発生した（Issue #2655 / #2660 / #2664 / #2834 で複数ドメイン独立に発見）。

**正規形（3要素を組み合わせる。排他の二択ではない）**:

```
業務TX
  └─ 業務イベントを publish（ApplicationEventPublisher）
       └─ COMMIT

AFTER_COMMIT + @Async("event-pool") の通知配送リスナー
  └─ NotificationDeliveryRunner#sendOne()   // 1件ごと REQUIRES_NEW
       ├─ 成功 → 通知だけ commit
       └─ 失敗 → 通知だけ rollback、外側の非TXリスナーで ERROR ログ（例外を Runner 内部で catch しない）
```

| 手法 | 解く問題 |
|---|---|
| `@TransactionalEventListener(AFTER_COMMIT)` | 通知を出してよい**因果関係**を保証する（業務がコミットされる前に通知を作らない） |
| `@Transactional(REQUIRES_NEW)` の Runner | 通知失敗の**影響範囲**を限定する（1受信者の失敗が他受信者を巻き添えにしない） |
| `@Async` | 通知配送を業務処理の**待ち時間**から切り離す |

**複数受信者がある場合、配送リスナーの中から Runner を1件ずつ呼ぶこと。** リスナー全体を1つの `REQUIRES_NEW` にしてループで通知を作り `try/catch` するだけでは、同じ「1受信者の失敗が他受信者を巻き添えにする」欠陥が再発する。共通の型は `notification/service/NotificationDeliveryRequest`（配送要求 record）・`notification/service/NotificationDeliveryRunner`（`sendOne`）。金型: `schedule/service/ScheduleCommentNotificationRunner`、`event/listener/EventAdvanceNoticeNotificationListener`、`contact/event/ContactRequestNotificationListener`。

**配送リスナーと `dispatch` は別プールに置く（Issue #2953）**: 配送リスナーは `@Async("event-pool")`、`NotificationDispatchService#dispatch` は `@Async("notification-dispatch-pool")` に固定する。`dispatch` を executor 無指定にすると `@Primary` により `event-pool` へ戻り、「event-pool のワーカーが自分自身のプールへ積む」自己投入が復活する。飽和時の拒否例外は `sendOne` の `REQUIRES_NEW` を巻き戻し、**作成済みの通知行ごと消す**。`notification-dispatch-pool` は CallerRuns のため拒否例外が発生せず、この経路が構造的に閉じる。

**visibility deny と DB 例外を区別して観測する**: `NotificationService#createNotification` は visibility deny 時に例外を投げず `null` を返す。配送リスナーは Runner の戻り値が `null` なら WARN（deny）、例外を catch したら ERROR（配送失敗）とログを分ける。

**例外を Runner のトランザクション内部で catch して監査ログを書いてはならない。** そのトランザクションが rollback-only なら監査記録も一緒に消える。境界は「非TXの Async リスナー → `try { runner.sendOne(); } catch { ERRORログ }`」であり、Runner 自身は `catch` しない。

**新しいソースを参照する際は、削除済み行を参照していないか実測確認すること。** ソースが削除された後に `AFTER_COMMIT` で通知を発火すると、可視性ガード（`ContentVisibilityChecker`）は論理削除行を除外して deny する。生存中のソースへ切り替えるか、`ReferenceType` に対応する Resolver が実在するかを `ContentVisibilityChecker` の resolver 一覧で確認してから使うこと（`NotificationSourceTypeMapper` にマッピングだけあって Resolver 未実装の `ReferenceType` は常に fail-closed deny になる罠がある）。

#### 原則5-1: 契約の明文（Issue #2990 で確定・番人が機械的に強制する）

> **付随通知は業務TX内では「イベント発行だけ」を行う。実通知は「明示的な `AFTER_COMMIT` 境界の後」、または「項目TX完了を待つ非トランザクションのバッチオーケストレータ」からのみ実行する。`@Async` / `REQUIRES_NEW` / `NOT_SUPPORTED` 単独は `AFTER_COMMIT` の代用とみなさない。通知自体が業務目的の経路は監査済み例外として明示する。**

**判定軸は `@Transactional` の有無ではなく、`AFTER_COMMIT` 境界を越えたかどうかに置く。**
`@Async` も `REQUIRES_NEW` も `NOT_SUPPORTED` も **TX への参加は切るが「業務コミット後」という因果は保証しない**。業務側が後でロールバックしても通知だけが残る**逆向きの不整合**が通ってしまう（`NotificationDeliveryRunner` の Javadoc が「業務サービスから直接呼んではならない」と禁じているのはこの理由）。したがって「`@Async` 専用 Bean からなら呼んでよい」は**誤り**である。

**とくに `@Async` の自己呼び出しは失効する。** `@Async` を付けた `protected` / `private` メソッドを**同一クラス内から無修飾で呼ぶ**と Spring プロキシを経ないため、`@Async` も `@Transactional` 境界も効かず、呼び出し元のトランザクション内で同期実行される。「`@Async` が付いているから安全」という判断は、呼び出しがプロキシを経るかを確認するまで成立しない。別 Bean へ切り出すこと（金型: `NotificationCreditMonthlyResetBatch` → `NotificationCreditResetRunner` / `NotificationCreditAlertSender`）。

**監査済み例外（契約の適用対象外）** — 通知の作成・確定こそがユースケースの本体であり、業務TXと通知を同時に巻き戻すことが正しい振る舞いの経路。番人はこれらを違反として挙げない:

- `social.service.FriendNotificationService`
- `advertising.campaign.service.AdPushChannelService`
- `family.service.CareEventNotificationService`

**自身のみ対象外の通知サービス（呼び出し元は判定する。CMP-260930-1932）** — 上のクラス粒度の免除は、そのクラスへ委譲する全呼び出し元まで沈黙させる副作用を持つ（下記「免除はクラス単位にしてはならない」）。`notification.confirmable.service.ConfirmableNotificationService` はこの副作用で自動キャンセル・市の最終認証の業務TX内同期送信を見逃していたため、自身の本体だけを対象外にするリストへ移した（番人: `SELF_EXEMPT_NOTIFICATION_SERVICES`）。業務TXから同期 `send` / `sendFromSource` を呼ぶ呼び出し元は `TX_NOTIFY_VIA_DELEGATE` として挙がり、許されるのは「業務TXと通知が原子的に巻き戻るのが正しい」と監査したメソッドだけである（番人: `AUDITED_ROLLBACK_COUPLED_CALLERS`。メソッド粒度・委譲先限定・キーのドリフトを機械検査）:

- `committee.service.CommitteeDistributionService#distribute`（伝達ログが確認通知IDを保持する。通知だけ失敗して伝達ログが残ると「伝達済みなのに届いていない」記録になる）
- `payment.service.PaymentRequestService#send`（依頼の送付と確認通知IDの保持が一体）

自動・システム通知（自動キャンセル・ペナルティ・最終認証など）は AFTER_COMMIT + `@Async` のリスナーから `ConfirmableNotificationService` を直接呼ぶ（金型: `RecruitmentAutoCancelledNotificationListener` / `MarketFinalizeConfirmationListener`）。

**監査済み例外（メソッド粒度・基準が上とは異なる）** — 上のクラス粒度の免除は「業務TXと通知を**同時にロールバックさせることが正しい**」経路だが、こちらは逆に**業務TXと通知を意図的に切り離す**経路である。共通するのは「通知の作成・確定こそがユースケースの本体で、業務コミットを待つ対象が存在しない」ことだけなので、リストを分けてある（番人: `AUDITED_EXCEPTION_METHODS`）:

- `shiftbudget.service.ShiftBudgetNotificationResendService#resend`（Issue #2990 L13。`NOTIFICATION_SEND` 失敗イベントの再送。運用者が管理 API / リトライバッチから「あの通知をもう一度送れ」と命じる経路であり、同期 API `ShiftBudgetFailedEventService#retry` の応答として結果を返す契約のため `AFTER_COMMIT` へは移せない）

**免除はクラス単位にしてはならない。** クラス粒度の免除は (i) そのクラスへ委譲するあらゆる呼び出し元を `TX_NOTIFY_VIA_DELEGATE` から外し、(ii) そのクラスに将来足される任意のメソッドを無条件で契約の外に出す。免除したい1メソッドの周りで起きた回帰まで永久に緑になる。

**免除は無条件の許可ではなく前提条件つきの許可である。** `AUDITED_EXCEPTION_METHODS` に載せたメソッドは、呼び出し元TXから切り離す宣言（`NOT_SUPPORTED` / `NEVER` / `@Async`。`REQUIRES_NEW` は業務コミット前に通知を先に確定させるので不可）を持つことを番人が機械検査する。宣言が外れた瞬間に赤くなるので、免除が回帰の隠れ蓑にならない。

**番人**: `common/architecture/NotificationTransactionBoundaryGuardTest`。許可された入口（`@TransactionalEventListener` かつ phase が `AFTER_COMMIT`。phase 省略時の既定も `AFTER_COMMIT`）だけを通す**ホワイトリスト構造**で判定する。静的解析で「実行時に TX が開いていないこと」は証明できないが、「許可された入口である」ことは判定できるためこの向きを採る。既存の負債は `src/test/resources/notification_guard/notification_tx_boundary_freeze.txt` に**正規化した違反キーの完全一致集合**として凍結してあり、**増加は常に CI fail**、減少は該当実装差分と同じ PR でのみ許可する（最終目標は baseline ゼロ）。番人が判定できない範囲（interface 経由・reflection・2ホップ以上の委譲）は番人自身の Javadoc に明記してある。

> **【重要・仕様として承認済み】番人が挙げる件数は実態の「下限」であり、総数ではない。**
> 別 Bean への委譲追跡は **1ホップ限定** である（`A → B` は見えるが `A → B → C` は見えない）。
> これは実装の未完成ではなく、**偽陽性と引き換えにしないと広げられないことを承知のうえで採った仕様上の下限**である
> （2ホップ以上は型解決の精度が落ち、無関係な呼び出しを違反に数え始める）。
> したがって **「凍結リストが 0 件になったら通知境界の負債が無くなった」とは読めない。**
> 2ホップ以上へ広げる判断は後続ロットで扱う。広げるときは、広げたことによる新規検出を
> 「本物か偽陽性か」1件ずつ実地確認してから凍結すること。

**baseline を減らすときの規律（機械強制）**: 凍結リストから行を減らす変更は、番人自身が次を CI で検証する。

- **偽陽性だった場合** — 同じ PR で削除台帳の行 `# REMOVED: <FQCN>#<method> -> <種別> : <理由>` を足すこと。番人は (a) 理由が空でないこと、(b) **そのキーが現在の判定ロジックでも実際に検出されないこと**（＝偽陽性の主張を実測で裏取り）、(c) 凍結エントリと重複しないことを検証する。
- **実際に是正した場合** — 凍結リスト先頭の `# CENSUS_FLOOR: <n>` を、是正した実装差分と同じ PR で下げること。台帳の総数（凍結エントリ + REMOVED）がこの下限を割ると fail する。**理由を書かずに行をそっと消すことはできない。**
- **検出力の下限** — 「判定ロジックを緩めて違反を消し、baseline も同時に減らす」と完全一致検証は再び成立してしまう。これを塞ぐため、分類の**手前**の量（`NOTIFY_CALL_CANDIDATE` の生ヒット数・構造条件を通過した通知発火点の数・parse したメソッド数・通知発火点を持つファイル数）を実測値で下限固定している。正規の是正は通知呼び出しを**消さず AFTER_COMMIT の入口へ移す**だけなのでこれらの量は減らないが、判定を緩めた場合は必ずいずれかが減る。この非対称性が「同時に緩める」攻撃を捕まえる。
- これらのゲート自体が空振りしていないことは `NotificationTransactionBoundaryGuardConditionTest` の**変異テスト**（理由なき削除・嘘の削除理由・実質空の理由・再検出・判定の厳格化）が固定している。

#### 原則5-2: 通知 API の書き方の契約（Issue #3039・番人の死角を作らないため）

番人は字句走査であり、次の書き方をされると**静かに検出できなくなる**。番人側で塞げるものは塞いだ（下記「塞いだ形」）が、**塞げないもの・塞ぐと偽陽性を生むものは契約として禁じる**。守られているかは可能な範囲で番人が機械的に確かめる。

**守ること（開発者への契約）**

1. **通知 API は必ず引数（宛先・種別など）を取る。引数ゼロの通知 API を作らない。**
   番人の `notifyCallOffsets` は引数ゼロの呼び出しを一律で除外している（`record` アクセサや `Object#notifyAll()` を落とすため）。引数ゼロの通知 API を足すと、その呼び出しは業務TX内にあっても検出されない。
   → **機械ゲート**: `NotificationTransactionBoundaryGuardTest#通知APIは必ず引数を取る` が、配送層クラスの public メソッドを型側から列挙して違反を fail させる。
2. **通知をメソッド参照（`notificationHelper::notify`）で遅延させない。**
   参照の生成位置は分かっても実行位置は静的には決まらない（別スレッド・コミット後・そもそも呼ばれない、のいずれもありうる）。
   **実行位置が決まらないということは「`AFTER_COMMIT` 境界の後だ」とも言えない**ということなので、契約上は違反として扱う。必要なら明示的に `AFTER_COMMIT` リスナーへ寄せること。
   → **機械ゲート**: `NOTIFY_METHOD_REFERENCE`。許可された入口（およびその境界内のヘルパ）の外で、**語彙に完全一致する名前**かつ**レシーバの型がその名前の API を実際に宣言している**メソッド参照を違反として挙げる。
   偽陽性を避けるためこの2条件を要求している——`main` にある 7 件の `::toNotificationResponse` / `::getNotifyTeamSlotNoteUpdates`（mapper・getter）は語彙の完全一致で落ちる。`src/main/java` の通知系メソッド参照は現時点で 0 件（実測）。
3. **配送層に新しい `createNotification*` API を足したら、番人の語彙 `NOTIFY_METHOD_VOCABULARY` にも足す。**
   `createNotification(?:PreAuthorized)?` は**閉じた列挙**である。語彙を `createNotification[A-Za-z]*` のように開くと、綴りが似ているだけの決済 API（`createNotificationCreditCheckoutSession`）まで拾って偽陽性になるため開かない。
   → **機械ゲート**: `#配送層の生成APIが語彙から漏れていない` が、配送層の public メソッド名と語彙を突き合わせて漏れを fail させる。

**Issue #3039 で番人側を直して塞いだ形**（もう書いても検出される）

- **別 Bean への1ホップ委譲** — `@Transactional` な業務メソッドが無印の別 Bean を呼び、その先で通知する形。レシーバの**宣言の綴りから型を引き**、その型のソースを引いて「委譲先が通知を発火するか」を判定する（`TX_NOTIFY_VIA_DELEGATE`）。**綴りが通知に見えない API（`gateway.send` 等）もこの型の軸で捕まる。**
- **`TransactionTemplate` の lambda** — `execute(...)` / `executeWithoutResult(...)` の引数の内側は、外側メソッドが無印でも構文から TX 内だと断定できる。
- **メタ注釈で `@Transactional` を持つ合成アノテーション** — 注釈の**定義側**を走査して名前を集める。
- **`AFTER_COMMIT` 入口と同名の業務オーバーロード** — 「自分で `@Transactional` を宣言しているメソッドは、`AFTER_COMMIT` リスナーの境界の内側ではない」という条件で分離した。

**Codex 独立検分（PR #3048）を受けて塞いだ形**

- **単純名の曖昧性** — 型の索引を「先勝ち」から**全候補保持**へ変えた。本番の単純名重複は実測 174 種類・428 宣言あり、
  `MaintenanceScheduleService`（admin/incident）・`PromotionService`（promotion/tournament）・`ReceiptService`（payment/receipt）のように
  委譲先になりうる型も重複している。まず**単一型 `import` と同一パッケージ**で一意に絞り（Java の名前解決そのもの）、
  それでも決まらず**かつ候補ごとに「通知を発火するか」の判定が割れる**ときだけ、違反でも合格でもなく **「判定不能」として番人を落とす**。
  「重複があるだけで落とす」設計にすると 174 種類のせいで常時赤になり番人として使えないため、**判定に影響するときだけ**落とす。
- **変数のシャドーイング**（ローカルがフィールドを隠す） — 字句走査にスコープが無いので**正しくは解けない**。
  ただし先勝ちで片方を黙って採るのはやめ、上と同じ「候補が割れたら判定不能」の扱いに統一した。
  検体 `GuardBlindSpotFixture#localShadowsField` が、**違反ではなく曖昧性として報告される**ことを固定している
  （検出できないものを検出できるフリのテストで包まないため）。
- **通知発火 API のメソッド参照** — 上記 原則5-2 の 2 を参照。

**Codex 独立検分 2巡目（番人強化の最終巡）を受けて塞いだ形**

- **継承・インターフェース経由の宣言** — 通知 API の宣言が親クラスや interface にあると、
  「レシーバの型がその API を宣言している」条件を満たせず `NOTIFY_METHOD_REFERENCE` が発火しなかった。
  `extends` / `implements` を辿って宣言を探すようにした（親が索引の外なら打ち切り）。
- **完全修飾で宣言されたフィールド** — `private final com.x.Y y;` は型が解決できないうえ
  **曖昧性としても報告されない**ため静かに追跡外だった。完全修飾は一意な名前解決なので、
  単一型 `import` と同じ扱いで解決に使う。
- **import 先が索引に無いときのフォールバック** — かつては同一パッケージ／全候補へ黙って倒れており、
  **別パッケージの同名型のソースを読んで判定する静かな誤解決**が起きえた（候補が1つに絞れるため
  曖昧性ゲートにも掛からない）。`import` は authoritative に扱い、一致する候補が無ければ
  「索引の外の型」と結論して追跡しない。
- **メソッド参照経路の曖昧性ゲート欠落** — 委譲判定は「候補間で割れたら判定不能」なのに、
  メソッド参照の経路だけが「いずれかが宣言していれば違反」という存在量化だった。
  無関係な同名候補で静かに偽陽性へ倒れうるため、**扱いを三値へ揃えた**。

**判定不能（曖昧）も是正対象として台帳に載せる**

曖昧性は違反一覧に入らないため、放置すると **CI は赤いのにロット計画上は実違反数を過少報告する**。
凍結ファイルに `# AMBIGUOUS: <FQCN>#<メソッド> : <理由>` 行を設け、
検出された判定不能がすべて台帳にあること・台帳の行が今も判定不能であること・理由が空でないことを
番人が機械的に検証する。総数は `# CENSUS_FLOOR` に算入する。現時点は 0 件（仕組みは変異テストで裏取り済み）。

**それでも残る死角（＝番人の件数は下限である）**: 2ホップ以上の委譲（**仕様上の下限**。上記の枠内を参照）、型が解決できないレシーバ（interface だけを import した DI 等）、reflection / `getBean`、`@Transactional` を持たない同名オーバーロード、`import static p.Outer.Inner;` 形のネスト型 static import、`private final p.Outer.Inner x;` 形の完全修飾ネスト型宣言（`Map.Entry` 等の JDK ネスト型を巻き込まないため意図的に閉じている）。

**凍結リストの影響区分（任務5）**: 凍結エントリは末尾に ` | <区分>` を持てる。区分は 2 つ。

| 区分 | 意味 | 典型 |
|---|---|---|
| `ROLLBACK_COUPLED` | (i) 通知の失敗が**業務TXを巻き戻す**（#2990 の本体） | 委譲先が無印 `@Transactional`（既定 REQUIRED）で呼び出し元のTXに参加 |
| `ORDERING_ONLY` | (ii) 巻き戻しはしないが**因果順序を保証しない** | 委譲先が `@Async` / `REQUIRES_NEW` で別スレッド・別TX |

区分は**ソースの実際の注釈と機械照合される**（`TX_NOTIFY_VIA_DELEGATE` については委譲先入口の `@Async` / `@Transactional` の伝播設定から導出し、台帳の値と食い違えば番人が落ちる）。分類を誤ると是正の手当てを誤るため、人手のコメントではなく機械照合を正とする。

どちらも原則5 の違反であることに変わりはないが、**是正の手当てが違う**（前者は「TXから切り離す」、後者は「AFTER_COMMIT リスナーへ移す」）ため、ロット割り当ての前に機械可読な形で分ける。区分の無い行は未分類であり、後続ロットで埋める。番人は `# CLASSIFIED_FLOOR: <n>` により **一度付けた分類が黙って外れること**だけを止める（増やしたら同じ PR で下限も上げる）。

### 人間（開発者）向けの運用
- 軽微なバグ修正、リファクタリング、ドキュメント更新については、事前の設計承認をスキップして直接実装・コミットすることを許可する。
- 大規模な機能追加や、既存のデータスキーマ・アーキテクチャに影響を与える変更については、AI同様に設計案の合意を経てから進める。

---
