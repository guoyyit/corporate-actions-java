package com.github.guoyyit.corporateactionsjava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.github.guoyyit.corporateactionsjava.core.domain.RawPrice;
import com.github.guoyyit.corporateactionsjava.core.repo.CorporateEventRepository;
import com.github.guoyyit.corporateactionsjava.core.repo.RawPriceRepository;
import com.github.guoyyit.corporateactionsjava.core.service.RawPriceService;

@SpringBootTest
@AutoConfigureMockMvc
class HttpApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
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
    void registersSplitAndReturnsAllThreeBases() throws Exception {
        ingest("HTTP1", "2024-01-02", "10.00");
        ingest("HTTP1", "2024-01-03", "5.00");

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"h1","symbol":"HTTP1","effectiveDate":"2024-01-03",
                                 "type":"SPLIT","params":{"from":1,"to":2}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("h1"))
                .andExpect(jsonPath("$.type").value("SPLIT"));

        mockMvc.perform(get("/api/prices").param("symbol", "HTTP1").param("basis", "RAW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("RAW"))
                .andExpect(jsonPath("$.prices[0].raw").value(10.00))
                .andExpect(jsonPath("$.prices[1].backward").value(10.00))
                .andExpect(jsonPath("$.prices[0].forward").value(5.00));

        mockMvc.perform(get("/api/prices").param("symbol", "HTTP1").param("basis", "FORWARD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prices[1].forwardFactor").value(1.0000000000));
    }

    @Test
    void invalidParamsReturnBadRequestWithClearMessage() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"bad","symbol":"HTTP2","effectiveDate":"2024-01-03",
                                 "type":"SPLIT","params":{"from":0,"to":2}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("positive")));
    }

    @Test
    void unknownEventTypeIsRejected() throws Exception {
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"bad2","symbol":"HTTP3","effectiveDate":"2024-01-03",
                                 "type":"BOGUS","params":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void duplicateAndConflictingEventsReturnDistinctErrors() throws Exception {
        ingest("HTTP4", "2024-01-02", "10.00");
        ingest("HTTP4", "2024-01-03", "5.00");
        String body = """
                {"eventId":"dup","symbol":"HTTP4","effectiveDate":"2024-01-03",
                 "type":"SPLIT","params":{"from":1,"to":2}}
                """;
        mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EVENT"));

        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"other","symbol":"HTTP4","effectiveDate":"2024-01-03",
                                 "type":"SPLIT","params":{"from":1,"to":3}}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICTING_EVENT"));
    }

    @Test
    void amendAndRevolveThroughHttp() throws Exception {
        ingest("HTTP5", "2024-01-02", "10.00");
        ingest("HTTP5", "2024-01-03", "5.00");
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"am","symbol":"HTTP5","effectiveDate":"2024-01-03",
                                 "type":"SPLIT","params":{"from":1,"to":2}}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/events/am")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"am","symbol":"HTTP5","effectiveDate":"2024-01-03",
                                 "type":"SPLIT","params":{"from":1,"to":4}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.params.to").value(4));

        mockMvc.perform(get("/api/prices").param("symbol", "HTTP5"))
                .andExpect(jsonPath("$.prices[1].backwardFactor").value(4.0000000000));

        mockMvc.perform(delete("/api/events/am")).andExpect(status().isOk());
        mockMvc.perform(get("/api/events").param("symbol", "HTTP5"))
                .andExpect(jsonPath("$[0]").doesNotExist());
        mockMvc.perform(delete("/api/events/am"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    @Test
    void invalidBasisIsRejected() throws Exception {
        mockMvc.perform(get("/api/prices").param("symbol", "HTTP6").param("basis", "MAGIC"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private void ingest(String symbol, String date, String close) {
        rawPriceService.ingest(new RawPrice(symbol, LocalDate.parse(date), new BigDecimal(close)));
    }
}
