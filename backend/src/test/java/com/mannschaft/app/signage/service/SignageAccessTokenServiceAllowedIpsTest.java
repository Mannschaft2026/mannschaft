package com.mannschaft.app.signage.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.signage.SignageErrorCode;
import com.mannschaft.app.signage.entity.SignageAccessTokenEntity;
import com.mannschaft.app.signage.entity.SignageScreenEntity;
import com.mannschaft.app.signage.repository.SignageAccessTokenRepository;
import com.mannschaft.app.signage.repository.SignageScreenRepository;
import com.mannschaft.app.signage.service.SignageAccessTokenService.IssueSignageTokenRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SignageAccessTokenService} の許可 IP（allowedIps）検証の単体テスト（CMP-261127-1814）。
 *
 * <p>allowedIps を指定したトークンは許可外の送信元を拒否し、拒否は無効トークンと同じ
 * SIGNAGE_002 に畳む（トークンの実在を漏らさない）。未指定は従来どおり送信元を問わない。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignageAccessTokenService 許可IP検証 単体テスト")
class SignageAccessTokenServiceAllowedIpsTest {

    private static final String TOKEN = "plain-token";
    private static final String HASH = "h".repeat(64);

    @Mock
    private SignageAccessTokenRepository tokenRepository;
    @Mock
    private SignageScreenRepository screenRepository;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private SecretTokenVault vault;

    @InjectMocks
    private SignageAccessTokenService service;

    private void storedToken(String allowedIpsJson) {
        given(vault.hash(TOKEN)).willReturn(HASH);
        SignageAccessTokenEntity entity = SignageAccessTokenEntity.builder()
                .screenId(1L).tokenHash(HASH).name("t").createdBy(1L)
                .allowedIps(allowedIpsJson).build();
        given(tokenRepository.findByTokenHashAndIsActiveTrue(HASH)).willReturn(Optional.of(entity));
    }

    @Test
    @DisplayName("allowedIps未指定のトークンは送信元を問わず成功する")
    void 未指定は送信元を問わず成功する() {
        storedToken(null);

        assertThat(service.validateToken(TOKEN, "203.0.113.9")).isNotNull();
    }

    @Test
    @DisplayName("許可リスト内の送信元は成功する")
    void 許可リスト内の送信元は成功する() {
        storedToken("[\"192.168.1.100\",\"10.0.0.0/24\"]");

        assertThat(service.validateToken(TOKEN, "192.168.1.100")).isNotNull();
        assertThat(service.validateToken(TOKEN, "10.0.0.42")).isNotNull();
    }

    @Test
    @DisplayName("許可リスト外の送信元は無効トークンと同じSIGNAGE_002で拒否する")
    void 許可リスト外の送信元はSIGNAGE_002で拒否する() {
        storedToken("[\"192.168.1.100\",\"10.0.0.0/24\"]");

        assertThatThrownBy(() -> service.validateToken(TOKEN, "203.0.113.9"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(SignageErrorCode.SIGNAGE_002);
    }

    @Test
    @DisplayName("IPv6の許可リストを照合できる")
    void IPv6の許可リストを照合できる() {
        storedToken("[\"2001:db8::/32\"]");

        assertThat(service.validateToken(TOKEN, "2001:db8::5")).isNotNull();
        assertThatThrownBy(() -> service.validateToken(TOKEN, "2001:db9::5"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(SignageErrorCode.SIGNAGE_002);
    }

    @Test
    @DisplayName("送信元IPが不明(null)なら許可リスト付きトークンは拒否する")
    void 送信元不明なら拒否する() {
        storedToken("[\"192.168.1.100\"]");

        assertThatThrownBy(() -> service.validateToken(TOKEN, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(SignageErrorCode.SIGNAGE_002);
    }

    @Test
    @DisplayName("発行時にIPでもCIDRでもない値を含むallowedIpsは保存せずSIGNAGE_010で拒否する")
    void 発行時に書式不正のallowedIpsはSIGNAGE_010で拒否する() {
        SignageScreenEntity screen = SignageScreenEntity.builder().scopeType("TEAM").scopeId(1L).name("s").build();
        given(screenRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(screen));

        assertThatThrownBy(() -> service.issueToken(1L, 1L,
                new IssueSignageTokenRequest("t", List.of("192.168.1.100", "office.example.com"), null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(SignageErrorCode.SIGNAGE_010);
        verify(tokenRepository, never()).save(any());
    }
}
