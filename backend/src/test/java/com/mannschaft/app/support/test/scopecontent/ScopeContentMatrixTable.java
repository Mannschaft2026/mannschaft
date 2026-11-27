package com.mannschaft.app.support.test.scopecontent;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CMP-261124-0159: 親チーム上限の表駆動 IT（{@code ScopeContentAccessMatrixIT}）の表
 * {@code src/test/resources/scope_content_matrix/<package>.yaml} の読み込み（F00 §20.6）。
 *
 * <pre>{@code
 * package: bulletin
 * routes:
 *   - id: R-bulletin-3          # 経路台帳 routes.tsv の補助 ID
 *     method: GET
 *     path: /api/v1/teams/{teamId}/bulletin/threads   # {teamId}/{slug}/{orgId} はハーネスが埋める
 *     params: { threadId: "@thread" }                 # 任意。@ で始まる値は派生クラスの resolveParam に委ねる
 *     expect:                                          # 閲覧者 8 区分 → ALLOW / OUTSIDER / STATUS:nnn
 *       ADMIN: ALLOW
 *       SUPPORTER: OUTSIDER
 * }</pre>
 *
 * <ul>
 *   <li>{@code ALLOW}: 本戦役前に 200 だった閲覧者は 200 のまま（2xx）。</li>
 *   <li>{@code OUTSIDER}: 部外者（ログイン済み非所属）と status・{@code error.code}・{@code error.message} まで完全一致。</li>
 *   <li>{@code STATUS:nnn}: 既存の追加認可で拒否されていたもの（例: 管理者専用経路の MEMBER）は拒否のまま。</li>
 * </ul>
 * 期待値を書かなかった閲覧者は {@code OUTSIDER}（権限なし側の既定）とする。
 */
public record ScopeContentMatrixTable(String packageName, List<Route> routes) {

    /** 閲覧者 8 区分（F00 §20.6 の権限あり 3・権限なし 5）。 */
    public enum Viewer {
        ADMIN, DEPUTY_ADMIN, MEMBER, SUPPORTER, GUEST, OUTSIDER, OTHER_TEAM_MEMBER, OTHER_ORG_MEMBER
    }

    /** 1 経路の期待値。 */
    public record Route(String id, String method, String path, Map<String, String> params,
                        Map<Viewer, String> expect) {

        public String expectationOf(Viewer viewer) {
            return expect.getOrDefault(viewer, "OUTSIDER");
        }
    }

    public Set<String> ids() {
        Set<String> ids = new LinkedHashSet<>();
        routes.forEach(r -> ids.add(r.id()));
        return ids;
    }

    @SuppressWarnings("unchecked")
    public static ScopeContentMatrixTable load(Path file) {
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = new Yaml().load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (root == null) {
            throw new IllegalStateException("Matrix 表が空: " + file);
        }
        String pkg = String.valueOf(root.get("package"));
        List<Route> routes = new ArrayList<>();
        Object rawRoutes = root.get("routes");
        if (rawRoutes instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> r = (Map<String, Object>) item;
                Map<Viewer, String> expect = new EnumMap<>(Viewer.class);
                Object rawExpect = r.get("expect");
                if (rawExpect instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> expect.put(Viewer.valueOf(String.valueOf(k)), validate(String.valueOf(v))));
                }
                Map<String, String> params = new LinkedHashMap<>();
                Object rawParams = r.get("params");
                if (rawParams instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> params.put(String.valueOf(k), String.valueOf(v)));
                }
                routes.add(new Route(String.valueOf(r.get("id")),
                        String.valueOf(r.getOrDefault("method", "GET")), String.valueOf(r.get("path")), params, expect));
            }
        }
        return new ScopeContentMatrixTable(pkg, routes);
    }

    private static String validate(String expectation) {
        if (expectation.equals("ALLOW") || expectation.equals("OUTSIDER") || expectation.matches("STATUS:\\d{3}")) {
            return expectation;
        }
        throw new IllegalArgumentException("期待値は ALLOW / OUTSIDER / STATUS:nnn のいずれか: " + expectation);
    }
}
