package com.mannschaft.app.support.test.scopecontent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture.Actor;
import com.mannschaft.app.support.test.scopecontent.ScopeContentMatrixTable.Route;
import com.mannschaft.app.support.test.scopecontent.ScopeContentMatrixTable.Viewer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * CMP-261124-0159: 親チーム上限の表駆動 IT ハーネス（{@code ScopeContentAccessMatrixIT} の基底。F00 §20.6）。
 *
 * <p>各ドメイン PR はパッケージ別のサブクラス（CI シャード分散のため 1 クラスを巨大化させない）を作り、
 * {@link #matrixFile()} で自パッケージの表 {@code scope_content_matrix/<package>.yaml} を返す。
 * 表の期待値は本戦役前の実挙動のスナップショットから作る（試練の作法: まず origin/main で権限あり側を記録し、
 * 次に権限なし側＝部外者と同一応答の red を書く）。</p>
 *
 * <ul>
 *   <li><b>フィクスチャ</b>: {@link ScopeContentFixture}（非公開チームと閲覧者 8 区分）。</li>
 *   <li><b>比較器</b>: {@link #sameAsOutsider} — status・{@code error.code}・{@code error.message} の完全一致。</li>
 *   <li><b>スナップショット記録器</b>: 実行した全応答の要約を {@code build/scope-content-matrix/<package>.tsv} へ書く
 *       （権限あり側の期待値づくりと、上限の追加で変わったのが権限なし側だけであることの差分確認に使う）。</li>
 * </ul>
 */
public abstract class AbstractScopeContentAccessMatrixIT extends AbstractMySqlIntegrationTest {

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext protected EntityManager em;

    protected ScopeContentFixture fixture;
    private final List<String> snapshot = new ArrayList<>();

    /** 自パッケージの表（{@code src/test/resources/scope_content_matrix/<package>.yaml}）。 */
    protected abstract Path matrixFile();

    /** 表の {@code params} で {@code @} から始まる値を、派生クラスが作ったデータの ID 等へ解決する。 */
    protected String resolveParam(String name, String value) {
        throw new IllegalArgumentException("未解決のパラメータ: " + name + "=" + value);
    }

    /** フィクスチャ作成後に、派生クラスが経路の対象データ（スレッド・ファイル等）を作るフック。 */
    protected void seed(ScopeContentFixture fixture) {
    }

    @BeforeEach
    void createFixture() {
        fixture = new TransactionTemplate(transactionManager).execute(t -> {
            ScopeContentFixture created = ScopeContentFixture.create(em);
            seed(created);
            return created;
        });
    }

    @AfterEach
    void deleteFixtureAndWriteSnapshot() {
        if (fixture != null) {
            new TransactionTemplate(transactionManager).executeWithoutResult(t -> fixture.delete(em));
        }
        writeSnapshot();
    }

    @TestFactory
    Stream<DynamicTest> 非公開チームの閲覧者8区分() {
        ScopeContentMatrixTable table = ScopeContentMatrixTable.load(matrixFile());
        List<DynamicTest> tests = new ArrayList<>();
        for (Route route : table.routes()) {
            for (Viewer viewer : Viewer.values()) {
                tests.add(DynamicTest.dynamicTest(route.id() + " " + viewer + " " + route.expectationOf(viewer),
                        () -> verify(table.packageName(), route, viewer)));
            }
        }
        return tests.stream();
    }

    private void verify(String pkg, Route route, Viewer viewer) throws Exception {
        Response actual = perform(route, Actor.valueOf(viewer.name()));
        Response outsider = viewer == Viewer.OUTSIDER ? actual : perform(route, Actor.OUTSIDER);
        snapshot.add(String.join("\t", pkg, route.id(), viewer.name(), String.valueOf(actual.status()),
                String.valueOf(actual.code())));
        String expectation = route.expectationOf(viewer);
        if (expectation.equals("ALLOW")) {
            assertThat(actual.status()).as("%s %s は本戦役前どおり 2xx", route.id(), viewer).isBetween(200, 299);
        } else if (expectation.startsWith("STATUS:")) {
            assertThat(actual.status()).as("%s %s", route.id(), viewer)
                    .isEqualTo(Integer.parseInt(expectation.substring("STATUS:".length())));
        } else {
            // 部外者の応答そのものが 200 だと code・message が両方 null で一致し、認可の全面欠落を見逃すため、先に独立に拒否を確かめる
            assertThat(isRejection(outsider))
                    .as("%s 部外者の応答は拒否（存在秘匿の 404 か契約上の 403 で error.code あり）であること: outsider=%s", route.id(), outsider)
                    .isTrue();
            assertThat(sameAsOutsider(actual, outsider))
                    .as("%s %s は部外者と同一応答であること: actual=%s outsider=%s", route.id(), viewer, actual, outsider)
                    .isTrue();
        }
    }

    /** 拒否応答（403 または 404、かつ error.code を持つ）。部外者の応答が 200 のような成功応答でないことの確認に使う。 */
    public static boolean isRejection(Response response) {
        return (response.status() == 403 || response.status() == 404) && response.code() != null;
    }

    /** 部外者と同一応答（status・error.code・error.message の完全一致）。 */
    public static boolean sameAsOutsider(Response actual, Response outsider) {
        return actual.status() == outsider.status()
                && java.util.Objects.equals(actual.code(), outsider.code())
                && java.util.Objects.equals(actual.message(), outsider.message());
    }

    /** 応答の要約。 */
    public record Response(int status, String code, String message) { }

    protected Response perform(Route route, Actor actor) throws Exception {
        String path = route.path()
                .replace("{teamId}", String.valueOf(fixture.teamId))
                .replace("{id}", String.valueOf(fixture.teamId))
                .replace("{slug}", fixture.teamSlug)
                .replace("{orgId}", String.valueOf(fixture.parentOrgId));
        for (Map.Entry<String, String> p : route.params().entrySet()) {
            String value = p.getValue().startsWith("@") ? resolveParam(p.getKey(), p.getValue()) : p.getValue();
            path = path.replace("{" + p.getKey() + "}", value);
        }
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(route.method()), path);
        if (actor != null) {
            builder = builder.with(user(String.valueOf(fixture.user(actor))));
        }
        MvcResult result = mvc.perform(builder).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String code = null;
        String message = null;
        if (!body.isBlank() && body.trim().startsWith("{")) {
            JsonNode error = objectMapper.readTree(body).path("error");
            code = error.path("code").isMissingNode() ? null : error.path("code").asText();
            message = error.path("message").isMissingNode() ? null : error.path("message").asText();
        }
        return new Response(result.getResponse().getStatus(), code, message);
    }

    private void writeSnapshot() {
        if (snapshot.isEmpty()) {
            return;
        }
        Path out = Paths.get("build", "scope-content-matrix", getClass().getSimpleName() + ".tsv");
        try {
            Files.createDirectories(out.getParent());
            Files.write(out, snapshot, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
