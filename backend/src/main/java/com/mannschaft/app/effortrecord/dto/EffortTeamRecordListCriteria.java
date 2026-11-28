package com.mannschaft.app.effortrecord.dto;

import java.time.LocalDate;
import java.util.UUID;

/** F06.4.1 チーム一覧の条件（API 契約 §1・§4。user_id は任意）。 */
public record EffortTeamRecordListCriteria(
        LocalDate dateFrom,
        LocalDate dateTo,
        UUID definitionUuid,
        Long userId,
        String cursor,
        int limit) {
}
