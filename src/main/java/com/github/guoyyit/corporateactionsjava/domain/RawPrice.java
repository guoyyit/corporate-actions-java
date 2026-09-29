package com.github.guoyyit.corporateactionsjava.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RawPrice(
        String symbol,
        LocalDate date,
        BigDecimal close
) {
}
