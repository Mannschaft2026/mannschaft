package com.mannschaft.app.effortrecord.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshot;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * F06.4.1 定義 snapshot と値 JSON の canonical 直列化・hash（データ設計 §2）。
 *
 * <p><b>canonical JSON</b>: DTO の宣言順・null 明示・空白なし・UTF-8 非エスケープの 1 通りの文字列。
 * {@code effort_definition_revisions.definition_hash} は本 Codec の {@link #hash(String)} と一致させる
 * （標準プリセットの seed は migration の SQL 側で同じ文字列の {@code SHA2(..., 256)} を取る。
 * 一致は {@code EffortPresetSeedCanonicalTest} が検査する）。</p>
 *
 * <p>MySQL の JSON 列は読み出し時にキー順・空白を正規化するため、保存済み JSON を hash の入力にしてはならない。
 * hash は必ず DTO から {@link #canonicalJson(EffortDefinitionSnapshot)} で組み直した文字列に対して取る。</p>
 */
@Component
public class EffortSnapshotCodec {

    private static final TypeReference<LinkedHashMap<String, String>> VALUES = new TypeReference<>() { };

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** DTO の canonical JSON。 */
    public String canonicalJson(EffortDefinitionSnapshot snapshot) {
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("定義 snapshot を直列化できません", e);
        }
    }

    /** 保存済み JSON（MySQL 正規化後でも可）を DTO に戻す。 */
    public EffortDefinitionSnapshot parseSnapshot(String json) {
        try {
            return mapper.readValue(json, EffortDefinitionSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("保存済みの定義 snapshot を読み込めません", e);
        }
    }

    /** SHA-256（小文字 16 進 64 文字）。 */
    public String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 定義 snapshot の canonical hash。 */
    public String hash(EffortDefinitionSnapshot snapshot) {
        return hash(canonicalJson(snapshot));
    }

    /** fieldKey → string/null の values JSON を Map に戻す。 */
    public Map<String, String> parseValues(String json) {
        try {
            return mapper.readValue(json, VALUES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("保存済みの値 JSON を読み込めません", e);
        }
    }

    /** values Map を JSON にする（null 値を保持する）。 */
    public String valuesJson(Map<String, String> values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("値を直列化できません", e);
        }
    }
}
