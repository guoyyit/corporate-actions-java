package com.github.guoyyit.corporateactionsjava.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.github.guoyyit.corporateactionsjava.core.error.ApiException;
import com.github.guoyyit.corporateactionsjava.core.error.ErrorCode;

public record SplitParams(int from, int to) implements EventParams {

    static final int FACTOR_SCALE = 10;

    @Override
    public EventType type() {
        return EventType.SPLIT;
    }

    @Override
    public void validate() {
        if (from <= 0 || to <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "split requires positive from/to share counts, got " + from + " -> " + to);
        }
        if (from == to) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "split requires different from/to share counts, got " + from + " -> " + to);
        }
    }

    @Override
    public BigDecimal multiplier(BigDecimal rawCloseBefore) {
        return BigDecimal.valueOf(to).divide(BigDecimal.valueOf(from), FACTOR_SCALE, RoundingMode.HALF_UP);
    }
}
