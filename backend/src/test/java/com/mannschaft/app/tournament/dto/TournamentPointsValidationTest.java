package com.mannschaft.app.tournament.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261123-2131: 大会作成・更新リクエストの勝点・引分点・敗点の範囲検証。
 *
 * <p>DB 列は TINYINT UNSIGNED（0〜255）。範囲外を素通しすると保存時に DB エラー（500）になるため、
 * 入力検証で 400 の fieldErrors に揃える。</p>
 */
@DisplayName("大会リクエスト 勝点・引分点・敗点の範囲検証")
class TournamentPointsValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new ParameterNamesModule());

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    private static <T> Set<String> invalidFields(Class<T> type, String json) throws Exception {
        T req = MAPPER.readValue(json, type);
        return validator.validate(req).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("作成: 0〜255 は許容される")
    void create_inRange_OK() throws Exception {
        assertThat(invalidFields(CreateTournamentRequest.class,
                "{\"name\":\"大会\",\"format\":\"LEAGUE\",\"winPoints\":255,\"drawPoints\":0,\"lossPoints\":1}"))
                .isEmpty();
    }

    @Test
    @DisplayName("作成: 256 以上・負数は 3 項目とも 400 の対象になる")
    void create_outOfRange_rejected() throws Exception {
        assertThat(invalidFields(CreateTournamentRequest.class,
                "{\"name\":\"大会\",\"format\":\"LEAGUE\",\"winPoints\":300,\"drawPoints\":256,\"lossPoints\":-1}"))
                .containsExactlyInAnyOrder("winPoints", "drawPoints", "lossPoints");
    }

    @Test
    @DisplayName("作成: 未指定（null）は許容される（BE 既定値を使う）")
    void create_null_OK() throws Exception {
        assertThat(invalidFields(CreateTournamentRequest.class, "{\"name\":\"大会\",\"format\":\"LEAGUE\"}"))
                .isEmpty();
    }

    @Test
    @DisplayName("更新: 256 以上・負数は 3 項目とも 400 の対象になる")
    void update_outOfRange_rejected() throws Exception {
        assertThat(invalidFields(UpdateTournamentRequest.class,
                "{\"version\":1,\"winPoints\":300,\"drawPoints\":256,\"lossPoints\":-1}"))
                .containsExactlyInAnyOrder("winPoints", "drawPoints", "lossPoints");
    }

    @Test
    @DisplayName("更新: 0〜255 は許容される")
    void update_inRange_OK() throws Exception {
        assertThat(invalidFields(UpdateTournamentRequest.class,
                "{\"version\":1,\"winPoints\":255,\"drawPoints\":0,\"lossPoints\":1}"))
                .isEmpty();
    }
}
