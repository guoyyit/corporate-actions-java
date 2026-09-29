package com.github.guoyyit.corporateactionsjava.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.github.guoyyit.corporateactionsjava.core.domain.CorporateEvent;
import com.github.guoyyit.corporateactionsjava.core.domain.DividendParams;
import com.github.guoyyit.corporateactionsjava.core.domain.EventType;
import com.github.guoyyit.corporateactionsjava.core.domain.PricePoint;
import com.github.guoyyit.corporateactionsjava.core.domain.RawPrice;
import com.github.guoyyit.corporateactionsjava.core.domain.SplitParams;
import com.github.guoyyit.corporateactionsjava.core.error.ApiException;
import com.github.guoyyit.corporateactionsjava.core.error.ErrorCode;
import com.github.guoyyit.corporateactionsjava.core.repo.CorporateEventRepository;
import com.github.guoyyit.corporateactionsjava.core.repo.RawPriceRepository;
import com.github.guoyyit.corporateactionsjava.core.service.CorporateActionService;
import com.github.guoyyit.corporateactionsjava.core.service.RawPriceService;

@SpringBootTest
class CorporateActionServiceIntegrationTest {

    @Autowired
    private CorporateActionService service;
    @Autowired
    private RawPriceService rawPriceService;
    @Autowired
    private CorporateEventRepository eventRepository;
    @Autowired
    private RawPriceRepository rawPriceRepository;

    @BeforeEach
    void clean() {
        eventRepository.findAll().forEach(event -> eventRepository.delete(event.eventId()));
        rawPriceRepository.findAllSymbols().forEach(rawPriceRepository::deleteBySymbol);
    }

    @Test
    void registrationOrderDoesNotChangeResult() {
        ingestBasePrices("AAA");
        CorporateEvent split = event("s1", "AAA", "2024-01-03", EventType.SPLIT,
                new SplitParams(1, 2));
        CorporateEvent dividend = event("d1", "AAA", "2024-02-07", EventType.DIVIDEND,
                new DividendParams(new BigDecimal("1.00")));

        service.register(split);
        service.register(dividend);
        List<PricePoint> first = service.queryPrices("AAA");

        service.revoke("s1");
        service.revoke("d1");
        service.register(dividend);
        service.register(split);
        List<PricePoint> second = service.queryPrices("AAA");

        assertThat(second).usingRecursiveComparison().isEqualTo(first);
    }

    @Test
    void duplicateRegistrationIsRejectedAndNotAppliedTwice() {
        ingestBasePrices("BBB");
        CorporateEvent split = event("dup", "BBB", "2024-01-03", EventType.SPLIT,
                new SplitParams(1, 2));

        service.register(split);
        assertThatThrownBy(() -> service.register(split))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.DUPLICATE_EVENT);

        List<PricePoint> points = service.queryPrices("BBB");
        assertThat(points.get(1).backwardFactor()).isEqualByComparingTo("2.0000000000");
    }

    @Test
    void conflictingSameTypeEventsOnSameDateAreRejectedWithDistinctError() {
        ingestBasePrices("CCC");
        service.register(event("c1", "CCC", "2024-01-03", EventType.SPLIT, new SplitParams(1, 2)));

        assertThatThrownBy(() -> service.register(
                event("c2", "CCC", "2024-01-03", EventType.SPLIT, new SplitParams(1, 3))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.CONFLICTING_EVENT);
    }

    @Test
    void differentTypeEventsOnSameDateCanCoexist() {
        ingestBasePrices("DDD");
        service.register(event("x1", "DDD", "2024-01-03", EventType.SPLIT, new SplitParams(1, 2)));
        service.register(event("x2", "DDD", "2024-01-03", EventType.DIVIDEND,
                new DividendParams(new BigDecimal("0.50"))));

        assertThat(service.listEvents("DDD")).hasSize(2);
    }

    @Test
    void invalidParamsAreRejectedWithClearError() {
        assertThatThrownBy(() -> service.register(
                event("bad", "EEE", "2024-01-03", EventType.SPLIT, new SplitParams(0, 2))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void amendingEventRecomputesHistoryEquivalentToReplay() {
        ingestBasePrices("FFF");
        service.register(event("a1", "FFF", "2024-01-03", EventType.SPLIT, new SplitParams(1, 2)));

        service.amend(event("a1", "FFF", "2024-01-03", EventType.SPLIT, new SplitParams(1, 4)));
        List<PricePoint> afterAmend = service.queryPrices("FFF");

        service.revoke("a1");
        service.register(event("a1", "FFF", "2024-01-03", EventType.SPLIT, new SplitParams(1, 4)));
        List<PricePoint> replayed = service.queryPrices("FFF");

        assertThat(afterAmend).usingRecursiveComparison().isEqualTo(replayed);
        assertThat(afterAmend.get(1).backwardFactor()).isEqualByComparingTo("4.0000000000");
    }

    @Test
    void revokingEventRecomputesHistoryAndIsDurable() {
        ingestBasePrices("GGG");
        service.register(event("g1", "GGG", "2024-01-03", EventType.SPLIT, new SplitParams(1, 2)));
        assertThat(service.queryPrices("GGG").get(1).backwardFactor())
                .isEqualByComparingTo("2.0000000000");

        service.revoke("g1");

        assertThat(eventRepository.findById("g1")).isEmpty();
        assertThat(service.queryPrices("GGG").get(1).backwardFactor())
                .isEqualByComparingTo("1.0000000000");
        assertThatThrownBy(() -> service.revoke("g1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.EVENT_NOT_FOUND);
    }

    private void ingestBasePrices(String symbol) {
        rawPriceService.ingest(new RawPrice(symbol, LocalDate.parse("2024-01-02"), new BigDecimal("10.00")));
        rawPriceService.ingest(new RawPrice(symbol, LocalDate.parse("2024-01-03"), new BigDecimal("5.00")));
        rawPriceService.ingest(new RawPrice(symbol, LocalDate.parse("2024-02-06"), new BigDecimal("10.00")));
        rawPriceService.ingest(new RawPrice(symbol, LocalDate.parse("2024-02-07"), new BigDecimal("9.50")));
    }

    private CorporateEvent event(String id, String symbol, String date, EventType type,
                                 com.github.guoyyit.corporateactionsjava.core.domain.EventParams params) {
        return new CorporateEvent(id, symbol, LocalDate.parse(date), type, params);
    }
}
