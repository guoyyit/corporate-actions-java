package com.github.guoyyit.corporateactionsjava.web;

import com.github.guoyyit.corporateactionsjava.core.service.RawPriceService;
import com.github.guoyyit.corporateactionsjava.core.domain.RawPrice;
import com.github.guoyyit.corporateactionsjava.web.dto.RawPriceRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prices")
public class RawPriceController {

    private final RawPriceService service;

    public RawPriceController(RawPriceService service) {
        this.service = service;
    }

    @PostMapping("/raw")
    public RawPriceRequest ingest(@RequestBody RawPriceRequest request) {
        RawPrice saved = service.ingest(
                new RawPrice(request.symbol(), request.date(), request.close()));
        return new RawPriceRequest(saved.symbol(), saved.date(), saved.close());
    }
}
