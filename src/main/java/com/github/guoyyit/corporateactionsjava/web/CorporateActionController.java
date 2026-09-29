package com.github.guoyyit.corporateactionsjava.web;

import com.github.guoyyit.corporateactionsjava.core.domain.CorporateEvent;
import com.github.guoyyit.corporateactionsjava.core.domain.PriceBasis;
import com.github.guoyyit.corporateactionsjava.core.domain.PricePoint;
import com.github.guoyyit.corporateactionsjava.core.error.ApiException;
import com.github.guoyyit.corporateactionsjava.core.error.ErrorCode;
import com.github.guoyyit.corporateactionsjava.core.service.CorporateActionService;
import com.github.guoyyit.corporateactionsjava.web.dto.EventRequest;
import com.github.guoyyit.corporateactionsjava.web.dto.EventResponse;
import com.github.guoyyit.corporateactionsjava.web.dto.PriceQueryResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.List;

@RestController
@RequestMapping("/api")
public class CorporateActionController {

    private final CorporateActionService service;
    private final EventParamsMapper mapper;

    public CorporateActionController(CorporateActionService service,
                                     EventParamsMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping("/events")
    public EventResponse register(@RequestBody EventRequest request) {
        return toResponse(service.register(mapper.toDomain(request)));
    }

    @PutMapping("/events/{eventId}")
    public EventResponse amend(@PathVariable String eventId,
                               @RequestBody EventRequest request) {
        CorporateEvent domain = mapper.toDomain(request);
        if (!eventId.equals(domain.eventId())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "path eventId " + eventId + " does not match body eventId " + domain.eventId());
        }
        return toResponse(service.amend(domain));
    }

    @DeleteMapping("/events/{eventId}")
    public void revoke(@PathVariable String eventId) {
        service.revoke(eventId);
    }

    @GetMapping("/events")
    public List<EventResponse> list(@RequestParam String symbol) {
        requireSymbol(symbol);
        return service.listEvents(symbol).stream().map(this::toResponse).toList();
    }

    @GetMapping("/prices")
    public PriceQueryResponse prices(@RequestParam String symbol,
                                     @RequestParam(defaultValue = "RAW") String basis) {
        requireSymbol(symbol);
        PriceBasis priceBasis = parseBasis(basis);
        return new PriceQueryResponse(symbol, priceBasis.name(), service.queryPrices(symbol));
    }

    private PriceBasis parseBasis(String raw) {
        try {
            return PriceBasis.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "unknown basis '" + raw + "', expected one of " + EnumSet.allOf(PriceBasis.class));
        }
    }

    private void requireSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "symbol query parameter is required");
        }
    }

    private EventResponse toResponse(CorporateEvent event) {
        return new EventResponse(event.eventId(), event.symbol(), event.effectiveDate(),
                event.type().name(), mapper.toParamsView(event.params()));
    }
}
