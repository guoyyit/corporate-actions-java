package com.github.guoyyit.corporateactionsjava.core.domain;

import java.math.BigDecimal;

public sealed interface EventParams
        permits SplitParams, DividendParams, RightsParams, MergeParams {

    EventType type();

    void validate();

    BigDecimal multiplier(BigDecimal rawCloseBefore);
}
