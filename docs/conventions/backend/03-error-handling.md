<!-- `backend/.claudecode.md` §3 の本文（逐語移設）。節番号・見出しは元のまま。 -->
[← backend/.claudecode.md（索引）に戻る](../../../backend/.claudecode.md)

## 3. エラーハンドリングとAPIレスポンス設計

### 3.1 レスポンス形式
HTTPステータスコードで成功/失敗を判断する。`success` フィールドは持たない。
成功時は常に `data` フィールドでラップし、一貫性を保つ。
すべてのコントローラーは `ApiResponse<T>` または `PagedResponse<T>` を返り値とする。

**共通レスポンスクラス:**
```java
// 単一レスポンス: ApiResponse<T>
// フィールド: data (T)
ApiResponse<UserResponse> → { "data": { "id": 1, "name": "..." } }

// ページネーションレスポンス: PagedResponse<T> extends ApiResponse<List<T>>
// フィールド: data (List<T>), meta (PageMeta)
PagedResponse<UserResponse> → { "data": [...], "meta": { "total": 100, "page": 1, "size": 20, "totalPages": 5 } }

// エラーレスポンス: ErrorResponse
// フィールド: error (ErrorDetail: code, message, fieldErrors?)
ErrorResponse → { "error": { "code": "AUTH_001", "message": "...", "fieldErrors": [...] } }
```

**バリデーションエラー時の `fieldErrors`:**
```json
{
  "error": {
    "code": "COMMON_001",
    "message": "入力内容に不備があります",
    "fieldErrors": [
      { "field": "email", "message": "メールアドレスは必須です" },
      { "field": "password", "message": "8文字以上で入力してください" }
    ]
  }
}
```
バリデーションメッセージは `src/main/resources/messages.properties` に集約し、`GlobalExceptionHandler` でフィールド名＋メッセージを変換する。

### 3.2 エラーコード設計（Enum・機能別分割）
- `com.mannschaft.app.common` に共通インターフェース `ErrorCode` を配置する。
- 各機能パッケージ内に `[Feature]ErrorCode` Enum を作成し、`ErrorCode` を実装する。
- `ErrorCode` はHTTPステータスに依存しない。HTTPステータスとの紐付けは `GlobalExceptionHandler` で行う。

```java
// com.mannschaft.app.common.ErrorCode
public interface ErrorCode {
    String getCode();
    String getMessage();
    Severity getSeverity();

    enum Severity { ERROR, WARN, INFO }
}

// com.mannschaft.app.auth.AuthErrorCode
public enum AuthErrorCode implements ErrorCode {
    AUTH_001("AUTH_001", "メールアドレスまたはパスワードが正しくありません", Severity.WARN),
    AUTH_002("AUTH_002", "トークンの有効期限が切れています", Severity.WARN);
    // ...
}

// GlobalExceptionHandler 内で ErrorCode → HttpStatus のマッピングを管理
// Severity ベースのデフォルト + 個別オーバーライドの2層構造
private static final Map<ErrorCode, HttpStatus> HTTP_STATUS_MAP = Map.of(
    AuthErrorCode.AUTH_001, HttpStatus.UNAUTHORIZED,
    AuthErrorCode.AUTH_002, HttpStatus.UNAUTHORIZED
    // ... 個別マッピングが必要なもののみ登録
);

private HttpStatus resolveHttpStatus(ErrorCode errorCode) {
    // 1. 個別マッピングがあればそれを使用
    // 2. なければ Severity からデフォルト解決（ERROR→500, WARN→400, INFO→200）
    return HTTP_STATUS_MAP.getOrDefault(errorCode, defaultFromSeverity(errorCode.getSeverity()));
}
```

**設計理由:** エラー定義を特定のプロトコル（HTTP）から独立させ、バッチ処理やメッセージング処理など、非HTTPコンテキストでも再利用可能にするため。

#### 3.2.1 `Severity` の意味と `Severity.ERROR` の付与基準【必須】

`ErrorCode.Severity` の**唯一の消費者は `GlobalExceptionHandler#resolveHttpStatus`** である。ログレベルの決定には使われていない（`BusinessException` は severity に関わらず常に `log.warn`）。

したがって **`Severity.ERROR` を付けることは「既定で HTTP 500 を返す」宣言と同義**である。

| Severity | 既定 HttpStatus | 付けてよいのは |
| :--- | :--- | :--- |
| `ERROR` | **500** | **サーバ側の障害のみ**（外部連携失敗・生成/直列化失敗・データ不整合・想定外の状態） |
| `WARN` | 400 | クライアント起因（入力不備・不在・重複・状態遷移違反・所有権不一致） |
| `INFO` | 200 | 正常系扱いの通知 |

**クライアント起因のエラーに `Severity.ERROR` を付けてはならない。** 500 になると `status.is5xxServerError()` 経由で `error_reports` への記録と Slack エスカレーションまで走るため、利用者の入力ミスのたびに障害アラートが鳴る。

**是正手順（対処療法の禁止）:**

1. クライアント起因なら、まず **`Severity` を `WARN` に正す**（宣言箇所で意図が読めるようになる）。既定の 400 で妥当ならこれで足りる。
2. 既定の 400 では粗すぎる場合（不在は 404・重複や状態競合は 409・課金導線は 402 など）に限り `ERROR_CODE_STATUS_MAP` へ明示登録する。

**`Severity.ERROR` のまま `ERROR_CODE_STATUS_MAP` に 4xx を足すだけの直し方は禁止**する。定義側に誤分類を残したまま症状だけを隠すことになる。

