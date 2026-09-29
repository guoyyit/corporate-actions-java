package com.github.guoyyit.corporateactionsjava.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PricePoint(LocalDate date,
                         BigDecimal raw,
                         BigDecimal rawFactor,
                         BigDecimal backward,
                         BigDecimal backwardFactor,
                         BigDecimal forward,
                         BigDecimal forwardFactor) {
}
