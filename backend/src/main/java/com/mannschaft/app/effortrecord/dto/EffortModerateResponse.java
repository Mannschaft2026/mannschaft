package com.mannschaft.app.effortrecord.dto;

import java.util.UUID;

/** F06.4.1 管理者非表示の応答（API 契約 §4: {id, version, state:'MODERATED'}）。 */
public record EffortModerateResponse(UUID id, long version, String state) {
}
