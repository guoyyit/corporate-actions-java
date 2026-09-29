package com.github.guoyyit.corporateactionsjava.core.domain;

import java.time.LocalDate;

public record CorporateEvent(String eventId,
                             String symbol,
                             LocalDate effectiveDate,
                             EventType type,
                             EventParams params) {
}
