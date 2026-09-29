package com.github.guoyyit.corporateactionsjava.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RawPriceRequest(String symbol,
                              LocalDate date,
                              BigDecimal close) {
}
