package com.mannschaft.app.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService.SwitchVerdict;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.i18n.EarlyErrorResponseWriter;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity;
import com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity.FeatureScope;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 代理入力コンテキストフィルター（F14.1）。
 * X-Proxy-For-User-Id ヘッダーが存在する場合のみ動作し、DB再検証後に
 * ProxyInputContext をアクティブ化する。JwtAuthenticationFilter の直後に実行される。
 *
 * ヘッダーがない場合は通常入力として何もせず chain を続行する。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProxyInputContextFilter extends OncePerRequestFilter {

    static final String HEADER_PROXY_FOR = "X-Proxy-For-User-Id";
    static final String HEADER_PROXY_CONSENT = "X-Proxy-Consent-Id";
    static final String HEADER_PROXY_SOURCE = "X-Proxy-Input-Source";
    static final String HEADER_PROXY_STORAGE = "X-Proxy-Original-Storage";
    static final String HEADER_PROXY_STORAGE_ENCODING = "X-Proxy-Original-Storage-Encoding";
    private static final int MAX_ORIGINAL_STORAGE_LENGTH = 255;

    /**
     * 後見切替（GUARDIANSHIP_SWITCH）で activate する際の {@code originalStorageLocation} 固定値。
     *
     * <p>後見切替は紙原本を伴わないオンライン代理だが、{@code proxy_input_records.original_storage_location}
     * は {@code NOT NULL}（V18.012）である。切替中に F14.1 代理入力 7 機能
     * （survey/出欠/shift/announcement 既読/parking/circulation 押印）が発火すると、
     * 各 Service の {@code buildAndSaveProxyInputRecord} が {@code context.getOriginalStorageLocation()} を
     * そのまま列に書くため、ここで {@code null} を渡すと NOT NULL 制約違反で 500 になる。
     * {@link com.mannschaft.app.auth.guardianship.GuardianshipSwitchService} の切替開始記録と
     * 同一文言の固定値を入れて整合させる（03_security §3.2 二重記録）。</p>
     */
    static final String SWITCH_STORAGE_LOCATION_NA = "N/A (online guardianship switch)";

    private final ProxyInputConsentRepository proxyInputConsentRepository;
    private final ProxyInputContext proxyInputContext;
    private final ObjectMapper objectMapper;

    /**
     * 後見切替の再検証サービス（F08.9 P3c）。
     *
     * <p>{@link ObjectProvider} による遅延解決とする。後見切替経路（{@code X-Proxy-For-User-Id} のみの
     * リクエスト）を踏んだときに初めて解決するため、本フィルタを組み立てる多数の
     * {@code @WebMvcTest} スライス（auth ドメインの Service を含まない構成・100件超）に
     * {@code @MockitoBean} 追加を強いない。また proxy→auth の構築時依存を解消する
     * （切替経路を使わない限り auth ドメイン Bean は不要）。</p>
     */
    private final ObjectProvider<GuardianshipSwitchService> guardianshipSwitchServiceProvider;

    private EarlyErrorResponseWriter earlyErrorResponseWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String proxyForHeader = request.getHeader(HEADER_PROXY_FOR);

        // ヘッダーがない場合は通常入力として続行
        if (proxyForHeader == null) {
            chain.doFilter(request, response);
            return;
        }

        String consentIdHeader = request.getHeader(HEADER_PROXY_CONSENT);

        // 後見切替経路（F08.9 P3c）: consent-id ヘッダなし＝紙同意書ベースでない acting-as。
        // 保護者リンク＋年齢ゲートを毎リクエスト再検証し、PAYMENT スコープのみで activate する。
        // consent-id があれば従来の F14.1 紙同意書経路へフォールスルー（既存経路を壊さない）。
        if (consentIdHeader == null) {
            handleGuardianshipSwitch(request, response, chain, proxyForHeader);
            return;
        }

        // 必須ヘッダーの存在チェック（従来の F14.1 紙同意書経路）
        String inputSourceHeader = request.getHeader(HEADER_PROXY_SOURCE);
        String storageHeader = request.getHeader(HEADER_PROXY_STORAGE);

        if (inputSourceHeader == null || storageHeader == null
                || storageHeader.isBlank()) {
            sendError(request, response, HttpServletResponse.SC_BAD_REQUEST,
                    ProxyInputErrorCode.HEADERS_INCOMPLETE);
            return;
        }

        String originalStorageLocation;
        try {
            originalStorageLocation = normalizeOriginalStorage(storageHeader,
                    request.getHeader(HEADER_PROXY_STORAGE_ENCODING));
        } catch (IllegalArgumentException e) {
            sendError(request, response, HttpServletResponse.SC_BAD_REQUEST,
                    ProxyInputErrorCode.ORIGINAL_STORAGE_INVALID);
            return;
        }

        // 数値パース
        Long subjectUserId;
        Long consentId;
        try {
            subjectUserId = Long.parseLong(proxyForHeader.trim());
            consentId = Long.parseLong(consentIdHeader.trim());
        } catch (NumberFormatException e) {
            sendError(request, response, HttpServletResponse.SC_BAD_REQUEST,
                    ProxyInputErrorCode.IDS_INVALID);
            return;
        }

        // InputSource バリデーション
        try {
            ProxyInputRecordEntity.InputSource.valueOf(inputSourceHeader.trim());
        } catch (IllegalArgumentException e) {
            sendError(request, response, HttpServletResponse.SC_BAD_REQUEST,
                    ProxyInputErrorCode.INPUT_SOURCE_INVALID);
            return;
        }

        // SecurityContext からリクエスト者（代理者）のIDを取得
        Long proxyUserId = extractCurrentUserId();
        if (proxyUserId == null) {
            sendError(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                    ProxyInputErrorCode.AUTHENTICATION_REQUIRED);
            return;
        }

        // DB再検証: 有効な同意書が存在し、proxyUserIdが一致するか
        Optional<ProxyInputConsentEntity> consentOpt =
                proxyInputConsentRepository.findValidConsent(consentId, proxyUserId);

        if (consentOpt.isEmpty()) {
            log.warn("代理入力の同意書検証失敗: consentId={}, proxyUserId={}", consentId, proxyUserId);
            sendError(request, response, HttpServletResponse.SC_FORBIDDEN,
                    ProxyInputErrorCode.CONSENT_NOT_FOUND);
            return;
        }

        ProxyInputConsentEntity consent = consentOpt.get();

        // subjectUserIdの一致確認
        if (!consent.getSubjectUserId().equals(subjectUserId)) {
            log.warn("代理入力の対象ユーザー不一致: requested={}, consent={}", subjectUserId, consent.getSubjectUserId());
            sendError(request, response, HttpServletResponse.SC_FORBIDDEN,
                    ProxyInputErrorCode.SUBJECT_MISMATCH);
            return;
        }

        // 同意書で許可されたスコープ集合を抽出（F08.9 P3b: 決済系の要求スコープ検証の素地）
        java.util.Set<FeatureScope> grantedScopes = consent.getScopes().stream()
                .map(ProxyInputConsentScopeEntity::getFeatureScope)
                .collect(java.util.stream.Collectors.toSet());

        // 検証OK: ProxyInputContext をアクティブ化
        proxyInputContext.activate(subjectUserId, consentId, inputSourceHeader.trim(), originalStorageLocation,
                grantedScopes);
        log.debug("代理入力モード有効化: proxyUserId={}, subjectUserId={}, consentId={}",
                proxyUserId, subjectUserId, consentId);

        chain.doFilter(request, response);
    }

    /**
     * 後見切替経路（F08.9 P3c）。{@code X-Proxy-For-User-Id} はあるが {@code X-Proxy-Consent-Id} が
     * ない場合に呼ばれる。保護者リンク＋年齢ゲートを毎リクエスト再検証し、合格時のみ
     * {@code PAYMENT} スコープのみで {@link ProxyInputContext#activate} する。
     *
     * <p>境界日跨ぎ（年度末・誕生日）の自動失効は本実行時ゲートで担保する（封印後の子へは AGE_LOCKED）。
     * inputSource は {@code GUARDIANSHIP_SWITCH}、consentId は {@code null}（紙同意書を伴わない）。</p>
     */
    private void handleGuardianshipSwitch(HttpServletRequest request,
                                          HttpServletResponse response,
                                          FilterChain chain,
                                          String proxyForHeader) throws ServletException, IOException {
        // 子ユーザーIDのパース
        Long childUserId;
        try {
            childUserId = Long.parseLong(proxyForHeader.trim());
        } catch (NumberFormatException e) {
            sendError(request, response, HttpServletResponse.SC_BAD_REQUEST,
                    ProxyInputErrorCode.SWITCH_TARGET_INVALID);
            return;
        }

        // リクエスト者（保護者）の認証確認
        Long guardianUserId = extractCurrentUserId();
        if (guardianUserId == null) {
            sendError(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                    ProxyInputErrorCode.SWITCH_AUTHENTICATION_REQUIRED);
            return;
        }

        // 保護者リンク＋年齢ゲートの毎リクエスト再検証（副作用なし）。
        // ObjectProvider 遅延解決: 後見切替経路を踏んだここで初めて auth ドメイン Bean を解決する。
        SwitchVerdict verdict = guardianshipSwitchServiceProvider.getObject()
                .evaluateSwitch(guardianUserId, childUserId);
        switch (verdict) {
            case LINK_NOT_FOUND -> {
                log.warn("後見切替の再検証失敗（リンクなし）: guardianUserId={}, childUserId={}",
                        guardianUserId, childUserId);
                sendError(request, response, HttpServletResponse.SC_FORBIDDEN,
                        ProxyInputErrorCode.SWITCH_LINK_NOT_FOUND);
                return;
            }
            case AGE_LOCKED -> {
                log.warn("後見切替の再検証失敗（年齢封印）: guardianUserId={}, childUserId={}",
                        guardianUserId, childUserId);
                sendError(request, response, HttpServletResponse.SC_FORBIDDEN,
                        ProxyInputErrorCode.SWITCH_AGE_LOCKED);
                return;
            }
            case ALLOWED -> {
                // 後見切替は会費支払い・所属管理・プロフィール編集・閲覧のため PAYMENT スコープのみ付与
                // （03_security §3.2「切替中に行えること」・最小権限）。consentId は紙同意書がないため null。
                // originalStorageLocation は NOT NULL 列のため固定値を渡す（null だと F14.1 代理入力で
                // proxy_input_records 保存時に NOT NULL 制約違反 500 になる）。
                proxyInputContext.activate(childUserId, null,
                        ProxyInputRecordEntity.InputSource.GUARDIANSHIP_SWITCH.name(),
                        SWITCH_STORAGE_LOCATION_NA,
                        java.util.Set.of(FeatureScope.PAYMENT));
                log.debug("後見切替モード有効化: guardianUserId={}, childUserId={}", guardianUserId, childUserId);
                chain.doFilter(request, response);
            }
        }
    }

    /** マーカーなしの既存値は復号せず、明示されたUTF-8転送だけを一度復号する。 */
    private String normalizeOriginalStorage(String storage, String encoding) {
        String decoded = storage;
        if (encoding != null) {
            if (!"uri-component".equals(encoding)) {
                throw new IllegalArgumentException("原本保管場所の転送マーカーが不正です。");
            }
            decoded = decodeOriginalStorage(storage);
        }
        // trimで前後のCR/LFを消す前に、監査値へ制御文字が入ることを拒否する。
        if (decoded.chars().anyMatch(value -> value < 0x20)) {
            throw new IllegalArgumentException("原本保管場所に制御文字は使用できません。");
        }
        String normalized = decoded.trim();
        if (normalized.isBlank() || normalized.length() > MAX_ORIGINAL_STORAGE_LENGTH) {
            throw new IllegalArgumentException("原本保管場所は1文字以上255文字以内で指定してください。");
        }
        return normalized;
    }

    /** URLフォーム復号を使わず、literal '+'と二重エスケープをそのまま保持する。 */
    private String decodeOriginalStorage(String storage) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(storage.length());
        for (int index = 0; index < storage.length(); index++) {
            char value = storage.charAt(index);
            if (value > 0x7f) {
                throw new IllegalArgumentException("転送値はASCIIで指定してください。");
            }
            if (value == '%') {
                if (index + 2 >= storage.length()) {
                    throw new IllegalArgumentException("パーセントエスケープが不完全です。");
                }
                char highChar = storage.charAt(++index);
                char lowChar = storage.charAt(++index);
                int high = highChar <= 0x7f ? Character.digit(highChar, 16) : -1;
                int low = lowChar <= 0x7f ? Character.digit(lowChar, 16) : -1;
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("パーセントエスケープが不正です。");
                }
                bytes.write((high << 4) | low);
            } else {
                bytes.write(value);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("原本保管場所のUTF-8が不正です。", e);
        }
    }

    private Long extractCurrentUserId() {
        return SecurityUtils.getCurrentUserIdOrNull();
    }

    /**
     * 早期拒否の応答を書く（CMP-261113-1507）。共通の {@code ErrorResponse} 形式で、利用者の言語
     * （users.locale → Accept-Language）へ訳す。部品の Bean が無い縮小構成では ja 正本で同じ形を書く。
     */
    private void sendError(HttpServletRequest request, HttpServletResponse response, int status,
                           ProxyInputErrorCode code) throws IOException {
        if (earlyErrorResponseWriter != null) {
            earlyErrorResponseWriter.write(request, response, status, code);
        } else {
            EarlyErrorResponseWriter.writeJapanese(response, status, code);
        }
    }

    /** 早期拒否応答の部品（任意注入。既存の構築箇所・スライステストのコンストラクタを変えないため setter で受ける）。 */
    @Autowired(required = false)
    public void setEarlyErrorResponseWriter(EarlyErrorResponseWriter earlyErrorResponseWriter) {
        this.earlyErrorResponseWriter = earlyErrorResponseWriter;
    }
}
