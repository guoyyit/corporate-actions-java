package com.github.guoyyit.corporateactionsjava.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PricePointRequest(
        LocalDate date,
        BigDecimal close
) {
}
