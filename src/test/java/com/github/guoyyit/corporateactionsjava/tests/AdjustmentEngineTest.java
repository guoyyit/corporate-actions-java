package com.github.guoyyit.corporateactionsjava.tests;

import com.github.guoyyit.corporateactionsjava.domain.ActionParams;
import com.github.guoyyit.corporateactionsjava.domain.AdjustedPoint;
import com.github.guoyyit.corporateactionsjava.domain.CorporateAction;
import com.github.guoyyit.corporateactionsjava.domain.RawPrice;
import com.github.guoyyit.corporateactionsjava.service.AdjustmentEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdjustmentEngineTest {

    private final AdjustmentEngine engine = new AdjustmentEngine();
    private final BigDecimal tick = new BigDecimal("0.01");

    private RawPrice price(String date, String close) {
        return new RawPrice("AAA", LocalDate.parse(date), new BigDecimal(close));
    }

    @Test
    void splitTwoForOneProducesExpectedFactors() {
        List<RawPrice> prices = List.of(
                price("2024-01-02", "100"),
                price("2024-01-03", "60"),
                price("2024-01-04", "62"));
        CorporateAction split = new CorporateAction(
                "a1", "AAA", LocalDate.parse("2024-01-03"),
                new ActionParams.SplitParams(new BigDecimal("2")));

        List<AdjustedPoint> points =
                engine.compute("AAA", prices, List.of(split), tick);

        assertEquals(0, new BigDecimal("1").compareTo(points.get(0).factor()));
        assertEquals(0, new BigDecimal("2").compareTo(points.get(1).factor()));
        assertEquals(0, new BigDecimal("2").compareTo(points.get(2).factor()));

        // 后复权：100、60*2=120、62*2=124
        assertEquals("100", points.get(0).backward().toPlainString());
        assertEquals("120", points.get(1).backward().toPlainString());
        assertEquals("124", points.get(2).backward().toPlainString());
        // 前复权以最新因子为锚：100/2=50，之后保持原始价
        assertEquals("50", points.get(0).forward().toPlainString());
        assertEquals("60", points.get(1).forward().toPlainString());
        assertEquals("62", points.get(2).forward().toPlainString());
    }

    @Test
    void mergeShrinksFactor() {
        List<RawPrice> prices = List.of(
                price("2024-01-02", "10"),
                price("2024-01-03", "90"));
        CorporateAction merge = new CorporateAction(
                "m1", "AAA", LocalDate.parse("2024-01-03"),
                new ActionParams.MergeParams(new BigDecimal("0.1")));

        List<AdjustedPoint> points =
                engine.compute("AAA", prices, List.of(merge), tick);

        // 10 合 1：后复权因子 0.1，后复权价 90*0.1=9
        assertEquals("9", points.get(1).backward().toPlainString());
        // 前复权锚 0.1：10*1/0.1=100
        assertEquals("100", points.get(0).forward().toPlainString());
    }

    @Test
    void dividendAdjustsByPreviousClose() {
        List<RawPrice> prices = List.of(
                price("2024-01-02", "100"),
                price("2024-01-03", "95"));
        CorporateAction dividend = new CorporateAction(
                "d1", "AAA", LocalDate.parse("2024-01-03"),
                new ActionParams.DividendParams(new BigDecimal("10")));

        List<AdjustedPoint> points =
                engine.compute("AAA", prices, List.of(dividend), tick);

        // 日因子 = (100-10)/100 = 0.9，后复权价 95*0.9 = 85.5
        assertEquals(0, new BigDecimal("0.9").compareTo(points.get(1).factor()));
        assertEquals("85.5", points.get(1).backward().toPlainString());
        // 前复权：100 / 0.9 ≈ 111.11（最小单位 0.01）
        assertEquals("111.11", points.get(0).forward().toPlainString());
    }

    @Test
    void adjustedPricesObeyViewRelationsWithinOneTick() {
        List<RawPrice> prices = List.of(
                price("2024-01-02", "100"),
                price("2024-01-03", "95"),
                price("2024-01-04", "60"),
                price("2024-01-05", "61"));
        List<CorporateAction> actions = List.of(
                new CorporateAction("d1", "AAA", LocalDate.parse("2024-01-03"),
                        new ActionParams.DividendParams(new BigDecimal("10"))),
                new CorporateAction("s1", "AAA", LocalDate.parse("2024-01-04"),
                        new ActionParams.SplitParams(new BigDecimal("2"))));

        List<AdjustedPoint> points = engine.compute("AAA", prices, actions, tick);

        for (AdjustedPoint point : points) {
            // 后复权 ≈ 原始 * 因子（误差不超过一个最小价格单位）
            BigDecimal backwardExpected = point.raw().multiply(point.factor());
            assertTrue(
                    point.backward().subtract(backwardExpected).abs().compareTo(tick) <= 0,
                    "后复权换算超差: " + point);
            // 前复权与后复权成固定比例（最后一天锚点），换算误差不超过一个 tick
            BigDecimal latestFactor = points.get(points.size() - 1).factor();
            BigDecimal forwardExpected =
                    point.backward().divide(latestFactor, 10, java.math.RoundingMode.HALF_UP);
            assertTrue(
                    point.forward().subtract(forwardExpected).abs().compareTo(tick) <= 0,
                    "前复权换算超差: " + point);
        }
    }

    @Test
    void eventBeforeFirstPriceBarIsIgnoredByAnchor() {
        List<RawPrice> prices = List.of(
                price("2024-02-01", "100"),
                price("2024-02-02", "101"));
        CorporateAction ancientSplit = new CorporateAction(
                "old", "AAA", LocalDate.parse("2020-01-01"),
                new ActionParams.SplitParams(new BigDecimal("10")));

        List<AdjustedPoint> points =
                engine.compute("AAA", prices, List.of(ancientSplit), tick);

        assertEquals(0, BigDecimal.ONE.compareTo(points.get(0).factor()));
        assertEquals(0, BigDecimal.ONE.compareTo(points.get(1).factor()));
        assertEquals("100", points.get(0).backward().toPlainString());
    }
}
