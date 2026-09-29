package com.github.guoyyit.corporateactionsjava.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.github.guoyyit.corporateactionsjava.core.error.ApiException;
import com.github.guoyyit.corporateactionsjava.core.error.ErrorCode;

public record MergeParams(int from, int to) implements EventParams {

    @Override
    public EventType type() {
        return EventType.MERGE;
    }

    @Override
    public void validate() {
        if (from <= 0 || to <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "merge requires positive from/to share counts, got " + from + " -> " + to);
        }
        if (from <= to) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "merge requires from > to (more old shares merged into fewer new shares), got "
                            + from + " -> " + to);
        }
    }

    @Override
    public BigDecimal multiplier(BigDecimal rawCloseBefore) {
        return BigDecimal.valueOf(from).divide(BigDecimal.valueOf(to),
                SplitParams.FACTOR_SCALE, RoundingMode.HALF_UP);
    }
}
