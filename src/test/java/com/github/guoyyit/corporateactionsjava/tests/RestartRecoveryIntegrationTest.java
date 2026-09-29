package com.github.guoyyit.corporateactionsjava.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.github.guoyyit.corporateactionsjava.Application;
import com.github.guoyyit.corporateactionsjava.core.domain.CorporateEvent;
import com.github.guoyyit.corporateactionsjava.core.domain.EventType;
import com.github.guoyyit.corporateactionsjava.core.domain.PricePoint;
import com.github.guoyyit.corporateactionsjava.core.domain.RawPrice;
import com.github.guoyyit.corporateactionsjava.core.domain.SplitParams;
import com.github.guoyyit.corporateactionsjava.core.service.CorporateActionService;
import com.github.guoyyit.corporateactionsjava.core.service.RawPriceService;

class RestartRecoveryIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void eventsAndPricesSurviveRestartWithoutDuplication() {
        String jdbcUrl = "jdbc:h2:file:" + tempDir.resolve("restart-db").toAbsolutePath();

        try (ConfigurableApplicationContext first = start(jdbcUrl)) {
            RawPriceService rawPriceService = first.getBean(RawPriceService.class);
            CorporateActionService actionService = first.getBean(CorporateActionService.class);
            rawPriceService.ingest(new RawPrice("RESTART", LocalDate.parse("2024-01-02"),
                    new BigDecimal("10.00")));
            rawPriceService.ingest(new RawPrice("RESTART", LocalDate.parse("2024-01-03"),
                    new BigDecimal("5.00")));
            actionService.register(new CorporateEvent("p1", "RESTART",
                    LocalDate.parse("2024-01-03"), EventType.SPLIT, new SplitParams(1, 2)));
            assertThat(actionService.queryPrices("RESTART").get(1).backwardFactor())
                    .isEqualByComparingTo("2.0000000000");
        }

        try (ConfigurableApplicationContext second = start(jdbcUrl)) {
            CorporateActionService actionService = second.getBean(CorporateActionService.class);
            List<PricePoint> recovered = actionService.queryPrices("RESTART");

            assertThat(recovered).hasSize(2);
            assertThat(recovered.get(1).raw()).isEqualByComparingTo("5.00");
            assertThat(recovered.get(1).backwardFactor()).isEqualByComparingTo("2.0000000000");
            assertThat(actionService.listEvents("RESTART")).hasSize(1);
        }
    }

    private ConfigurableApplicationContext start(String jdbcUrl) {
        return new SpringApplicationBuilder(Application.class)
                .properties(
                        "spring.datasource.url=" + jdbcUrl,
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.sql.init.mode=always",
                        "spring.main.web-application-type=none")
                .run();
    }
}
