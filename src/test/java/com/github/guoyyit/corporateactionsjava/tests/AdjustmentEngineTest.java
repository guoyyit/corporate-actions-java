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
        // 除权日收盘价恰好等于除权参考价 P-d 时，复权序列必须完全连续
        List<RawPrice> prices = List.of(
                price("2024-01-02", "100"),
                price("2024-01-03", "90"));
        CorporateAction dividend = new CorporateAction(
                "d1", "AAA", LocalDate.parse("2024-01-03"),
                new ActionParams.DividendParams(new BigDecimal("10")));

        List<AdjustedPoint> points =
                engine.compute("AAA", prices, List.of(dividend), tick);

        // 日因子 = P/(P-d) = 100/90 ≈ 1.1111（后复权因子在除权日抬升）
        assertEquals(0, new BigDecimal("1.1111").compareTo(
                points.get(1).factor().setScale(4, java.math.RoundingMode.HALF_UP)));
        // 后复权：除权日价 90 * (100/90) = 100，与前一日完全连续
        assertEquals("100", points.get(0).backward().toPlainString());
        assertEquals("100", points.get(1).backward().toPlainString());
        // 前复权：以前一日为锚，除权日前一日价 = P-d = 90，与除权日未复权价一致
        assertEquals("90", points.get(0).forward().toPlainString());
        assertEquals("90", points.get(1).forward().toPlainString());
    }

    @Test
    void dividendExampleOneYuanPerShare() {
        // 需求 1 数值示例：前收盘 10.00 元，每股分红 1.00 元，生效日收盘 9.00 元
        List<RawPrice> prices = List.of(
                price("2024-01-02", "10.00"),
                price("2024-01-03", "9.00"));
        CorporateAction dividend = new CorporateAction(
                "d1", "AAA", LocalDate.parse("2024-01-03"),
                new ActionParams.DividendParams(new BigDecimal("1.00")));

        List<AdjustedPoint> points =
                engine.compute("AAA", prices, List.of(dividend), tick);

        // 后复权生效日价格 = 10.00，与前一交易日完全连续
        assertEquals(0, new BigDecimal("10.00").compareTo(points.get(1).backward()));
        // 前复权生效日前一日价格 = 9.00，与生效日未复权价一致
        assertEquals(0, new BigDecimal("9.00").compareTo(points.get(0).forward()));
        // 未复权保持原始价格
        assertEquals(0, new BigDecimal("10.00").compareTo(points.get(0).raw()));
        assertEquals(0, new BigDecimal("9.00").compareTo(points.get(1).raw()));
    }

    @Test
    void rightsIssueAdjustsByReferencePrice() {
        // 需求 2 数值示例：前收盘 10.00 元，每 10 股配 3 股，配股价 6.00 元
        // 除权参考价 = (10 + 0.3*6) / 1.3 = 9.0769 -> 四舍五入 9.08 元
        List<RawPrice> prices = List.of(
                price("2024-01-02", "10.00"),
                price("2024-01-03", "9.08"));
        CorporateAction rights = new CorporateAction(
                "r1", "AAA", LocalDate.parse("2024-01-03"),
                new ActionParams.RightsIssueParams(
                        new BigDecimal("0.3"), new BigDecimal("6.00")));

        List<AdjustedPoint> points =
                engine.compute("AAA", prices, List.of(rights), tick);

        // 后复权生效日价格回到前收盘水平 10.00（允许一个最小价格单位误差）
        BigDecimal backwardDiff = points.get(1).backward()
                .subtract(new BigDecimal("10.00")).abs();
        assertTrue(backwardDiff.compareTo(tick) <= 0,
                "后复权生效日价应回到 10.00，实际 " + points.get(1).backward());
        // 前复权生效日前一日价格 = 9.08（除权参考价）
        BigDecimal forwardDiff = points.get(0).forward()
                .subtract(new BigDecimal("9.08")).abs();
        assertTrue(forwardDiff.compareTo(tick) <= 0,
                "前复权生效日前一日价应为 9.08，实际 " + points.get(0).forward());
    }

    @Test
    void adjustmentGapNeverExceedsRawGapAcrossExDate() {
        // 需求 3：除权区间内复权价差绝对值不得超过未复权价差绝对值
        // 除权日未复权价低于除权参考价时，复权序列仍满足该不等式
        List<RawPrice> prices = List.of(
                price("2024-01-02", "10.00"),
                price("2024-01-03", "8.50"));
        CorporateAction dividend = new CorporateAction(
                "d1", "AAA", LocalDate.parse("2024-01-03"),
                new ActionParams.DividendParams(new BigDecimal("1.00")));

        List<AdjustedPoint> points =
                engine.compute("AAA", prices, List.of(dividend), tick);

        BigDecimal rawGap = points.get(1).raw().subtract(points.get(0).raw()).abs();
        BigDecimal backwardGap = points.get(1).backward()
                .subtract(points.get(0).backward()).abs();
        BigDecimal forwardGap = points.get(1).forward()
                .subtract(points.get(0).forward()).abs();
        assertTrue(backwardGap.compareTo(rawGap) <= 0,
                "后复权价差 " + backwardGap + " 超过未复权价差 " + rawGap);
        assertTrue(forwardGap.compareTo(rawGap) <= 0,
                "前复权价差 " + forwardGap + " 超过未复权价差 " + rawGap);
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
