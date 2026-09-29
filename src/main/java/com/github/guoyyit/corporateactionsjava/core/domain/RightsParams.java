package com.github.guoyyit.corporateactionsjava.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.github.guoyyit.corporateactionsjava.core.error.ApiException;
import com.github.guoyyit.corporateactionsjava.core.error.ErrorCode;

public record RightsParams(BigDecimal subscriptionPrice,
                           BigDecimal ratio) implements EventParams {

    @Override
    public EventType type() {
        return EventType.RIGHTS;
    }

    @Override
    public void validate() {
        if (subscriptionPrice == null || subscriptionPrice.signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "rights requires a positive subscriptionPrice, got " + subscriptionPrice);
        }
        if (ratio == null || ratio.signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "rights requires a positive rights ratio (new shares per old share), got " + ratio);
        }
    }

    @Override
    public BigDecimal multiplier(BigDecimal rawCloseBefore) {
        if (rawCloseBefore == null || rawCloseBefore.signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "raw close price before the rights ex-date is required to compute the factor");
        }
        if (subscriptionPrice.compareTo(rawCloseBefore) >= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "rights subscriptionPrice " + subscriptionPrice
                            + " must be smaller than the close before ex-date " + rawCloseBefore);
        }
        BigDecimal theoreticalExPrice = rawCloseBefore.add(ratio.multiply(subscriptionPrice))
                .divide(BigDecimal.ONE.add(ratio), SplitParams.FACTOR_SCALE, RoundingMode.HALF_UP);
        return rawCloseBefore.divide(theoreticalExPrice, SplitParams.FACTOR_SCALE, RoundingMode.HALF_UP);
    }
}
