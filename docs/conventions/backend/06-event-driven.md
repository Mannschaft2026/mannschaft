<!-- `backend/.claudecode.md` §6 の本文（逐語移設）。節番号・見出しは元のまま。 -->
[← backend/.claudecode.md（索引）に戻る](../../../backend/.claudecode.md)

## 6. イベント駆動設計
機能間の連携（例: 予約作成 → 通知送信、マッチング成立 → スケジュール登録）が多いため、Service 間の直接呼び出しによる密結合を避ける。

- **方針**: イベント発行を `DomainEventPublisher` インターフェースで抽象化し、初期実装は Spring の `ApplicationEvent` + `@TransactionalEventListener` を使用する。将来的な外部メッセージキュー（RabbitMQ/Kafka等）への移行に備える。
- **イベントクラス配置**:
    - `com.mannschaft.app.common.event/` には共通のマーカーインターフェース `DomainEvent` と基底クラス（`BaseEvent` 等）のみを配置する。
    - 各機能固有のイベントクラスは、**発行元の feature パッケージ内**に配置し、`DomainEvent` を実装する（例: `com.mannschaft.app.reservation.ReservationCreatedEvent`）。
- **適用基準**: 同一機能内の処理は Service 直接呼び出し、**異なる機能間の非同期連携**にイベントを使用する。
- **トランザクション境界**: イベントリスナーには `@TransactionalEventListener(phase = AFTER_COMMIT)` を使用し、**発行元のトランザクションが正常にコミットされた後にのみ実行**されるようにする。これにより、発行元がロールバックされたのにリスナーが動作する不整合を防止する。

```java
// com.mannschaft.app.common.event.DomainEvent（マーカーインターフェース）
public interface DomainEvent {
    LocalDateTime occurredAt();  // §20 日時統一規約に準拠（Instant は使用しない）
}

// com.mannschaft.app.common.DomainEventPublisher（抽象インターフェース）
public interface DomainEventPublisher {
    void publish(DomainEvent event);  // Object ではなく DomainEvent で型安全性を確保
}

// com.mannschaft.app.config.SpringEventPublisher（初期実装）
@Component
@RequiredArgsConstructor
public class SpringEventPublisher implements DomainEventPublisher {
    private final ApplicationEventPublisher applicationEventPublisher;
    @Override
    public void publish(DomainEvent event) {
        applicationEventPublisher.publishEvent(event);
    }
}

// 例: 予約作成時に通知を送信
// reservation パッケージ内にイベントクラスを配置
// com.mannschaft.app.reservation.ReservationCreatedEvent implements DomainEvent
domainEventPublisher.publish(new ReservationCreatedEvent(reservation));

// notification パッケージが購読（トランザクションコミット後に実行）
@TransactionalEventListener(phase = AFTER_COMMIT)
@Async
public void onReservationCreated(ReservationCreatedEvent event) {
    // プッシュ通知送信
}
```

- **非同期イベント失敗時の方針**: `@Async` リスナーで例外が発生した場合は、ログ出力（ERROR レベル）+ 監査ログ記録を必須とする。即時リトライは行わず、運用監視で検知・対応する。将来的にリトライ機構（Spring Retry / メッセージキューの DLQ 等）を導入する場合は、本セクションを更新すること。

### 6.1 既存処理を AFTER_COMMIT へ移設するときの必須手順 **【#2990 / #3140】**

業務トランザクション内の直接呼び出しを `@TransactionalEventListener(AFTER_COMMIT)` へ移す是正は、
**既存テストを赤くせずに無力化する**という副作用を持つ。移設のたびに次を実施すること。

1. **移設した通知種別・イベントを観測している既存テストを洗う。**
   移設したクラスだけを grep しても足りない。`notification_type` の値（例 `'SCHEDULE_KEEP_CONVERTED'`）で
   テスト木全体を検索し、**別ドメインの契約テストが横から観測していないか**を確認する。
   L8 ではこの観点が検索網から漏れ、隣の IT が壊れて初めて気づいた。
2. **見つかったテストが `@Transactional` かどうかを確認する。**
   `@Transactional` が効いていると業務トランザクションはコミットされず、`AFTER_COMMIT` は発火しない。
   よって `notifications` に行は 1 件も生まれない。
   - 「1 件以上であること」を検証していれば**赤くなる**ので気づける。
   - **「0 件であること」を検証していると必ず通る（偽の緑）。単独では CI で永遠に検出できない。**
   - クラスに注釈が無くても、**基底クラスからの継承**や `@DataJpaTest` で実効的にトランザクショナルになる。
3. **どちらかの正しい形へ直す。**
   - **配送まで検証したい**（この戦役の正規形）: クラスから `@Transactional` を外し、
     フィクスチャ投入を `TransactionTemplate` で明示コミットする。
     金型: `ScheduleCommentNotificationPartialFailureIT`（「クラスに `@Transactional` を付けない理由」節）、
     `ScheduleNotificationTransactionBoundaryIT`。
   - **業務 TX 内で観測できる契約までで割り切る**: `@RecordApplicationEvents` を付け、
     `ApplicationEvents` で**配送イベントが publish されたこと**を検証する。配送内容はリスナー側の
     単体テストが受け持つ。先例: `ScheduleKeepConvertContractIT` AC-15b（PR #3135）。
4. **直したテストが、直す前の本番コードで赤くなることを実測する。** 赤くならないなら、
   そのテストはまだ何も検証していない。

番人 `TransactionalTestNotificationObservationGuardTest`
（`backend/src/test/java/com/mannschaft/app/common/architecture/`）が
「実効的に `@Transactional` なテストが通知の配送結果を表明している」形を CI で機械的に拒否する。
適法な例外は同クラスの `ALLOWED` に理由付きで列挙する（無条件の baseline 化はしない）。

---
