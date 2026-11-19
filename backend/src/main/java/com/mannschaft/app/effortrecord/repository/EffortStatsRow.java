package com.mannschaft.app.effortrecord.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** F06.4.1 集計用の最小射影（snapshot・private note を読まない）。 */
public record EffortStatsRow(LocalDate recordedOn, Instant createdAt, UUID id, Integer definitionRevision,
                             String valuesJson) {
}
