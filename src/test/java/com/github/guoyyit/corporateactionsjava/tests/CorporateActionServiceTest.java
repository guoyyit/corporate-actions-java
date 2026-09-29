package com.github.guoyyit.corporateactionsjava.tests;

import com.github.guoyyit.corporateactionsjava.domain.ActionParams;
import com.github.guoyyit.corporateactionsjava.domain.AdjustedPoint;
import com.github.guoyyit.corporateactionsjava.domain.CorporateAction;
import com.github.guoyyit.corporateactionsjava.domain.PriceView;
import com.github.guoyyit.corporateactionsjava.domain.RawPrice;
import com.github.guoyyit.corporateactionsjava.dto.ActionResponse;
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
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CorporateActionServiceTest {

    @TempDir
    Path tempDir;

    private CorporateActionService service;
    private AdjustmentEngine engine;
    private Path dataFile;

    private static final List<RawPrice> PRICES = List.of(
            new RawPrice("AAA", LocalDate.parse("2024-01-02"), new BigDecimal("100")),
            new RawPrice("AAA", LocalDate.parse("2024-01-03"), new BigDecimal("95")),
            new RawPrice("AAA", LocalDate.parse("2024-01-04"), new BigDecimal("60")),
            new RawPrice("AAA", LocalDate.parse("2024-01-05"), new BigDecimal("61")));

    @BeforeEach
    void setUp() {
        dataFile = tempDir.resolve("state.json");
        JsonStateStore store = new JsonStateStore(dataFile.toString());
        engine = new AdjustmentEngine();
        service = new CorporateActionService(store, engine, "0.01");
        service.restore();
        service.ingestPrices(new IngestPricesRequest("AAA",
                PRICES.stream()
                        .map(price -> new PricePointRequest(price.date(), price.close()))
                        .toList()));
    }

    private RegisterActionRequest dividend(String date, String perShare) {
        return new RegisterActionRequest("AAA", LocalDate.parse(date),
                new ActionParams.DividendParams(new BigDecimal(perShare)));
    }

    private List<AdjustedPoint> replay(List<RegisterActionRequest> requests) {
        List<CorporateAction> actions = new ArrayList<>();
        for (int index = 0; index < requests.size(); index++) {
            RegisterActionRequest request = requests.get(index);
            actions.add(new CorporateAction(
                    "replay-" + index, request.symbol(),
                    request.effectiveDate(), request.params()));
        }
        return engine.compute("AAA", PRICES, actions, new BigDecimal("0.01"));
    }

    private List<BigDecimal> backwardFactors(PriceSeriesResponse response) {
        return response.points().stream().map(AdjustedPoint::factor).toList();
    }

    @Test
    void outOfOrderRegistrationYieldsSameResult() {
        List<RegisterActionRequest> ordered = List.of(
                dividend("2024-01-03", "10"),
                dividend("2024-01-04", "5"),
                new RegisterActionRequest("AAA", LocalDate.parse("2024-01-05"),
                        new ActionParams.SplitParams(new BigDecimal("2"))));

        List<RegisterActionRequest> shuffled = new ArrayList<>(ordered);
        Collections.reverse(shuffled);

        List<ActionResponse> registered = new ArrayList<>();
        for (RegisterActionRequest request : shuffled) {
            registered.add(service.register(request));
        }

        PriceSeriesResponse actual =
                service.query("AAA", PriceView.ALL, null, null);
        List<BigDecimal> expectedFactors = replay(ordered).stream()
                .map(AdjustedPoint::factor)
                .toList();
        assertEquals(expectedFactors, backwardFactors(actual));

        // 乱序登记的事件列表仍按生效日期返回
        List<LocalDate> dates = service.list("AAA").stream()
                .map(ActionResponse::effectiveDate)
                .toList();
        assertEquals(List.of(
                LocalDate.parse("2024-01-03"),
                LocalDate.parse("2024-01-04"),
                LocalDate.parse("2024-01-05")), dates);
    }

    @Test
    void modifyAndRevokeEqualReplay() {
        ActionResponse first = service.register(dividend("2024-01-03", "10"));
        ActionResponse second = service.register(dividend("2024-01-04", "5"));

        // 修改生效日与参数后，等价于重放修正后的序列
        service.modify(first.id(), dividend("2024-01-05", "8"));
        List<RegisterActionRequest> afterModify = List.of(
                dividend("2024-01-04", "5"),
                dividend("2024-01-05", "8"));
        PriceSeriesResponse afterModifyResponse =
                service.query("AAA", PriceView.ALL, null, null);
        assertEquals(
                replay(afterModify).stream().map(AdjustedPoint::factor).toList(),
                backwardFactors(afterModifyResponse));

        // 撤销后等价于只重放剩余事件
        service.revoke(second.id());
        PriceSeriesResponse afterRevokeResponse =
                service.query("AAA", PriceView.ALL, null, null);
        assertEquals(
                replay(List.of(dividend("2024-01-05", "8"))).stream()
                        .map(AdjustedPoint::factor).toList(),
                backwardFactors(afterRevokeResponse));
        assertEquals(1, service.list("AAA").size());
    }

    @Test
    void duplicateIsRejectedAndConflictIsDistinguishable() {
        service.register(dividend("2024-01-03", "10"));

        ApiException duplicate = assertThrows(ApiException.class,
                () -> service.register(dividend("2024-01-03", "10")));
        assertEquals("DUPLICATE_EVENT", duplicate.code());

        ApiException conflict = assertThrows(ApiException.class,
                () -> service.register(new RegisterActionRequest(
                        "AAA", LocalDate.parse("2024-01-03"),
                        new ActionParams.SplitParams(new BigDecimal("2")))));
        assertEquals("CONFLICTING_EVENT", conflict.code());

        // 同日不同标的不冲突
        service.register(new RegisterActionRequest(
                "BBB", LocalDate.parse("2024-01-03"),
                new ActionParams.DividendParams(new BigDecimal("1"))));
    }

    @Test
    void invalidParamsAreRejected() {
        assertThrows(ApiException.class,
                () -> service.register(new RegisterActionRequest(
                        "AAA", LocalDate.parse("2024-01-03"),
                        new ActionParams.SplitParams(new BigDecimal("1")))));
        assertThrows(ApiException.class,
                () -> service.register(new RegisterActionRequest(
                        "AAA", LocalDate.parse("2024-01-03"),
                        new ActionParams.SplitParams(new BigDecimal("-2")))));
        assertThrows(ApiException.class,
                () -> service.register(new RegisterActionRequest(
                        "AAA", LocalDate.parse("2024-01-03"),
                        new ActionParams.MergeParams(new BigDecimal("1")))));
        assertThrows(ApiException.class,
                () -> service.register(new RegisterActionRequest(
                        "AAA", LocalDate.parse("2024-01-03"),
                        new ActionParams.DividendParams(new BigDecimal("0")))));
        assertThrows(ApiException.class,
                () -> service.register(new RegisterActionRequest(
                        "AAA", null,
                        new ActionParams.DividendParams(new BigDecimal("1")))));
        assertThrows(ApiException.class,
                () -> service.register(new RegisterActionRequest(
                        "  ", LocalDate.parse("2024-01-03"),
                        new ActionParams.DividendParams(new BigDecimal("1")))));
    }

    @Test
    void revokeUnknownEventIsNotFound() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.revoke("no-such-id"));
        assertEquals("NOT_FOUND", ex.code());
    }
}
