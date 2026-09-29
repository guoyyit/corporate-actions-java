package com.github.guoyyit.corporateactionsjava.domain;

import java.time.LocalDate;

public record CorporateAction(
        String id,
        String symbol,
        LocalDate effectiveDate,
        ActionParams params
) {
    public ActionType type() {
        return params.type();
    }
}
