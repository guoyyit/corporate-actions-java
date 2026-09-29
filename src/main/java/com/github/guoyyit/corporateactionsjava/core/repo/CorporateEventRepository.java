package com.github.guoyyit.corporateactionsjava.core.repo;

import com.github.guoyyit.corporateactionsjava.core.domain.CorporateEvent;
import com.github.guoyyit.corporateactionsjava.core.domain.DividendParams;
import com.github.guoyyit.corporateactionsjava.core.domain.EventParams;
import com.github.guoyyit.corporateactionsjava.core.domain.EventType;
import com.github.guoyyit.corporateactionsjava.core.domain.MergeParams;
import com.github.guoyyit.corporateactionsjava.core.domain.RightsParams;
import com.github.guoyyit.corporateactionsjava.core.domain.SplitParams;
import com.github.guoyyit.corporateactionsjava.core.error.ApiException;
import com.github.guoyyit.corporateactionsjava.core.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class CorporateEventRepository {

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public CorporateEventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<CorporateEvent> findById(String eventId) {
        List<CorporateEvent> events = jdbcTemplate.query(
                "SELECT event_id, symbol, effective_date, event_type, params_json FROM corporate_event WHERE event_id = ?",
                (rs, rowNum) -> mapRow(rs),
                eventId);
        return events.stream().findFirst();
    }

    public List<CorporateEvent> findBySymbol(String symbol) {
        return jdbcTemplate.query(
                "SELECT event_id, symbol, effective_date, event_type, params_json FROM corporate_event WHERE symbol = ?",
                (rs, rowNum) -> mapRow(rs),
                symbol);
    }

    public List<CorporateEvent> findAll() {
        return jdbcTemplate.query(
                "SELECT event_id, symbol, effective_date, event_type, params_json FROM corporate_event",
                (rs, rowNum) -> mapRow(rs));
    }

    public void insert(CorporateEvent event) {
        jdbcTemplate.update(
                "INSERT INTO corporate_event(event_id, symbol, effective_date, event_type, params_json) VALUES (?, ?, ?, ?, ?)",
                event.eventId(),
                event.symbol(),
                Date.valueOf(event.effectiveDate()),
                event.type().name(),
                writeParams(event.params()));
    }

    public void update(CorporateEvent event) {
        int updated = jdbcTemplate.update(
                "UPDATE corporate_event SET symbol = ?, effective_date = ?, event_type = ?, params_json = ? WHERE event_id = ?",
                event.symbol(),
                Date.valueOf(event.effectiveDate()),
                event.type().name(),
                writeParams(event.params()),
                event.eventId());
        if (updated == 0) {
            throw new ApiException(ErrorCode.EVENT_NOT_FOUND,
                    "event not found for update: " + event.eventId());
        }
    }

    public void delete(String eventId) {
        jdbcTemplate.update("DELETE FROM corporate_event WHERE event_id = ?", eventId);
    }

    private CorporateEvent mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String eventId = rs.getString("event_id");
        String symbol = rs.getString("symbol");
        LocalDate effectiveDate = rs.getDate("effective_date").toLocalDate();
        EventType type = EventType.valueOf(rs.getString("event_type"));
        EventParams params = readParams(type, rs.getString("params_json"));
        return new CorporateEvent(eventId, symbol, effectiveDate, type, params);
    }

    private String writeParams(EventParams params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "failed to serialize event params: " + ex.getMessage());
        }
    }

    private EventParams readParams(EventType type, String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return switch (type) {
                case SPLIT -> new SplitParams(node.get("from").asInt(), node.get("to").asInt());
                case MERGE -> new MergeParams(node.get("from").asInt(), node.get("to").asInt());
                case DIVIDEND -> new DividendParams(node.get("dividendPerShare").decimalValue());
                case RIGHTS -> new RightsParams(node.get("subscriptionPrice").decimalValue(),
                        node.get("ratio").decimalValue());
            };
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "failed to deserialize event params: " + ex.getMessage());
        }
    }
}
