<!-- `backend/.claudecode.md` §27 の本文（逐語移設）。節番号・見出しは元のまま。 -->
[← backend/.claudecode.md（索引）に戻る](../../../backend/.claudecode.md)

## 27. 構造化ログフォーマット

### 方針
- 本番環境では **JSON 形式** で出力する（ログ集約基盤での検索・フィルタリングに対応）
- 開発環境では **人間が読みやすいテキスト形式** で出力する

### logback-spring.xml（完全な設定例）
Logback の `logstash-logback-encoder` を使用する。appender と `<root>` を必ずセットで定義すること（appender だけでは出力されない）:
```xml
<!-- logback-spring.xml -->
<configuration>
    <springProfile name="prod">
        <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <includeMdcKeyName>userId</includeMdcKeyName>
                <includeMdcKeyName>requestId</includeMdcKeyName>
                <includeMdcKeyName>traceId</includeMdcKeyName>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="JSON" />
        </root>
    </springProfile>

    <springProfile name="local">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="DEBUG">
            <appender-ref ref="CONSOLE" />
        </root>
    </springProfile>

    <!-- テスト実行時にもログを出力する（@ActiveProfiles("test") 対応） -->
    <springProfile name="test">
        <appender name="CONSOLE_TEST" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="WARN">
            <appender-ref ref="CONSOLE_TEST" />
        </root>
    </springProfile>
</configuration>
```

出力例（本番 JSON）:
```json
{
  "timestamp": "2026-03-13T21:00:00.123+09:00",
  "level": "INFO",
  "logger": "com.mannschaft.app.auth.AuthService",
  "message": "ユーザー認証成功 userId=42",
  "userId": "42",
  "requestId": "req_abc123",
  "traceId": "trace_xyz789"
}
```

### MDC（Mapped Diagnostic Context）の活用
全リクエストに以下の情報を MDC に設定し、ログに自動付与する:
- **`requestId`**: リクエストごとに UUID を生成（`X-Request-Id` ヘッダーがあればそれを使用）。`OncePerRequestFilter` で設定する。
- **`userId`**: 認証済みユーザーの ID（未認証時は `anonymous`）。**Spring Security の認証処理完了後**に設定するため、`RequestLoggingFilter` ではなく Spring Security フィルターチェーン内（`SecurityContextHolder` から取得）で別途設定する。
- **`traceId`**: 将来の分散トレーシング対応用（現時点では `requestId` と同値でよい）

```java
// RequestLoggingFilter.java（com.mannschaft.app.config に配置）
// requestId と traceId を設定する。userId は Spring Security 認証後に別途設定。
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String requestId = Optional.ofNullable(request.getHeader("X-Request-Id"))
            .orElse(UUID.randomUUID().toString());
        MDC.put("requestId", requestId);
        MDC.put("traceId", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}

// JwtAuthenticationFilter 等の認証フィルター内で userId を設定する:
// MDC.put("userId", String.valueOf(authenticatedUserId));
// 未認証の場合: MDC.put("userId", "anonymous");
```

---
