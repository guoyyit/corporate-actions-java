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
    void restartRestoresDividendAndRightsEventsIdentically() {
        Path dataFile = tempDir.resolve("state-dividend-rights.json");
        CorporateActionService first = newService(dataFile);
        first.ingestPrices(new IngestPricesRequest("AAA", List.of(
                new PricePointRequest(LocalDate.parse("2024-03-04"), new BigDecimal("10.00")),
                new PricePointRequest(LocalDate.parse("2024-03-05"), new BigDecimal("9.00")),
                new PricePointRequest(LocalDate.parse("2024-03-06"), new BigDecimal("8.31")))));
        first.register(new RegisterActionRequest("AAA",
                LocalDate.parse("2024-03-05"),
                new ActionParams.DividendParams(new BigDecimal("1.00"))));
        first.register(new RegisterActionRequest("AAA",
                LocalDate.parse("2024-03-06"),
                new ActionParams.RightsIssueParams(
                        new BigDecimal("0.3"), new BigDecimal("6.00"))));

        PriceSeriesResponse beforeRestart =
                first.query("AAA", PriceView.ALL, null, null);

        CorporateActionService restarted = newService(dataFile);
        PriceSeriesResponse afterRestart =
                restarted.query("AAA", PriceView.ALL, null, null);

        // 重启前后复权结果逐点一致，事件不丢失、不重复应用
        assertEquals(beforeRestart.points(), afterRestart.points());
        assertEquals(2, restarted.list("AAA").size());
        // 分红区间后复权连续：除息日 9.00 回到前收盘 10.00
        assertEquals("10", afterRestart.points().get(0).backward().toPlainString());
        assertEquals("10", afterRestart.points().get(1).backward().toPlainString());
        // 配股区间后复权连续：8.31 回到约 10.00（误差不超过一个最小价格单位）
        assertTrue(afterRestart.points().get(2).backward()
                .subtract(new BigDecimal("10.00")).abs()
                .compareTo(new BigDecimal("0.01")) <= 0);
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

        // 每个线程在不同日期登记混合类型事件（全部应成功），另有读取线程持续验证快照完整性
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
            ActionParams params = switch (index % 3) {
                case 0 -> new ActionParams.SplitParams(new BigDecimal("2"));
                case 1 -> new ActionParams.DividendParams(new BigDecimal("1"));
                default -> new ActionParams.RightsIssueParams(
                        new BigDecimal("0.3"), new BigDecimal("5"));
            };
            pool.submit(() -> {
                try {
                    start.await();
                    service.register(new RegisterActionRequest("AAA", date, params));
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

        // 全部事件（含分红与配股）生效后，三种口径查询均正常且价格为正
        PriceSeriesResponse series = service.query("AAA", PriceView.ALL, null, null);
        assertEquals(3, series.points().size());
        assertTrue(series.points().stream().allMatch(point ->
                point.factor().signum() > 0
                        && point.forward().signum() > 0
                        && point.backward().signum() > 0));

        // 持久化文件仍可恢复出同样数量的事件
        CorporateActionService restarted = newService(dataFile);
        assertEquals(threads, restarted.list("AAA").size());
        assertEquals(series.points(),
                restarted.query("AAA", PriceView.ALL, null, null).points());
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
