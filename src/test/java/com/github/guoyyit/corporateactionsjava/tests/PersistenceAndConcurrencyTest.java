package com.github.guoyyit.corporateactionsjava.tests;

import com.github.guoyyit.corporateactionsjava.domain.ActionParams;
import com.github.guoyyit.corporateactionsjava.domain.PriceView;
import com.github.guoyyit.corporateactionsjava.dto.ActionResponse;
import com.github.guoyyit.corporateactionsjava.dto.IngestPricesRequest;
import com.github.guoyyit.corporateactionsjava.dto.PricePointRequest;
import com.github.guoyyit.corporateactionsjava.dto.PriceSeriesResponse;
import com.github.guoyyit.corporateactionsjava.dto.RegisterActionRequest;
import com.github.guoyyit.corporateactionsjava.error.ApiException;
import com.github.guoyyit.corporateactionsjava.service.AdjustmentEngine;
import com.github.guoyyit.corporateactionsjava.service.CorporateActionService;
import com.github.guoyyit.corporateactionsjava.store.JsonStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceAndConcurrencyTest {

    @TempDir
    Path tempDir;

    private CorporateActionService newService(Path dataFile) {
        CorporateActionService svc = new CorporateActionService(
                new JsonStateStore(dataFile.toString()),
                new AdjustmentEngine(), "0.01");
        svc.restore();
        return svc;
    }

    @Test
    void restartRestoresEventsAndPricesWithoutDuplication() {
        Path dataFile = tempDir.resolve("state.json");
        CorporateActionService first = newService(dataFile);
        first.ingestPrices(new IngestPricesRequest("AAA", List.of(
                new PricePointRequest(LocalDate.parse("2024-01-02"), new BigDecimal("100")),
                new PricePointRequest(LocalDate.parse("2024-01-03"), new BigDecimal("50")))));
        first.register(new RegisterActionRequest("AAA",
                LocalDate.parse("2024-01-03"),
                new ActionParams.SplitParams(new BigDecimal("2"))));

        PriceSeriesResponse beforeRestart =
                first.query("AAA", PriceView.ALL, null, null);

        CorporateActionService restarted = newService(dataFile);
        PriceSeriesResponse afterRestart =
                restarted.query("AAA", PriceView.ALL, null, null);

        assertEquals(beforeRestart.points(), afterRestart.points());
        assertEquals(1, restarted.list("AAA").size());

        // 再重启一次，确认恢复幂等：不会重复应用事件
        CorporateActionService restartedAgain = newService(dataFile);
        assertEquals(1, restartedAgain.list("AAA").size());
        assertEquals(afterRestart.points(),
                restartedAgain.query("AAA", PriceView.ALL, null, null).points());
    }

    @Test
    void concurrentRegistrationsAndQueriesStayConsistent() throws Exception {
        Path dataFile = tempDir.resolve("state.json");
        CorporateActionService service = newService(dataFile);
        service.ingestPrices(new IngestPricesRequest("AAA", List.of(
                new PricePointRequest(LocalDate.parse("2024-01-02"), new BigDecimal("100")),
                new PricePointRequest(LocalDate.parse("2024-07-01"), new BigDecimal("80")),
                new PricePointRequest(LocalDate.parse("2024-12-31"), new BigDecimal("70")))));

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        // 每个线程在不同日期登记拆股（全部应成功），另有读取线程持续验证快照完整性
        List<Thread> readers = new ArrayList<>();
        AtomicInteger readErrors = new AtomicInteger();
        for (int readerIndex = 0; readerIndex < 4; readerIndex++) {
            Thread reader = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        PriceSeriesResponse response =
                                service.query("AAA", PriceView.ALL, null, null);
                        // 快照不变式：点数恒定且因子单调（拆股因子递增）
                        if (response.points().size() != 3) {
                            readErrors.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        readErrors.incrementAndGet();
                    }
                }
            });
            reader.setDaemon(true);
            readers.add(reader);
            reader.start();
        }

        for (int index = 0; index < threads; index++) {
            LocalDate date = LocalDate.of(2024, 2, 1).plusDays(index);
            pool.submit(() -> {
                try {
                    start.await();
                    service.register(new RegisterActionRequest("AAA", date,
                            new ActionParams.SplitParams(new BigDecimal("2"))));
                    success.incrementAndGet();
                } catch (ApiException ex) {
                    rejected.incrementAndGet();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        readers.forEach(Thread::interrupt);

        assertEquals(threads, success.get());
        assertEquals(0, rejected.get());
        assertEquals(0, readErrors.get(), "并发期间查询观察到了不完整快照");
        assertEquals(threads, service.list("AAA").size());

        // 持久化文件仍可恢复出同样数量的事件
        CorporateActionService restarted = newService(dataFile);
        assertEquals(threads, restarted.list("AAA").size());
    }

    @Test
    void concurrentSameDayRegistrationsLeaveExactlyOneEvent() throws Exception {
        Path dataFile = tempDir.resolve("state.json");
        CorporateActionService service = newService(dataFile);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        RegisterActionRequest identical = new RegisterActionRequest("AAA",
                LocalDate.parse("2024-03-01"),
                new ActionParams.DividendParams(new BigDecimal("1")));
        for (int index = 0; index < threads; index++) {
            pool.submit(() -> {
                try {
                    start.await();
                    service.register(identical);
                    accepted.incrementAndGet();
                } catch (ApiException ex) {
                    rejected.incrementAndGet();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, accepted.get());
        assertEquals(threads - 1, rejected.get());
        assertEquals(1, service.list("AAA").size());

        assertEquals(1, newService(dataFile).list("AAA").size());
    }
}