> **既知の例外（新規に増やさないこと）:** `CHAT_015`(413) / `CHAT_019`(409) / `RECRUITMENT_204`(400) / `RECRUITMENT_207`(400) は `Severity.ERROR` のまま `ERROR_CODE_STATUS_MAP` で 4xx に上書きされている。ステータスは正しいが定義側の分類は誤ったままであり、既存テスト `GlobalExceptionHandlerTest` の `@DisplayName`（「ERROR severity だが個別マッピングで 400 になる」）がその状態を文書化している。**これらは過去の負債であって手本ではない。**

既定の 400 を上書きするときの目安:

| 状況 | ステータス |
| :--- | :--- |
| 入力不備・状態遷移違反・**件数/サイズの上限超過** | **400（既定のまま。上書き不要）** |
| 対象が存在しない（越境アクセスの存在秘匿を含む） | 404 |
| 重複（already exists）・状態遷移の競合・楽観ロック競合 | 409 |
| 支払いが必要 | 402 |
| レート制限 | 429 |

**「上限超過は 409」ではない。** 本リポジトリの「上限超過」系エラーコードは全数調査（154 件）で **400 が 113 件・409 が 21 件**であり、400 が本則である（409 側は PersonalTimetable / PointCard 等の一部ドメインに偏在する少数派）。件数上限を 409 に倒すと同一概念の兄弟コードと割れる。

#### 3.2.2 ステータスを決めるときは「同一概念の兄弟コード」を必ず確認する【必須】

ドメインをまたいで**同じ概念に別々のエラーコードが存在する**ことが多い。片方だけ触ると系統が割れる。

- 例: `ReservationErrorCode.MAX_REMINDERS_EXCEEDED`(`RESERVATION_015`) と `ScheduleErrorCode.MAX_REMINDERS_EXCEEDED`(`SCHEDULE_008`) は **enum 定数名まで同一**の「保存済みリマインダー件数上限」
- 例: `PaymentErrorCode.WEBHOOK_SIGNATURE_INVALID`(`PAYMENT_019`) と `ConnectPaymentErrorCode.WEBHOOK_SIGNATURE_INVALID`(`PAYMENT_C040`)

新規追加・ステータス変更の前に、**enum 定数名と日本語メッセージの両方で全 `*ErrorCode.java` を grep** し、同一概念のコードが別ステータスになっていないか確認すること。

回帰固定は `GlobalExceptionHandlerTest` の `ClientErrorMustNotBe500` にある。新規エラーコード追加時、クライアント起因なのに 500 を返す状態にしないこと。

### 3.3 エラーコード命名規則
`{機能プレフィックス}_{連番3桁}` の形式で命名する。

| プレフィックス | 機能 | 例 |
| :--- | :--- | :--- |
| `COMMON` | 共通（バリデーション、権限等） | `COMMON_001` |
| `AUTH` | 認証・認可 | `AUTH_001` |
| `TEAM` | チーム管理 | `TEAM_001` |
| `ORG` | 組織管理 | `ORG_001` |

新しい機能を追加する際は、対応するプレフィックスを定義してからエラーコードを作成すること。

### 3.4 メッセージのハイブリッド方式
- **バックエンド**: Enum にデフォルトメッセージを保持し、レスポンスに含めて返す。バックエンド単体でもエラー内容が完結するようにする。
- **フロントエンド**: エラーコード (`code`) をキーとして表示文言を制御できる。サーバーのメッセージをそのまま表示しても、コードに基づいて独自の文言に差し替えてもよい。
- この方式により、フロントエンドの実装が未完了でもエラー内容が把握でき、将来のi18n対応にもコードベースで移行しやすい。

### 3.5 グローバル例外ハンドラー
`com.mannschaft.app.common` に以下のクラスを配置する。

- **`BusinessException`**: `RuntimeException` を継承し、`ErrorCode` を保持する業務例外クラス。Service層からスローする。
- **`GlobalExceptionHandler`** (`@RestControllerAdvice`): 以下の例外をキャッチし、共通レスポンス形式に変換する。
    1. `BusinessException` → 保持する `ErrorCode` に基づきレスポンス生成。`ErrorCode` → `HttpStatus` のマッピングもここで管理する。
    2. `MethodArgumentNotValidException`（Bean Validation失敗） → `COMMON_001` + `fieldErrors` として変換
    3. `HttpMessageNotReadableException`（不正な JSON ボディ） → `COMMON_001`（400 Bad Request）
    4. `MethodArgumentTypeMismatchException`（パス変数やクエリパラメータの型不一致） → `COMMON_001`（400 Bad Request）
    5. `MissingServletRequestParameterException`（必須クエリパラメータの欠落） → `COMMON_001`（400 Bad Request）
    6. `ObjectOptimisticLockingFailureException`（楽観的ロック競合） → `COMMON_003`（409 Conflict、§22 参照）
    7. その他の未定義例外 → `COMMON_999`（システムエラー、500）として一律処理

### 3.6 フロントエンドのエラーハンドリング
- **`composables/useErrorHandler.ts`** を作成し、エラーコードから表示メッセージへの変換ロジックを集約する。
- **共通インターセプター**（`useApi.ts` 内）で、認証切れ（401）やシステムエラー（5xx）を自動キャッチし、トースト通知やエラーページへの遷移を行う。
- **フォームバリデーションエラー**: `fieldErrors` 配列を受け取り、各画面の VeeValidate と連携して項目別にメッセージを表示する。

---
