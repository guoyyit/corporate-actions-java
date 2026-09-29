package com.github.guoyyit.corporateactionsjava.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;

public record EventRequest(String eventId,
                           String symbol,
                           LocalDate effectiveDate,
                           String type,
                           JsonNode params) {
}
