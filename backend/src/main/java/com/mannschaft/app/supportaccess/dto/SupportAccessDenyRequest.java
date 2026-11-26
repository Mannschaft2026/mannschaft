package com.mannschaft.app.supportaccess.dto;

import jakarta.validation.constraints.Size;

/** F10.9 本人の拒否（コメントは任意）。 */
public record SupportAccessDenyRequest(@Size(max = 500) String comment) {
}
