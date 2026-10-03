package com.github.guoyyit.corporateactionsjava.tests;

import com.github.guoyyit.corporateactionsjava.domain.ActionParams;
import com.github.guoyyit.corporateactionsjava.domain.AdjustedPoint;
import com.github.guoyyit.corporateactionsjava.domain.CorporateAction;
import com.github.guoyyit.corporateactionsjava.domain.RawPrice;
import com.github.guoyyit.corporateactionsjava.error.AdjustmentException;
import com.github.guoyyit.corporateactionsjava.service.AdjustmentEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        // 除息参考价 = 100 - 10 = 90；日因子 = 100/90 ≈ 1.111111111111（>1，把除息后价格调回除息前水平）
        assertEquals(0, new BigDecimal("1.111111111111").compareTo(points.get(1).factor()));
        // 后复权：除息日前保持原价 100；除息日 95 * 100/90 ≈ 105.56
        assertEquals("100", points.get(0).backward().toPlainString());
        assertEquals("105.56", points.get(1).backward().toPlainString());
        // 前复权：除息日前一日下压至 100 * 90/100 = 90，除息日保持原始价 95
        assertEquals("90", points.get(0).forward().toPlainString());
        assertEquals("95", points.get(1).forward().toPlainString());
        // 前复权历史价格不得高于对应未复权价
        assertTrue(points.get(0).forward().compareTo(points.get(0).raw()) <= 0);
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

    @Test
    void dividendCanonicalExampleIsContinuousAcrossExDate() {
        // 前收盘 10.00，每股分红 1.00，除息日收盘 9.00（恰为除息参考价）
        List<RawPrice> prices = List.of(
                price("2024-03-04", "10.00"),
                price("2024-03-05", "9.00"));
        CorporateAction dividend = new CorporateAction(
                "d1", "AAA", LocalDate.parse("2024-03-05"),
                new ActionParams.DividendParams(new BigDecimal("1.00")));

        List<AdjustedPoint> points =
                engine.compute("AAA", prices, List.of(dividend), tick);

        // 未复权保持原始价格
        assertEquals("9", points.get(1).raw().stripTrailingZeros().toPlainString());
        // 后复权：除息日价格回到前收盘 10.00，与前一日完全连续
        assertEquals("10", points.get(0).backward().toPlainString());
        assertEquals("10", points.get(1).backward().toPlainString());
        // 前复权：除息日前一日下压至 9.00，与除息日未复权价一致
        assertEquals("9", points.get(0).forward().toPlainString());
        assertEquals("9", points.get(1).forward().toPlainString());
        // 区间价差：复权价差（0）不得超过未复权价差（1.00）
        BigDecimal rawGap = points.get(1).raw().subtract(points.get(0).raw()).abs();
        assertTrue(points.get(1).backward().subtract(points.get(0).backward()).abs()
                .compareTo(rawGap) <= 0);
        assertTrue(points.get(1).forward().subtract(points.get(0).forward()).abs()
                .compareTo(rawGap) <= 0);
    }

    @Test
    void rightsIssueCanonicalExampleIsContinuousAcrossExDate() {
        // 前收盘 10.00，每 10 股配 3 股，配股价 6.00；
        // 除权参考价 = (10 + 0.3*6) / 1.3 = 9.0769... ≈ 9.08（按最小价格单位四舍五入）
        List<RawPrice> prices = List.of(
                price("2024-04-08", "10.00"),
                price("2024-04-09", "9.08"));
        CorporateAction rights = new CorporateAction(
                "r1", "AAA", LocalDate.parse("2024-04-09"),
                new ActionParams.RightsIssueParams(
                        new BigDecimal("0.3"), new BigDecimal("6.00")));

        List<AdjustedPoint> points =
                engine.compute("AAA", prices, List.of(rights), tick);

        // 后复权：除权日价格回到前收盘 10.00（误差不超过一个最小价格单位）
        assertEquals("10", points.get(0).backward().toPlainString());
        assertTrue(points.get(1).backward().subtract(new BigDecimal("10.00")).abs()
                        .compareTo(tick) <= 0,
                "后复权除权日价格应回到前收盘水平: " + points.get(1).backward());
        // 前复权：除权日前一日等于除权参考价 9.08（误差不超过一个最小价格单位）
        assertTrue(points.get(0).forward().subtract(new BigDecimal("9.08")).abs()
                        .compareTo(tick) <= 0,
                "前复权除权日前一日应等于除权参考价: " + points.get(0).forward());
        assertEquals("9.08", points.get(1).forward().toPlainString());
        // 区间价差：复权价差不超过一个 tick，且不超过未复权价差 0.92
        BigDecimal rawGap = points.get(1).raw().subtract(points.get(0).raw()).abs();
        BigDecimal backwardGap =
                points.get(1).backward().subtract(points.get(0).backward()).abs();
        BigDecimal forwardGap =
                points.get(1).forward().subtract(points.get(0).forward()).abs();
        assertTrue(backwardGap.compareTo(tick) <= 0);
        assertTrue(forwardGap.compareTo(tick) <= 0);
        assertTrue(backwardGap.compareTo(rawGap) <= 0);
        assertTrue(forwardGap.compareTo(rawGap) <= 0);
    }

    @Test
    void multipleDividendAndRightsEventsEachStayContinuous() {
        // 三个事件按生效日叠加，每个除权除息区间都必须连续
        List<RawPrice> prices = List.of(
                price("2024-05-06", "10.00"),
                price("2024-05-07", "9.00"),   // 分红 1.00，除息参考价 9.00
                price("2024-05-08", "8.31"),   // 配股 0.3@6.00，参考价 (9+1.8)/1.3 ≈ 8.31
                price("2024-05-09", "7.81"),   // 分红 0.50，除息参考价 8.31-0.50 = 7.81
                price("2024-05-10", "7.50"));
        List<CorporateAction> actions = List.of(
                new CorporateAction("d1", "AAA", LocalDate.parse("2024-05-07"),
                        new ActionParams.DividendParams(new BigDecimal("1.00"))),
                new CorporateAction("r1", "AAA", LocalDate.parse("2024-05-08"),
                        new ActionParams.RightsIssueParams(
                                new BigDecimal("0.3"), new BigDecimal("6.00"))),
                new CorporateAction("d2", "AAA", LocalDate.parse("2024-05-09"),
                        new ActionParams.DividendParams(new BigDecimal("0.50"))));

        List<AdjustedPoint> points = engine.compute("AAA", prices, actions, tick);

        // 每个除权除息区间：复权价差不超过一个 tick，且不超过未复权价差
        for (int index = 1; index <= 3; index++) {
            AdjustedPoint previous = points.get(index - 1);
            AdjustedPoint current = points.get(index);
            BigDecimal rawGap = current.raw().subtract(previous.raw()).abs();
            BigDecimal backwardGap =
                    current.backward().subtract(previous.backward()).abs();
            BigDecimal forwardGap =
                    current.forward().subtract(previous.forward()).abs();
            assertTrue(backwardGap.compareTo(tick) <= 0,
                    "后复权在区间 " + previous.date() + "->" + current.date()
                            + " 不连续: " + backwardGap);
            assertTrue(forwardGap.compareTo(tick) <= 0,
                    "前复权在区间 " + previous.date() + "->" + current.date()
                            + " 不连续: " + forwardGap);
            assertTrue(backwardGap.compareTo(rawGap) <= 0);
            assertTrue(forwardGap.compareTo(rawGap) <= 0);
        }

        // 任意交易日三口径换算关系：backward ≈ raw * factor，forward ≈ backward / 最新因子
        BigDecimal latestFactor = points.get(points.size() - 1).factor();
        for (AdjustedPoint point : points) {
            BigDecimal backwardExpected = point.raw().multiply(point.factor());
            assertTrue(point.backward().subtract(backwardExpected).abs()
                    .compareTo(tick) <= 0, "后复权换算超差: " + point);
            BigDecimal forwardExpected = point.backward()
                    .divide(latestFactor, 10, java.math.RoundingMode.HALF_UP);
            assertTrue(point.forward().subtract(forwardExpected).abs()
                    .compareTo(tick) <= 0, "前复权换算超差: " + point);
        }
    }

    @Test
    void missingPrecedingCloseRaisesDistinguishableError() {
        // 生效日当天就是序列首个交易日，之前没有任何收盘价
        List<RawPrice> prices = List.of(
                price("2024-06-03", "10.00"),
                price("2024-06-04", "9.00"));
        CorporateAction dividend = new CorporateAction(
                "d1", "AAA", LocalDate.parse("2024-06-03"),
                new ActionParams.DividendParams(new BigDecimal("1.00")));

        AdjustmentException ex = assertThrows(AdjustmentException.class,
                () -> engine.compute("AAA", prices, List.of(dividend), tick));
        assertEquals(AdjustmentException.CODE_MISSING_PRECEDING_CLOSE, ex.code());
    }

    @Test
    void dividendNotBelowPreviousCloseRaisesDistinguishableError() {
        List<RawPrice> prices = List.of(
                price("2024-06-03", "10.00"),
                price("2024-06-04", "9.00"));

        // 每股红利等于前收盘价
        CorporateAction equal = new CorporateAction(
                "d1", "AAA", LocalDate.parse("2024-06-04"),
                new ActionParams.DividendParams(new BigDecimal("10.00")));
        AdjustmentException exEqual = assertThrows(AdjustmentException.class,
                () -> engine.compute("AAA", prices, List.of(equal), tick));
        assertEquals(AdjustmentException.CODE_DIVIDEND_EXCEEDS_PREVIOUS_CLOSE,
                exEqual.code());

        // 每股红利大于前收盘价
        CorporateAction above = new CorporateAction(
                "d2", "AAA", LocalDate.parse("2024-06-04"),
                new ActionParams.DividendParams(new BigDecimal("12.00")));
        AdjustmentException exAbove = assertThrows(AdjustmentException.class,
                () -> engine.compute("AAA", prices, List.of(above), tick));
        assertEquals(AdjustmentException.CODE_DIVIDEND_EXCEEDS_PREVIOUS_CLOSE,
                exAbove.code());
    }
}
