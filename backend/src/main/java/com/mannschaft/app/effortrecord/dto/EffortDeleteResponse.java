package com.mannschaft.app.effortrecord.dto;

import java.util.UUID;

/** F06.4.1 削除応答 {@code {id, version, state:"DELETED"}}（204 やフラグを混在させない）。 */
public record EffortDeleteResponse(UUID id, long version, String state) {
}
