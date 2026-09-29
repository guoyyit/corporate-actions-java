package com.github.guoyyit.corporateactionsjava.core.service;

import com.github.guoyyit.corporateactionsjava.core.error.ApiException;
import com.github.guoyyit.corporateactionsjava.core.error.ErrorCode;
import com.github.guoyyit.corporateactionsjava.core.domain.RawPrice;
import com.github.guoyyit.corporateactionsjava.core.repo.RawPriceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RawPriceService {

    private final RawPriceRepository rawPriceRepository;

    public RawPriceService(RawPriceRepository rawPriceRepository) {
        this.rawPriceRepository = rawPriceRepository;
    }

    @Transactional
    public RawPrice ingest(RawPrice price) {
        if (price == null || price.symbol() == null || price.symbol().isBlank()
                || price.date() == null || price.close() == null || price.close().signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "raw price requires symbol, date and a positive close");
        }
        rawPriceRepository.upsert(price);
        return price;
    }
}
