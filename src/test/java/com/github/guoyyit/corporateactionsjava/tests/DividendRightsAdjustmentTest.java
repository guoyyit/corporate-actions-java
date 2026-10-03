package com.github.guoyyit.corporateactionsjava.tests;

import com.github.guoyyit.corporateactionsjava.domain.ActionParams;
import com.github.guoyyit.corporateactionsjava.domain.AdjustedPoint;
import com.github.guoyyit.corporateactionsjava.domain.PriceView;
import com.github.guoyyit.corporateactionsjava.dto.IngestPricesRequest;
import com.github.guoyyit.corporateactionsjava.dto.PricePointRequest;
import com.github.guoyyit.corporateactionsjava.dto.PriceSeriesResponse;
import com.github.guoyyit.corporateactionsjava.dto.RegisterActionRequest;
import com.github.guoyyit.corporateactionsjava.error.ApiException;
import com.github.guoyyit.corporateactionsjava.service.AdjustmentEngine;
import com.github.guoyyit.corporateactionsjava.service.CorporateActionService;
import com.github.guoyyit.corporateactionsjava.store.JsonStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分红与配股事件的复权语义、连续性、异常边界与批量精度测试。
 *
 * <p>断言独立于具体实现细节：只校验数值示例、连续性、换算关系与错误码。
 */
class DividendRightsAdjustmentTest {

    @TempDir
    Path tempDir;

    private CorporateActionService service;
    private Path dataFile;

    private static final BigDecimal TICK = new BigDecimal("0.01");

    @BeforeEach
    void setUp() {
        dataFile = tempDir.resolve("state.json");
        JsonStateStore store = new JsonStateStore(dataFile.toString());
        service = new CorporateActionService(store, new AdjustmentEngine(), "0.01");
        service.restore();
    }

