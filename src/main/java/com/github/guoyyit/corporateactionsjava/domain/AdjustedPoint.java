package com.github.guoyyit.corporateactionsjava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AdjustedPoint(
        LocalDate date,
        BigDecimal raw,
        BigDecimal forward,
        BigDecimal backward,
        BigDecimal factor
) {
}
