package com.github.guoyyit.corporateactionsjava.web.dto;

import com.github.guoyyit.corporateactionsjava.core.domain.PricePoint;

import java.util.List;

public record PriceQueryResponse(String symbol,
                                 String basis,
                                 List<PricePoint> prices) {
}
