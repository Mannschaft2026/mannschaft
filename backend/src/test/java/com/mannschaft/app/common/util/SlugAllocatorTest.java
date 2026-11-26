package com.mannschaft.app.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.TeamErrorCode;
import java.sql.SQLIntegrityConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** CMP-261021-0838: {@link SlugAllocator} の採番枯渇・一意制約違反判定。 */
@DisplayName("SlugAllocator")
class SlugAllocatorTest {

    @Test
    void 候補が全て使用中なら指定した専用エラーコードの業務例外_すでに使われています_ではない() {
        Throwable thrown = catchThrowable(() -> SlugAllocator.allocate("my-club", s -> true, TeamErrorCode.TEAM_073));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(TeamErrorCode.TEAM_073);
    }

    @Test
    void 基底が使用中なら連番で採番される() {
        String slug = SlugAllocator.allocate("my-club", s -> s.equals("my-club"), TeamErrorCode.TEAM_073);

        assertThat(slug).isEqualTo("my-club-1");
    }

    @Test
    void 一意制約違反判定_MySQL1062かつ索引名一致なら真() {
        Throwable e = new DataIntegrityViolationException("x", new SQLIntegrityConstraintViolationException(
                "Duplicate entry 'a' for key 'teams.uq_teams_slug'", "23000", 1062));

        assertThat(SlugAllocator.isUniqueViolation(e, "uq_teams_slug")).isTrue();
        assertThat(SlugAllocator.isUniqueViolation(e, "uq_organizations_slug")).isFalse();
    }

    @Test
    void 一意制約違反判定_SQL例外でない例外メッセージに索引名があるだけでは偽() {
        Throwable e = new IllegalStateException("uq_teams_slug という文字列を含むだけ");

        assertThat(SlugAllocator.isUniqueViolation(e, "uq_teams_slug")).isFalse();
    }

    @Test
    void 一意制約違反判定_1062以外のSQL例外は偽() {
        Throwable e = new SQLIntegrityConstraintViolationException("uq_teams_slug", "23000", 1452);

        assertThat(SlugAllocator.isUniqueViolation(e, "uq_teams_slug")).isFalse();
    }
}
