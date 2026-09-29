package com.github.guoyyit.corporateactionsjava.web;

import com.github.guoyyit.corporateactionsjava.domain.PriceView;
import com.github.guoyyit.corporateactionsjava.dto.ActionResponse;
import com.github.guoyyit.corporateactionsjava.dto.IngestPricesRequest;
import com.github.guoyyit.corporateactionsjava.dto.PriceSeriesResponse;
import com.github.guoyyit.corporateactionsjava.dto.RegisterActionRequest;
import com.github.guoyyit.corporateactionsjava.error.ApiException;
import com.github.guoyyit.corporateactionsjava.service.CorporateActionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api")
public class CorporateActionController {

    private final CorporateActionService service;

    public CorporateActionController(CorporateActionService service) {
        this.service = service;
    }

    @PostMapping("/actions")
    public ResponseEntity<ActionResponse> register(@RequestBody RegisterActionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.register(request));
    }

    @PutMapping("/actions/{id}")
    public ResponseEntity<ActionResponse> modify(@PathVariable String id,
                                                 @RequestBody RegisterActionRequest request) {
        return ResponseEntity.ok(service.modify(id, request));
    }

    @DeleteMapping("/actions/{id}")
    public ResponseEntity<Void> revoke(@PathVariable String id) {
        service.revoke(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/actions")
    public List<ActionResponse> list(@RequestParam(required = false) String symbol) {
        return service.list(symbol);
    }

    @PostMapping("/prices")
    public ResponseEntity<Void> ingestPrices(@RequestBody IngestPricesRequest request) {
        service.ingestPrices(request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/prices/{symbol}")
    public PriceSeriesResponse query(@PathVariable String symbol,
                                     @RequestParam(defaultValue = "ALL") String view,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.query(symbol, parseView(view), from, to);
    }

    private PriceView parseView(String view) {
        if (view == null || view.isBlank()) {
            return PriceView.ALL;
        }
        try {
            return PriceView.valueOf(view.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.invalidParam(
                    "view 取值只能为 ALL、RAW、FORWARD、BACKWARD，实际为 " + view);
        }
    }
}
