package com.github.guoyyit.corporateactionsjava.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.github.guoyyit.corporateactionsjava.core.domain.CorporateEvent;
import com.github.guoyyit.corporateactionsjava.core.domain.DividendParams;
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
class ConcurrencyIntegrationTest {

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
    void concurrentDuplicateRegistrationsApplyExactlyOnce() throws Exception {
        rawPriceService.ingest(new RawPrice("CONC", LocalDate.parse("2024-01-02"), new BigDecimal("10.00")));
        rawPriceService.ingest(new RawPrice("CONC", LocalDate.parse("2024-01-03"), new BigDecimal("5.00")));
        CorporateEvent split = new CorporateEvent("cc", "CONC", LocalDate.parse("2024-01-03"),
                com.github.guoyyit.corporateactionsjava.core.domain.EventType.SPLIT,
                new SplitParams(1, 2));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<String> task = () -> {
                start.await();
                try {
                    service.register(split);
                    return "REGISTERED";
                } catch (ApiException ex) {
                    return ex.code().name();
                }
            };
            futures.add(pool.submit(task));
        }
        start.countDown();

        long registered = 0;
        long duplicates = 0;
        for (Future<String> future : futures) {
            String result = future.get(30, TimeUnit.SECONDS);
            if ("REGISTERED".equals(result)) {
                registered++;
            } else if ("DUPLICATE_EVENT".equals(result)) {
                duplicates++;
            }
        }
        pool.shutdown();

        assertThat(registered).isEqualTo(1);
        assertThat(registered + duplicates).isEqualTo(threads);
        assertThat(eventRepository.findBySymbol("CONC")).hasSize(1);
        assertThat(service.queryPrices("CONC").get(1).backwardFactor())
                .isEqualByComparingTo("2.0000000000");
    }

    @Test
    void queriesDuringWritesAlwaysObserveWholeState() throws Exception {
        rawPriceService.ingest(new RawPrice("RACE", LocalDate.parse("2024-01-02"), new BigDecimal("10.00")));
        rawPriceService.ingest(new RawPrice("RACE", LocalDate.parse("2024-01-03"), new BigDecimal("5.00")));
        service.register(new CorporateEvent("race1", "RACE", LocalDate.parse("2024-01-03"),
                com.github.guoyyit.corporateactionsjava.core.domain.EventType.SPLIT,
                new SplitParams(1, 2)));

        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        int readerCount = 24;
        List<Future<Boolean>> readers = new ArrayList<>();
        for (int i = 0; i < readerCount; i++) {
            readers.add(pool.submit(() -> {
                start.await();
                List<PricePoint> points = service.queryPrices("RACE");
                BigDecimal factor = points.get(1).backwardFactor();
                return factor.compareTo(new BigDecimal("2")) == 0
                        || factor.compareTo(new BigDecimal("4")) == 0;
            }));
        }
        Future<?> writer = pool.submit(() -> {
            try {
                start.await();
                service.amend(new CorporateEvent("race1", "RACE", LocalDate.parse("2024-01-03"),
                        com.github.guoyyit.corporateactionsjava.core.domain.EventType.SPLIT,
                        new SplitParams(1, 4)));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        });
        start.countDown();

        writer.get(30, TimeUnit.SECONDS);
        for (Future<Boolean> reader : readers) {
            assertThat(reader.get(30, TimeUnit.SECONDS))
                    .as("reader must observe either the pre-amend factor 2 or post-amend factor 4")
                    .isTrue();
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(service.queryPrices("RACE").get(1).backwardFactor())
                .isEqualByComparingTo("4.0000000000");
    }
}
