package com.github.guoyyit.corporateactionsjava.web.dto;

import java.time.LocalDate;

public record EventResponse(String eventId,
                            String symbol,
                            LocalDate effectiveDate,
                            String type,
                            Object params) {
}
