package com.mannschaft.app.common.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * CMP-261119-1855 試練: 共有コンテキストで users の id が再利用されても、前テストの {@code UserLocaleCache} が
 * 次のテストへ持ち越されないことを決定的に固定する。
 *
 * <p>実際の再利用経路は、別構成のコンテキスト起動（{@code ddl-auto=create}）が共有 MySQL のスキーマを作り直し、
 * users の AUTO_INCREMENT が 1 に戻ること。ここでは同じ状態を「行を消して同じ id で作り直す」ことで再現する。
 * 1 本目が locale=ja の利用者をキャッシュへ載せて行を消し、2 本目（基底クラスの {@code @BeforeEach} を挟む）が
 * 同じ id で locale=en の利用者を作って早期拒否（AccessDeniedHandler の 403）が英語になることを確かめる。
 * 基底クラスがキャッシュを破棄しなければ 2 本目は ja のまま red になる。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("CMP-261119-1855 id 再利用でも users.locale キャッシュが持ち越されない")
class UserLocaleCacheIdReuseIT extends AbstractMySqlIntegrationTest {

    private static final Pattern JAPANESE = Pattern.compile("[\u3040-\u30FF\u3400-\u4DBF\u4E00-\u9FFF\uFF66-\uFF9F]");
    private static final String ADMIN_ONLY_URL = "/api/v1/admin/users/1";

    /** 1 本目が使った id（2 本目が同じ id で作り直す）。 */
    private static Long reusedId;
    private static Long createdAgainId;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    @Test
    @Order(1)
    @DisplayName("前提: locale=ja の利用者でリクエストして users.locale キャッシュに ja を載せ、その行を消す")
    void ja利用者をキャッシュに載せて行を消す() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        reusedId = tx.execute(s -> persistUser("ja").getId());

        MvcResult result = mockMvc.perform(get(ADMIN_ONLY_URL)
                .with(authentication(auth(reusedId))).header("Accept-Language", "en")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(JAPANESE.matcher(message(result)).find()).as("ja 利用者は日本語").isTrue();

        tx.executeWithoutResult(s -> em.createNativeQuery("DELETE FROM users WHERE id = :id")
                .setParameter("id", reusedId).executeUpdate());
    }

    @Test
    @Order(2)
    @DisplayName("同じ id で locale=en の利用者を作ると、早期拒否の 403 は前テストの ja を引きずらず英語になる")
    void 同じidのen利用者は英語() throws Exception {
        assertThat(reusedId).as("1 本目が先に走っていること").isNotNull();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        createdAgainId = tx.execute(s -> {
            Long generated = persistUser("en").getId();
            em.createNativeQuery("UPDATE users SET id = :to WHERE id = :from")
                    .setParameter("to", reusedId).setParameter("from", generated).executeUpdate();
            return reusedId;
        });

        MvcResult result = mockMvc.perform(get(ADMIN_ONLY_URL)
                .with(authentication(auth(createdAgainId))).header("Accept-Language", "ja")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(message(result)).isNotBlank().doesNotContainPattern(JAPANESE);
    }

    @AfterEach
    void cleanUp() {
        if (createdAgainId != null) {
            Long id = createdAgainId;
            createdAgainId = null;
            new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                    em.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", id).executeUpdate());
        }
    }

    private UserEntity persistUser(String locale) {
        UserEntity user = UserEntity.builder().email("cmp1855-" + UUID.randomUUID() + "@example.com")
                .passwordHash("{noop}unused").lastName("試練").firstName("id再利用")
                .displayName("id再利用試練").status(UserEntity.UserStatus.ACTIVE).isSearchable(false)
                .locale(locale).timezone("Asia/Tokyo").build();
        em.persist(user);
        em.flush();
        return user;
    }

    private static UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(String.valueOf(userId), null, List.of());
    }

    private String message(MvcResult result) throws Exception {
        String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode error = objectMapper.readTree(raw).path("error");
        return error.path("message").asText();
    }
}
