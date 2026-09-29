package com.github.guoyyit.corporateactionsjava.dto;

import com.github.guoyyit.corporateactionsjava.domain.AdjustedPoint;
import com.github.guoyyit.corporateactionsjava.domain.PriceView;

import java.math.BigDecimal;
import java.util.List;

public record PriceSeriesResponse(
        String symbol,
        PriceView view,
        BigDecimal tick,
        BigDecimal anchorFactor,
        List<AdjustedPoint> points
) {
}
