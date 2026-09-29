package com.github.guoyyit.corporateactionsjava.dto;

import com.github.guoyyit.corporateactionsjava.domain.ActionParams;

import java.time.LocalDate;

public record ActionResponse(
        String id,
        String symbol,
        LocalDate effectiveDate,
        String type,
        ActionParams params
) {
}