    private void ingest(String symbol, String... pairs) {
        List<PricePointRequest> points = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            points.add(new PricePointRequest(
                    LocalDate.parse(pairs[i]), new BigDecimal(pairs[i + 1])));
        }
        service.ingestPrices(new IngestPricesRequest(symbol, points));
    }

    private RegisterActionRequest dividend(String symbol, String date, String perShare) {
        return new RegisterActionRequest(symbol, LocalDate.parse(date),
                new ActionParams.DividendParams(new BigDecimal(perShare)));
    }

    private RegisterActionRequest rights(String symbol, String date,
                                         String ratio, String price) {
        return new RegisterActionRequest(symbol, LocalDate.parse(date),
                new ActionParams.RightsIssueParams(
                        new BigDecimal(ratio), new BigDecimal(price)));
    }

    private PriceSeriesResponse query(String symbol) {
        return service.query(symbol, PriceView.ALL, null, null);
    }

    // ---- 需求 1：现金分红数值示例 ----

    @Test
    void dividendBackwardAndForwardContinuity() {
        ingest("AAA", "2024-01-02", "10.00", "2024-01-03", "9.00");
        service.register(dividend("AAA", "2024-01-03", "1.00"));

        PriceSeriesResponse response = query("AAA");
        AdjustedPoint before = response.points().get(0);
        AdjustedPoint exDate = response.points().get(1);

        // 后复权生效日价格 = 10.00，与前一交易日完全连续
        assertEquals(0, new BigDecimal("10.00").compareTo(exDate.backward()));
        // 前复权生效日前一日价格 = 9.00，与生效日未复权价一致
        assertEquals(0, new BigDecimal("9.00").compareTo(before.forward()));
        // 未复权保持原始价格
        assertEquals(0, new BigDecimal("10.00").compareTo(before.raw()));
        assertEquals(0, new BigDecimal("9.00").compareTo(exDate.raw()));
    }

    // ---- 需求 2：配股数值示例 ----

    @Test
    void rightsIssueBackwardReturnsToPreviousClose() {
        ingest("AAA", "2024-01-02", "10.00", "2024-01-03", "9.08");
        service.register(rights("AAA", "2024-01-03", "0.3", "6.00"));

        PriceSeriesResponse response = query("AAA");
        AdjustedPoint before = response.points().get(0);
        AdjustedPoint exDate = response.points().get(1);

        // 后复权生效日价格回到前收盘 10.00（允许一个最小价格单位误差）
        assertTrue(exDate.backward().subtract(new BigDecimal("10.00")).abs()
                .compareTo(TICK) <= 0);
        // 前复权生效日前一日价格 = 9.08（除权参考价）
        assertTrue(before.forward().subtract(new BigDecimal("9.08")).abs()
                .compareTo(TICK) <= 0);
    }

    // ---- 需求 3：除权区间价差不等式（首个行情点与最后行情点之间） ----

    @Test
    void gapInequalityAcrossExDateWithNoQuoteOnExDate() {
        // 生效日 01-03 当天无行情，首个行情点在 01-05，最后行情点在 01-02
        ingest("AAA", "2024-01-02", "10.00", "2024-01-05", "9.00");
        service.register(dividend("AAA", "2024-01-03", "1.00"));

        PriceSeriesResponse response = query("AAA");
        AdjustedPoint before = response.points().get(0);
        AdjustedPoint after = response.points().get(1);

        BigDecimal rawGap = after.raw().subtract(before.raw()).abs();
        BigDecimal backwardGap = after.backward().subtract(before.backward()).abs();
        BigDecimal forwardGap = after.forward().subtract(before.forward()).abs();

        // 除权日未复权价等于除权参考价时，复权序列必须完全连续（价差为 0）
        assertEquals(0, BigDecimal.ZERO.compareTo(backwardGap));
        assertEquals(0, BigDecimal.ZERO.compareTo(forwardGap));
        // 价差绝对值不得超过未复权价差
        assertTrue(backwardGap.compareTo(rawGap) <= 0);
        assertTrue(forwardGap.compareTo(rawGap) <= 0);
    }

    @Test
    void gapInequalityWhenExDatePriceBelowReference() {
        // 除权日未复权价 8.50 低于参考价 9.00，复权价差仍不得超过未复权价差
        ingest("AAA", "2024-01-02", "10.00", "2024-01-03", "8.50");
        service.register(dividend("AAA", "2024-01-03", "1.00"));

        PriceSeriesResponse response = query("AAA");
        AdjustedPoint before = response.points().get(0);
        AdjustedPoint exDate = response.points().get(1);

        BigDecimal rawGap = exDate.raw().subtract(before.raw()).abs();
        BigDecimal backwardGap = exDate.backward().subtract(before.backward()).abs();
        BigDecimal forwardGap = exDate.forward().subtract(before.forward()).abs();

        assertTrue(backwardGap.compareTo(rawGap) <= 0,
                "后复权价差 " + backwardGap + " 超过未复权价差 " + rawGap);
        assertTrue(forwardGap.compareTo(rawGap) <= 0,
                "前复权价差 " + forwardGap + " 超过未复权价差 " + rawGap);
    }

    // ---- 需求 4：多事件序列连续性与精度不衰减 ----

    @Test
    void multipleEventsEachExDateIsContinuous() {
        // 多个分红与配股按生效日先后叠加，每个除权区间都必须连续
        ingest("AAA",
                "2024-01-02", "10.00",
                "2024-01-03", "9.00",   // 分红 d=1，参考价 9.00
                "2024-01-04", "9.50",
                "2024-01-05", "8.69",   // 配股 10配3 配股价6，参考价 8.69
                "2024-01-06", "8.80",
                "2024-01-07", "8.75",
                "2024-01-08", "8.25",   // 分红 d=0.5，参考价 8.25
                "2024-01-09", "8.30",
                "2024-01-10", "8.35");
        service.register(dividend("AAA", "2024-01-03", "1.00"));
        service.register(rights("AAA", "2024-01-05", "0.3", "6.00"));
        service.register(dividend("AAA", "2024-01-08", "0.50"));

        PriceSeriesResponse response = query("AAA");
        List<AdjustedPoint> points = response.points();

        // 每个除权日（01-03、01-05、01-08）的复权序列必须连续
        assertExDateContinuous(points, "2024-01-03");
        assertExDateContinuous(points, "2024-01-05");
        assertExDateContinuous(points, "2024-01-08");

        // 任意交易日三种口径换算误差不超过一个最小价格单位，且不随事件数增长
        BigDecimal latestFactor = points.get(points.size() - 1).factor();
        for (AdjustedPoint point : points) {
            BigDecimal backwardExpected = point.raw().multiply(point.factor());
            assertTrue(point.backward().subtract(backwardExpected).abs()
                    .compareTo(TICK) <= 0, "后复权换算超差: " + point);
            BigDecimal forwardExpected = point.backward()
                    .divide(latestFactor, 10, RoundingMode.HALF_UP);
            assertTrue(point.forward().subtract(forwardExpected).abs()
                    .compareTo(TICK) <= 0, "前复权换算超差: " + point);
        }
    }

    private void assertExDateContinuous(List<AdjustedPoint> points, String exDate) {
        LocalDate date = LocalDate.parse(exDate);
        AdjustedPoint before = null;
        AdjustedPoint after = null;
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).date().equals(date)) {
                after = points.get(i);
                if (i > 0) {
                    before = points.get(i - 1);
                }
                break;
            }
        }
        assertNotNull(before, "除权日 " + exDate + " 前应有行情");
        assertNotNull(after, "除权日 " + exDate + " 应有行情");
        // 后复权在除权日前后必须连续（误差不超过一个最小价格单位）
        assertTrue(after.backward().subtract(before.backward()).abs()
                .compareTo(TICK) <= 0, "后复权在 " + exDate + " 不连续");
        // 前复权在除权日前后必须连续
        assertTrue(after.forward().subtract(before.forward()).abs()
                .compareTo(TICK) <= 0, "前复权在 " + exDate + " 不连续");
    }

    // ---- 需求 5：异常边界 ----

    @Test
    void noCloseBeforeEffectiveDateReturnsDistinctError() {
        // 生效日 01-03 之前不存在任何收盘价
        ingest("AAA", "2024-01-03", "9.00");
        service.register(dividend("AAA", "2024-01-03", "1.00"));

        ApiException ex = assertThrows(ApiException.class, () -> query("AAA"));
        assertEquals("INSUFFICIENT_PRICE_HISTORY", ex.code());
    }

    @Test
    void dividendExceedsPreviousCloseReturnsDistinctError() {
        // 每股红利 10 等于前收盘价 10，除权参考价为 0
        ingest("AAA", "2024-01-02", "10.00", "2024-01-03", "5.00");
        service.register(dividend("AAA", "2024-01-03", "10.00"));

        ApiException ex = assertThrows(ApiException.class, () -> query("AAA"));
        assertEquals("INVALID_DIVIDEND", ex.code());
    }

    @Test
    void dividendLargerThanPreviousCloseReturnsDistinctError() {
        // 每股红利 12 大于前收盘价 10，除权参考价为负
        ingest("AAA", "2024-01-02", "10.00", "2024-01-03", "5.00");
        service.register(dividend("AAA", "2024-01-03", "12.00"));

        ApiException ex = assertThrows(ApiException.class, () -> query("AAA"));
        assertEquals("INVALID_DIVIDEND", ex.code());
    }

    @Test
    void errorDoesNotAffectOtherSymbols() {
        // AAA 的分红事件不合法，BBB 的查询必须正常
        ingest("AAA", "2024-01-03", "9.00");
        service.register(dividend("AAA", "2024-01-03", "1.00"));
        ingest("BBB", "2024-01-02", "20.00", "2024-01-03", "19.00");
        service.register(dividend("BBB", "2024-01-03", "1.00"));

        assertThrows(ApiException.class, () -> query("AAA"));

        PriceSeriesResponse bbb = query("BBB");
        assertEquals(2, bbb.points().size());
        // BBB 的复权结果正确：后复权生效日价 = 20.00
        assertEquals(0, new BigDecimal("20.00").compareTo(
                bbb.points().get(1).backward()));
    }

    @Test
    void errorDoesNotAffectRawViewOfSameSymbol() {
        // AAA 的分红事件不合法，但 RAW 口径不依赖复权因子，仍可返回原始价格
        ingest("AAA", "2024-01-03", "9.00");
        service.register(dividend("AAA", "2024-01-03", "1.00"));

        PriceSeriesResponse raw = service.query("AAA", PriceView.RAW, null, null);
        assertEquals(1, raw.points().size());
        assertEquals(0, new BigDecimal("9.00").compareTo(raw.points().get(0).raw()));
        // RAW 口径下复权字段为空（不返回零价格或负数价格）
        assertNull(raw.points().get(0).forward());
        assertNull(raw.points().get(0).backward());
        assertNull(raw.points().get(0).factor());

        // 其他口径仍返回明确错误
        assertThrows(ApiException.class,
                () -> service.query("AAA", PriceView.FORWARD, null, null));
        assertThrows(ApiException.class,
                () -> service.query("AAA", PriceView.BACKWARD, null, null));
        assertThrows(ApiException.class,
                () -> service.query("AAA", PriceView.ALL, null, null));
    }

    @Test
    void queryRecoversAfterBackfillingPrices() {
        // 登记时缺少前置收盘价，查询报错；补录后查询恢复正确结果
        ingest("AAA", "2024-01-03", "9.00");
        service.register(dividend("AAA", "2024-01-03", "1.00"));
        assertThrows(ApiException.class, () -> query("AAA"));

        // 补录生效日之前的历史价格
        ingest("AAA", "2024-01-02", "10.00");

        PriceSeriesResponse response = query("AAA");
        assertEquals(2, response.points().size());
        // 后复权生效日价 = 10.00，与前一日连续
        assertEquals(0, new BigDecimal("10.00").compareTo(
                response.points().get(1).backward()));
        // 前复权生效日前一日价 = 9.00
        assertEquals(0, new BigDecimal("9.00").compareTo(
                response.points().get(0).forward()));
    }

    @Test
    void queryRecoversAfterCorrectingDividend() {
        // 分红金额大于前收盘价，查询报错；修正分红金额后恢复
        ingest("AAA", "2024-01-02", "10.00", "2024-01-03", "9.00");
        String eventId = service.register(dividend("AAA", "2024-01-03", "12.00")).id();
        assertThrows(ApiException.class, () -> query("AAA"));

        service.modify(eventId, dividend("AAA", "2024-01-03", "1.00"));

        PriceSeriesResponse response = query("AAA");
        assertEquals(2, response.points().size());
        // 后复权生效日价 = 10.00
        assertEquals(0, new BigDecimal("10.00").compareTo(
                response.points().get(1).backward()));
    }

    // ---- 需求 6：登记与撤销行为一致 ----

    @Test
    void registerAndRevokeConsistency() {
        // 登记不合法事件后查询报错；撤销后查询恢复正常
        ingest("AAA", "2024-01-03", "9.00");
        String eventId = service.register(dividend("AAA", "2024-01-03", "1.00")).id();
        assertThrows(ApiException.class, () -> query("AAA"));

        service.revoke(eventId);

        // 撤销后事件不存在，查询恢复正常（无复权因子）
        PriceSeriesResponse response = query("AAA");
        assertEquals(1, response.points().size());
        assertEquals(0, new BigDecimal("9.00").compareTo(
                response.points().get(0).raw()));
        // 无事件时复权因子为 1
        assertEquals(0, BigDecimal.ONE.compareTo(
                response.points().get(0).factor()));
    }

    @Test
    void outOfOrderRegistrationWithDividendsAndRights() {
        // 乱序登记分红与配股，结果与顺序登记完全一致
        ingest("AAA",
                "2024-01-02", "10.00",
                "2024-01-03", "9.00",
                "2024-01-04", "9.50",
                "2024-01-05", "8.69");

        service.register(rights("AAA", "2024-01-05", "0.3", "6.00"));
        service.register(dividend("AAA", "2024-01-03", "1.00"));

        PriceSeriesResponse response = query("AAA");
        // 除权日（01-03 分红、01-05 配股）必须连续
        assertExDateContinuous(response.points(), "2024-01-03");
        assertExDateContinuous(response.points(), "2024-01-05");
    }

    @Test
    void duplicateAndConflictDistinguishableForDividendsAndRights() {
        ingest("AAA", "2024-01-02", "10.00", "2024-01-03", "9.00");
        service.register(dividend("AAA", "2024-01-03", "1.00"));

        ApiException duplicate = assertThrows(ApiException.class,
                () -> service.register(dividend("AAA", "2024-01-03", "1.00")));
        assertEquals("DUPLICATE_EVENT", duplicate.code());

        ApiException conflict = assertThrows(ApiException.class,
                () -> service.register(rights("AAA", "2024-01-03", "0.3", "6.00")));
        assertEquals("CONFLICTING_EVENT", conflict.code());
    }

    @Test
    void restartRecoveryPreservesDividendAndRights() {
        ingest("AAA",
                "2024-01-02", "10.00",
                "2024-01-03", "9.00",
                "2024-01-04", "9.50",
                "2024-01-05", "8.69");
        service.register(dividend("AAA", "2024-01-03", "1.00"));
        service.register(rights("AAA", "2024-01-05", "0.3", "6.00"));

        PriceSeriesResponse before = query("AAA");

        CorporateActionService restarted = new CorporateActionService(
                new JsonStateStore(dataFile.toString()),
                new AdjustmentEngine(), "0.01");
        restarted.restore();

        PriceSeriesResponse after = restarted.query("AAA", PriceView.ALL, null, null);
        assertEquals(before.points(), after.points());
        assertEquals(2, restarted.list("AAA").size());
    }
}
