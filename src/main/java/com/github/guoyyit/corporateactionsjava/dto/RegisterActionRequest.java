package com.github.guoyyit.corporateactionsjava.dto;

import com.github.guoyyit.corporateactionsjava.domain.ActionParams;

import java.time.LocalDate;

public record RegisterActionRequest(
        String symbol,
        LocalDate effectiveDate,
        ActionParams params
) {
}
