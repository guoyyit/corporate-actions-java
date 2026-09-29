package com.github.guoyyit.corporateactionsjava.dto;

import java.util.List;

public record IngestPricesRequest(
        String symbol,
        List<PricePointRequest> points
) {
}
