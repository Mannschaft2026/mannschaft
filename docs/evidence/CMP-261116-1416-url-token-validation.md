# CMP-261116-1416 URL診断の保証範囲

## 修復と検証の境界

公開FE診断のpageUrlはFEのcapture共有state・quiet・コメント再送の送信直前と、BE候補保存前でquery全体・fragment全体・userinfoを除去する。captureの明示的meta.apiUrlも連結前に除去し、既存consoleの通常／cooldownへ安全なcopyを渡す。実request URL・caller object・capabilityリンク・historyは変更しない。

正常http(s)／単一slash相対pathのraw pathを保持し、異常入力は有効で反復適用可能な `/invalid-url` とする。保存値のencoded delimiterをdecodeしない。従来のhash用path抽出は維持するが、裸host-onlyの旧空pathはcanonical `/`、異常入力は安全markerのhashへ揃う。これらと過去全行のhash同値は保証せず、旧行の移行／書換は行わない。内部HEALTH/BACKENDのpath契約は変更しない。

path内token、任意errorMessage／stack／context／userComment全文、既存保存履歴・browser history・全インフラログの全面除去を保証しない。実ユーザーの漏洩履歴は未調査。

## URL tokenの静的分類

存在だけで全件を欠陥とは判定しない。固定 `2aebb636f3f44d5d6d43e7b32f5773662bb8f8a0` のtracked sourceを正本とし、確認capabilityの寿命／使用済拒否は先行調査のe7b43a5c、掲示の区別はcore `5b8f96ce81da05abc1cb6e661de97c018dc2e3e0` で補足した。

| 用途 | 確認した境界 | 今回の扱い |
|---|---|---|
| 通常ログイン／通常WS | stompAuth.tsのCONNECT Authorization: BearerとuseChatWebSocketのURL/header分離 | frontend/appのcomposables/pluginsの限定query調査で同じaccessJWTをqueryへ付与するcallsiteは未検出。全動的組立・設定・全ページ・外部システムで不存在とは保証しない |
| 掲示端末 | 専用capabilityのSTOMP CONNECT X-Signage-Tokenは別修復。実BE SignageDisplayController:53にはGET `/signage/{token}` consumerが存在 | WS資格のURL query禁止とHTTP pathは別契約。HTTPの実到達認可を本資料で実証したとはしない |
| 将来掲示player／REST | F09.10:286-292は将来仕様。player `/signage/{token}` は:968で未実装。将来REST `/api/v1/signage/{token}/screen`・`/api/v1/signage/{token}/content/{slotId}`・`/api/v1/signage/{token}/weather` はpath token設計 | 現BE consumerまで未実装とは扱わず、「掲示tokenを全URLから撤去済み」とも言わない。将来HTTP/playerの全面再設計は今回の決裁外 |
| メール確認／reset／MFA回復 | AuthEmailEventListener:42,65,89、MfaSecurityEmailEventListener:103のqueryリンク | 確認・回復用capability。リンク方式は維持し診断への二次コピーを防御 |
| OAuth連携／メール変更確認API | useAuthApi:194／useUserSettingsApi:63のquery。AuthOAuthServiceはUUID/hash・1h、UserServiceはUUID/hash・24h、双方期限／usedAt拒否→markUsed | 通常accessJWTと区別。transportは維持。並行二重消費の排他性は未監査。5xxのmeta.apiUrl二次コピーを局所修復 |
| 広告解除／開封pixel | AdEmailChannelService:52,127、AdUnsubscribePublicController:98,179-182。専用issuer/鍵用途のJWT（解除30日、pixel180日） | ログインJWTではない。専用解除・計測のURL方式は変更しない |
| ポイントカード共有 | PointCardShareTokenService:66のmannschaft://wallet/share?tokenとPWA代替 | 共有capability。寿命／再使用性まで本資料で再監査したとはせず、一回用と断定しない |
| Google Calendar解除 | GoogleCalendarService:239→GoogleApiClient:108のrevokeUri?tokenは外部OAuth refresh token | Mannschaft accessJWTとは別の外部API契約。今回のtransport変更なし |
| その他capability | 先行棚卸しの保護者承認・feed・webhook・招待等。招待QRにはpath内tokenがある | 全producer/consumer／寿命の再監査完了とはしない。path tokenは一般query除去の保証外 |

## 試練と未実施の区分

- 先行FE：固定2d574e0c85で36件中32の実assertion failure／4成功。初期化失敗ではない。
- 先行BE：固定696b27e71aで実MockMvc／実Security／実Service／実MySQL commit後JDBC再読込。24実行／20実保存assertion failure／4成功／skip 0。自社保存Beanのmockなし。外部Redis／AIだけ共通fixtureを使用。
- 初回BEのHEALTH戻り型誤認によるcompile failureはprivacy redに数えない。
- green・最終統合CI・実機・独立探索は未確定。本資料の静的分類や試練作成を完了証拠へ昇格させない。

実機のquiet候補は同本人2tabで専用labelを新規作成→一方で削除→stale編集の既知404を通常GUIから作り、実quiet POSTを観測する方法。既存labelは使わず、正常機能を壊す5xx注入、response mock、consoleからcomposable直呼びは行わない。外送offでrootが正式実機を担当する。まだ未実施。

quietはcaptureのbadgeを開かない。制約下でcaptureを確実に起こす通常GUI導線は未特定であり、capture/commentはactualbody自動試験と既存Dialog回帰、保存境界は実MySQLITと区分する。自然なcaptureに実際に到達しない限り、実機passedとはしない。
