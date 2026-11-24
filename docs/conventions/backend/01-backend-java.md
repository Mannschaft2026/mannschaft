<!-- `backend/.claudecode.md` §1 の本文（逐語移設）。節番号・見出しは元のまま。 -->
[← backend/.claudecode.md（索引）に戻る](../../../backend/.claudecode.md)

## 1. バックエンド (Java / Spring Boot)
src/main/java/com/mannschaft/app/ 配下は「技術レイヤー」ではなく「機能（ドメイン）」ごとにパッケージを分離すること。

### 構成イメージ
```
src/main/java/com/mannschaft/app/
├── config/                          # Spring設定クラス
│   ├── SecurityConfig.java
│   ├── WebSocketConfig.java
│   ├── RedisConfig.java
│   ├── CorsConfig.java
│   ├── SwaggerConfig.java
│   ├── AsyncConfig.java             # @EnableAsync + スレッドプール定義（§15）
│   ├── TimeZoneConfig.java          # DataSource 生成時の JVM TZ 番人（§20）。設定本体は ServerTimeZoneEnvironmentPostProcessor
│   ├── RequestLoggingFilter.java    # MDC requestId/traceId 設定（§27）
│   └── SpringEventPublisher.java    # DomainEventPublisher 実装（§6）
├── common/                          # 共通部品
│   ├── ErrorCode.java               # エラーコード共通インターフェース
│   ├── BusinessException.java       # 業務例外（ErrorCode保持）
│   ├── GlobalExceptionHandler.java  # グローバル例外ハンドラー
│   ├── ApiResponse.java             # 共通レスポンスラッパー（単一用）
│   ├── PagedResponse.java           # ページネーション付きレスポンス
│   ├── ErrorResponse.java           # エラーレスポンス
│   ├── CommonErrorCode.java         # 共通エラーコード Enum
│   ├── CursorPagedResponse.java     # カーソルベースページネーションレスポンス
│   ├── CommonConstants.java         # プロジェクト横断的なシステム定数
│   ├── DomainEventPublisher.java    # イベント発行抽象インターフェース
│   └── event/                       # 共通イベント基底クラス
│       ├── DomainEvent.java         # マーカーインターフェース
│       └── BaseEvent.java
└── [feature]/                       # 例: auth, team, organization
    ├── [Feature]Controller.java     # ※役割ごとに分割可（例: AuthLoginController）
    ├── [Feature]Service.java        # ※役割ごとに分割可（例: AuthTokenService）
    ├── [Feature]Repository.java
    ├── [Feature]Entity.java
    ├── [Feature]ErrorCode.java      # 機能別エラーコード Enum
    ├── [Feature]Mapper.java         # MapStruct マッパー
    ├── [Feature]CreatedEvent.java   # 機能固有のドメインイベント
    └── dto/
        ├── [Feature]Request.java
        └── [Feature]Response.java
```

### ルール
- `com.mannschaft.app.controller` のような全機能共通のレイヤーパッケージは作成禁止。
- **パッケージ宣言:** ファイル移動・作成時は、ディレクトリ構造に合わせた `package` 宣言を正しく記述すること。
- **共通部品:** 複数機能で共有するモデルや基底クラスは `com.mannschaft.app.common` に配置すること。
- **設定クラス:** Spring の設定クラス（Security, WebSocket, Valkey/Redis, CORS, Swagger 等）は `com.mannschaft.app.config` に配置する。`common` とは分離し、アプリケーション設定専用とする。
- **依存関係:** 異なる機能間のサービスを呼び出す場合は、Repositoryを直接呼ばず、Service経由で呼び出すこと。
- **Service/Controller分割:** 機能が肥大化する場合は、役割ごとにクラスを分割すること。同一 feature パッケージ内に配置する。（例: `AuthService`, `AuthTokenService`, `AuthOAuthService`）
- **認可ファサード:** 名簿・日付・メンバー資格など `@PreAuthorize` で書けない認可は、Controller → Facade（`@Transactional` なし）→ `XxxAccessPolicy` → `AccessControlService` の順で行い、業務 Service は Policy を呼ばない。前例: shift 希望・ポジション（#3564）、学校出欠 `SchoolAttendanceAccessPolicy`（CMP-261001-0630。担任判定は `docs/security/03_role_authority_model.md` §7.1.1）。

---
