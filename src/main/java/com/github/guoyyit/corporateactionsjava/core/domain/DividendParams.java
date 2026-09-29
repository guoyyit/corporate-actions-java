package com.github.guoyyit.corporateactionsjava.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.github.guoyyit.corporateactionsjava.core.error.ApiException;
import com.github.guoyyit.corporateactionsjava.core.error.ErrorCode;

public record DividendParams(BigDecimal dividendPerShare) implements EventParams {

    @Override
    public EventType type() {
        return EventType.DIVIDEND;
    }

    @Override
    public void validate() {
        if (dividendPerShare == null || dividendPerShare.signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "dividend requires a positive dividendPerShare, got " + dividendPerShare);
        }
    }

    @Override
    public BigDecimal multiplier(BigDecimal rawCloseBefore) {
        if (rawCloseBefore == null || rawCloseBefore.signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "raw close price before the dividend ex-date is required to compute the factor");
        }
        if (dividendPerShare.compareTo(rawCloseBefore) >= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "dividendPerShare " + dividendPerShare
                            + " must be smaller than the close before ex-date " + rawCloseBefore);
        }
        BigDecimal exPrice = rawCloseBefore.subtract(dividendPerShare);
        return rawCloseBefore.divide(exPrice, SplitParams.FACTOR_SCALE, RoundingMode.HALF_UP);
    }
}
