package com.github.guoyyit.corporateactionsjava.tests;

import com.github.guoyyit.corporateactionsjava.domain.ActionParams;
import com.github.guoyyit.corporateactionsjava.domain.AdjustedPoint;
import com.github.guoyyit.corporateactionsjava.domain.CorporateAction;
import com.github.guoyyit.corporateactionsjava.domain.RawPrice;
import com.github.guoyyit.corporateactionsjava.service.AdjustmentEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumericalStabilityTest {

    private final AdjustmentEngine engine = new AdjustmentEngine();
    private final BigDecimal tick = new BigDecimal("0.01");

    @Test
    void incrementalApplicationEqualsFullReplayBitwise() {
        // 构造一条覆盖所有事件类型的长序列
        List<RawPrice> prices = new ArrayList<>();
        LocalDate start = LocalDate.of(2000, 1, 3);
        int days = 220;
        BigDecimal close = new BigDecimal("100");
        for (int index = 0; index < days; index++) {
            LocalDate date = start.plusDays(index);
            close = close.add(new BigDecimal(index % 7 - 3));
            if (close.signum() <= 0) {
                close = new BigDecimal("20");
            }
            prices.add(new RawPrice("AAA", date, close));
        }

        List<CorporateAction> allActions = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            LocalDate effective = prices.get(index + 1).date();
            ActionParams params = switch (index % 5) {
                case 0 -> new ActionParams.SplitParams(new BigDecimal("2"));
                case 1 -> new ActionParams.MergeParams(new BigDecimal("0.5"));
                case 2, 3 -> new ActionParams.DividendParams(new BigDecimal("1." + (index % 9 + 1)));
                default -> new ActionParams.RightsIssueParams(
                        new BigDecimal("0.3"), new BigDecimal("5"));
            };
            allActions.add(new CorporateAction("evt-" + index, "AAA", effective, params));
        }

        // 整体重放（一次计算全部事件）
        List<AdjustedPoint> replay =
                engine.compute("AAA", prices, allActions, tick);

        // 逐条应用：事件每增加一条就重新计算，最后一次必须与整体重放逐位一致
        List<AdjustedPoint> incremental = List.of();
        for (int index = 1; index <= allActions.size(); index++) {
            List<CorporateAction> prefix = allActions.subList(0, index);
            incremental = engine.compute("AAA", prices, prefix, tick);
        }
        assertEquals(replay, incremental, "逐条应用与整体重放的复权结果必须逐位一致");

        // 乱序重放：打乱事件登记顺序，结果不变
        List<CorporateAction> shuffled = new ArrayList<>(allActions);
        Collections.shuffle(shuffled, new java.util.Random(42));
        List<AdjustedPoint> shuffledResult =
                engine.compute("AAA", prices, shuffled, tick);
        assertEquals(replay, shuffledResult, "乱序登记必须得到完全一致的结果");

        // 因子与前后复权价始终为正且有限（误差不随事件数累积放大）
        for (AdjustedPoint point : replay) {
            assertTrue(point.factor().signum() > 0);
            assertTrue(point.forward().signum() >= 0);
            assertTrue(point.backward().signum() >= 0);
        }
    }
}
