package com.github.guoyyit.corporateactionsjava.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.guoyyit.corporateactionsjava.core.domain.CorporateEvent;
import com.github.guoyyit.corporateactionsjava.core.domain.DividendParams;
import com.github.guoyyit.corporateactionsjava.core.domain.EventType;
import com.github.guoyyit.corporateactionsjava.core.domain.PricePoint;
import com.github.guoyyit.corporateactionsjava.core.domain.RawPrice;
import com.github.guoyyit.corporateactionsjava.core.domain.RightsParams;
import com.github.guoyyit.corporateactionsjava.core.domain.SplitParams;
import com.github.guoyyit.corporateactionsjava.core.engine.AdjustmentEngine;

class AdjustmentEngineTest {

    private static final String SYMBOL = "600000";

    private final AdjustmentEngine engine = new AdjustmentEngine();

    @Test
    void splitProducesCanonicalBackwardAndForwardPrices() {
        List<RawPrice> prices = prices(
                price("2024-01-02", "10.00"),
                price("2024-01-03", "5.00"));
        CorporateEvent split = event("e1", "2024-01-03", EventType.SPLIT,
                new SplitParams(1, 2));

        List<PricePoint> points = engine.compute(SYMBOL, prices, List.of(split));

        assertThat(points).hasSize(2);
        assertThat(points.get(0).raw()).isEqualByComparingTo("10.00");
        assertThat(points.get(0).backward()).isEqualByComparingTo("10.00");
        assertThat(points.get(0).forward()).isEqualByComparingTo("5.00");
        assertThat(points.get(1).raw()).isEqualByComparingTo("5.00");
        assertThat(points.get(1).backward()).isEqualByComparingTo("10.00");
        assertThat(points.get(1).forward()).isEqualByComparingTo("5.00");
        assertThat(points.get(0).backwardFactor()).isEqualByComparingTo("1.0000000000");
        assertThat(points.get(1).backwardFactor()).isEqualByComparingTo("2.0000000000");
        assertThat(points.get(0).forwardFactor()).isEqualByComparingTo("0.5000000000");
        assertThat(points.get(1).forwardFactor()).isEqualByComparingTo("1.0000000000");
    }

    @Test
    void dividendMultiplierKeepsTotalReturnContinuity() {
        List<RawPrice> prices = prices(
                price("2024-02-06", "10.00"),
                price("2024-02-07", "9.50"));
        CorporateEvent dividend = event("d1", "2024-02-07", EventType.DIVIDEND,
                new DividendParams(new BigDecimal("1.00")));

        List<PricePoint> points = engine.compute(SYMBOL, prices, List.of(dividend));

        assertThat(points.get(0).backward()).isEqualByComparingTo("10.00");
        assertThat(points.get(1).backward()).isEqualByComparingTo("10.56");
        assertThat(points.get(0).backwardFactor()).isEqualByComparingTo("1.0000000000");
        assertThat(points.get(0).forward()).isEqualByComparingTo("9.00");
        assertThat(points.get(1).backwardFactor())
                .isEqualByComparingTo(BigDecimal.TEN.divide(new BigDecimal("9"), 10,
                        java.math.RoundingMode.HALF_UP));
    }

    @Test
    void rightsIssueMultiplierReflectsPaidInCapital() {
        List<RawPrice> prices = prices(
                price("2024-03-10", "10.00"),
                price("2024-03-11", "9.00"));
        CorporateEvent rights = event("r1", "2024-03-11", EventType.RIGHTS,
                new RightsParams(new BigDecimal("5.00"), new BigDecimal("0.5")));

        List<PricePoint> points = engine.compute(SYMBOL, prices, List.of(rights));

        assertThat(points.get(0).backwardFactor()).isEqualByComparingTo("1.0000000000");
        assertThat(points.get(0).backward()).isEqualByComparingTo("10.00");
        assertThat(points.get(1).backwardFactor()).isEqualByComparingTo("1.2000000000");
        assertThat(points.get(1).backward()).isEqualByComparingTo("10.80");
        assertThat(points.get(0).forwardFactor()).isEqualByComparingTo("0.8333333333");
        assertThat(points.get(1).forwardFactor()).isEqualByComparingTo("1.0000000000");
    }

    @Test
    void unorderedRegistrationYieldsIdenticalResult() {
        List<RawPrice> prices = prices(
                price("2024-01-02", "10.00"),
                price("2024-01-03", "5.00"),
                price("2024-02-07", "9.50"),
                price("2024-03-11", "9.00"));
        CorporateEvent split = event("s1", "2024-01-03", EventType.SPLIT,
                new SplitParams(1, 2));
        CorporateEvent dividend = event("d1", "2024-02-07", EventType.DIVIDEND,
                new DividendParams(new BigDecimal("1.00")));
        CorporateEvent rights = event("r1", "2024-03-11", EventType.RIGHTS,
                new RightsParams(new BigDecimal("5.00"), new BigDecimal("0.5")));

        List<PricePoint> ordered = engine.compute(SYMBOL, prices, List.of(split, dividend, rights));
        List<CorporateEvent> shuffled = new ArrayList<>(List.of(rights, split, dividend));
        Collections.shuffle(shuffled, new java.util.Random(42));
        List<PricePoint> unordered = engine.compute(SYMBOL, prices, shuffled);

        assertThat(unordered).usingRecursiveComparison().isEqualTo(ordered);
    }

    @Test
    void threeBasesAlwaysSatisfyFactorRelations() {
        List<RawPrice> prices = prices(
                price("2024-01-02", "10.00"),
                price("2024-01-03", "5.00"),
                price("2024-02-07", "4.50"));
        List<CorporateEvent> events = List.of(
                event("s1", "2024-01-03", EventType.SPLIT, new SplitParams(1, 2)),
                event("d1", "2024-02-07", EventType.DIVIDEND,
                        new DividendParams(new BigDecimal("1.00"))));

        for (PricePoint point : engine.compute(SYMBOL, prices, events)) {
            assertThat(point.rawFactor()).isEqualByComparingTo("1");
            assertThat(point.backward())
                    .isEqualByComparingTo(point.raw().multiply(point.backwardFactor())
                            .setScale(2, java.math.RoundingMode.HALF_UP));
            assertThat(point.forward())
                    .isEqualByComparingTo(point.raw().multiply(point.forwardFactor())
                            .setScale(2, java.math.RoundingMode.HALF_UP));
            assertThat(point.backwardFactor().signum()).isPositive();
            assertThat(point.forwardFactor().signum()).isPositive();
        }
    }

    private RawPrice price(String date, String close) {
        return new RawPrice(SYMBOL, LocalDate.parse(date), new BigDecimal(close));
    }

    private CorporateEvent event(String id, String date, EventType type,
                                 com.github.guoyyit.corporateactionsjava.core.domain.EventParams params) {
        return new CorporateEvent(id, SYMBOL, LocalDate.parse(date), type, params);
    }

    private List<RawPrice> prices(RawPrice... values) {
        return List.of(values);
    }
}
