package com.mannschaft.app.config;

import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/**
 * スコープ識別子（team / organization）のパスパラメータ変換器。
 *
 * <p>数値文字列はそのまま {@code Long} へ、非数値スラッグはリクエストパスのスコープ種別に応じて
 * team もしくは organization の内部 BIGINT ID へ解決する。</p>
 *
 * <p><b>なぜ 1 クラスで両ドメインを扱うのか:</b> Spring MVC は {@code String → Long} の変換器を
 * 高々 1 つしか選べない。team 用と organization 用に別々の {@code Converter<String, Long>} を
 * 登録すると変換器選択が曖昧になり競合する。そこで本クラスがリクエスト URI の直前セグメント
 * （{@code /organizations/{value}} か {@code /teams/{value}} か）を見て解決先ドメインを一意に決める。
 * これにより 118 の org 系コントローラの {@code @PathVariable Long} を変更せずに slug 解決を通す。</p>
 *
 * <p><b>解決先の決め方（越境の根治・CMP-261125-2124）:</b> 受け取る引数の名前が {@code organizationId} の
 * 場合は、パス（{@code /organizations/{id}}）かクエリ（{@code ?organizationId=}）かを問わず常に組織として解決する。
 * 以前は URI に {@code /organizations/} を含まないクエリの slug を TEAM として解決しており、
 * 同名のチームがあると別組織を誤照会しえた。{@code Converter<String, Long>} は引数名を知り得ないため、
 * 本クラスは {@link ConditionalGenericConverter} として対象の {@link TypeDescriptor}（{@link MethodParameter}）から
 * 引数名を取り出す。{@code organizationId} という名前の引数については、リクエスト値の一致で推測しないため
 * 取り違えない。<b>この保証は {@code organizationId} 側に限る。</b>引数名が {@code organizationId} 以外のとき
 * （{@code teamId} を含む）は従来どおり URI 直前セグメントで判定し（{@code detectScope} の値一致判定を含む）、
 * 判定できなければ TEAM とするため、{@code teamId} 側の取り違えは本クラスでは防げない。</p>
 *
 * <p><b>残る越境（別行で扱う）:</b> 引数名が {@code organizationId} ではないスコープ ID
 * （{@code scopeId}（約 73 件）、{@code organization_id}（{@code RepairPlanItemCsvController}）、
 * {@code teamId} など）は上記の従来判定のままで、同様の越境がありうる。</p>
 *
 * <p>解決に失敗した場合（存在しない slug 等）は 404 NOT_FOUND を送出する（400 ではない）。</p>
 */
@Component
public class ScopeSlugIdConverter implements ConditionalGenericConverter {

    /** この名前の引数（パス変数・クエリ）は常に組織 ID として解決する。 */
    static final String ORGANIZATION_PARAM_NAME = "organizationId";

    private final TeamService teamService;
    private final OrganizationService organizationService;

    public ScopeSlugIdConverter(@Lazy TeamService teamService,
                                @Lazy OrganizationService organizationService) {
        this.teamService = teamService;
        this.organizationService = organizationService;
    }

    @Override
    public Set<ConvertiblePair> getConvertibleTypes() {
        return Set.of(new ConvertiblePair(String.class, Long.class));
    }

    @Override
    public boolean matches(@NonNull TypeDescriptor sourceType, @NonNull TypeDescriptor targetType) {
        return true;
    }

    @Override
    public Object convert(Object value, @NonNull TypeDescriptor sourceType, @NonNull TypeDescriptor targetType) {
        if (value == null) {
            return null;
        }
        return resolve((String) value, isOrganizationParameter(targetType));
    }

    /** 対象引数の名前が {@code organizationId} か（明示名 → コンパイル時の引数名の順で判定）。 */
    private static boolean isOrganizationParameter(TypeDescriptor targetType) {
        RequestParam requestParam = targetType.getAnnotation(RequestParam.class);
        if (requestParam != null) {
            String explicit = !requestParam.name().isEmpty() ? requestParam.name() : requestParam.value();
            if (!explicit.isEmpty()) {
                return ORGANIZATION_PARAM_NAME.equals(explicit);
            }
        }
        PathVariable pathVariable = targetType.getAnnotation(PathVariable.class);
        if (pathVariable != null) {
            String explicit = !pathVariable.name().isEmpty() ? pathVariable.name() : pathVariable.value();
            if (!explicit.isEmpty()) {
                return ORGANIZATION_PARAM_NAME.equals(explicit);
            }
        }
        if (targetType.getResolvableType().getSource() instanceof MethodParameter parameter) {
            return ORGANIZATION_PARAM_NAME.equals(parameter.getParameterName());
        }
        return false;
    }

    Long resolve(@NonNull String source, boolean organizationParameter) {
        // 数値はそのまま解釈する（team / organization 共通の高速パス）
        try {
            return Long.parseLong(source);
        } catch (NumberFormatException ignored) {
            // 非数値 → スラッグとしてスコープ判定のうえ解決する
        }

        Scope scope = organizationParameter ? Scope.ORGANIZATION : detectScope(source);
        try {
            return switch (scope) {
                case ORGANIZATION -> organizationService.resolveOrgId(source);
                case TEAM -> teamService.resolveTeamId(source);
            };
        } catch (ResponseStatusException e) {
            // 既に適切なステータスを持つ例外はそのまま伝播させる
            throw e;
        } catch (Exception e) {
            // 解決失敗（不在 slug 等）は 404 に統一する（型変換失敗の 400 に落とさない）
            String label = scope == Scope.ORGANIZATION ? "組織が見つかりません: " : "チームが見つかりません: ";
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, label + source);
        }
    }

    private enum Scope { TEAM, ORGANIZATION }

    /**
     * リクエスト URI から、変換対象値の直前パスセグメントを見てスコープを判定する。
     *
     * <p>{@code /organizations/{source}} なら ORGANIZATION、それ以外（{@code /teams/{source}} を含む）は
     * TEAM と判定する。リクエストコンテキストが得られない場合は従来動作（team 解決）を既定とし、
     * 後方互換を保つ。</p>
     */
    private Scope detectScope(String source) {
        String uri = currentRequestUri();
        if (uri == null) {
            return Scope.TEAM;
        }
        String[] segments = uri.split("/");
        for (int i = 1; i < segments.length; i++) {
            if (source.equals(segments[i])) {
                String prev = segments[i - 1];
                if ("organizations".equals(prev)) {
                    return Scope.ORGANIZATION;
                }
                if ("teams".equals(prev)) {
                    return Scope.TEAM;
                }
            }
        }
        return Scope.TEAM;
    }

    private String currentRequestUri() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getRequestURI();
        }
        return null;
    }
}
