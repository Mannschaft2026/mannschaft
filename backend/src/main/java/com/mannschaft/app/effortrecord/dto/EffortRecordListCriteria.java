package com.mannschaft.app.effortrecord.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * F06.4.1 個人一覧の検索条件（query date_from / date_to / definition_uuid / cursor / limit）。
 * date_from と date_to は対で指定。limit は 1〜100（既定 20）。
 */
public record EffortRecordListCriteria(
        LocalDate dateFrom,
        LocalDate dateTo,
        UUID definitionUuid,
        String cursor,
        int limit) {
}
