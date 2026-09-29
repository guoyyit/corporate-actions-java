package com.github.guoyyit.corporateactionsjava.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.guoyyit.corporateactionsjava.core.domain.CorporateEvent;
import com.github.guoyyit.corporateactionsjava.core.domain.DividendParams;
import com.github.guoyyit.corporateactionsjava.core.domain.EventParams;
import com.github.guoyyit.corporateactionsjava.core.domain.EventType;
import com.github.guoyyit.corporateactionsjava.core.domain.MergeParams;
import com.github.guoyyit.corporateactionsjava.core.domain.RightsParams;
import com.github.guoyyit.corporateactionsjava.core.domain.SplitParams;
import com.github.guoyyit.corporateactionsjava.core.error.ApiException;
import com.github.guoyyit.corporateactionsjava.core.error.ErrorCode;
import com.github.guoyyit.corporateactionsjava.web.dto.EventRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class EventParamsMapper {

    public CorporateEvent toDomain(EventRequest request) {
        EventType type = parseType(request.type());
        EventParams params = switch (type) {
            case SPLIT -> new SplitParams(requiredInt(request.params(), "from"),
                    requiredInt(request.params(), "to"));
            case MERGE -> new MergeParams(requiredInt(request.params(), "from"),
                    requiredInt(request.params(), "to"));
            case DIVIDEND -> new DividendParams(requiredDecimal(request.params(), "dividendPerShare"));
            case RIGHTS -> new RightsParams(requiredDecimal(request.params(), "subscriptionPrice"),
                    requiredDecimal(request.params(), "ratio"));
        };
        return new CorporateEvent(request.eventId(), request.symbol(),
                request.effectiveDate(), type, params);
    }

    public Object toParamsView(Object params) {
        return params;
    }

    private EventType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "event type is required");
        }
        try {
            return EventType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "unknown event type '" + raw + "', expected one of SPLIT, DIVIDEND, RIGHTS, MERGE");
        }
    }

    private JsonNode requireObject(JsonNode params) {
        if (params == null || !params.isObject()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "typed params object is required");
        }
        return params;
    }

    private int requiredInt(JsonNode params, String field) {
        JsonNode node = requireObject(params).get(field);
        if (node == null || !node.canConvertToInt()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "params." + field + " is required as an integer");
        }
        return node.asInt();
    }

    private BigDecimal requiredDecimal(JsonNode params, String field) {
        JsonNode node = requireObject(params).get(field);
        if (node == null || !node.isNumber()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "params." + field + " is required as a number");
        }
        return node.decimalValue();
    }
}
