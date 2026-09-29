package com.github.guoyyit.corporateactionsjava.core.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.github.guoyyit.corporateactionsjava.core.domain.CorporateEvent;
import com.github.guoyyit.corporateactionsjava.core.domain.PricePoint;
import com.github.guoyyit.corporateactionsjava.core.domain.RawPrice;
import com.github.guoyyit.corporateactionsjava.core.error.ApiException;
import com.github.guoyyit.corporateactionsjava.core.error.ErrorCode;

/**
 * Pure, deterministic adjustment computation.
 *
 * <p>The same (raw prices, events) inputs always produce bit-identical output, regardless of
 * registration order: events are sorted by effective date first, then by event type. Both
 * incremental queries and full replays go through this single code path.
 */
@Component
public class AdjustmentEngine {

    public static final int FACTOR_SCALE = 10;
    public static final int PRICE_SCALE = 2;

    private static final Comparator<CorporateEvent> EVENT_ORDER =
            Comparator.comparing(CorporateEvent::effectiveDate)
                    .thenComparing(event -> event.type().ordinal());

    public List<PricePoint> compute(String symbol,
                                    List<RawPrice> rawPrices,
                                    List<CorporateEvent> events) {
        List<RawPrice> sortedPrices = rawPrices.stream()
                .sorted(Comparator.comparing(RawPrice::date))
                .toList();
        List<CorporateEvent> sortedEvents = events.stream()
                .sorted(EVENT_ORDER)
                .toList();

        Map<LocalDate, BigDecimal> closeByDate = new LinkedHashMap<>();
        for (RawPrice rawPrice : sortedPrices) {
            closeByDate.put(rawPrice.date(), normalizeInput(rawPrice.close()));
        }

        Map<LocalDate, BigDecimal> factorByDate = computeBackwardFactors(sortedPrices, sortedEvents, closeByDate);

        BigDecimal latestFactor = sortedPrices.isEmpty()
                ? BigDecimal.ONE
                : factorByDate.get(sortedPrices.get(sortedPrices.size() - 1).date());

        List<PricePoint> points = new ArrayList<>(sortedPrices.size());
        for (RawPrice rawPrice : sortedPrices) {
            BigDecimal raw = closeByDate.get(rawPrice.date());
            BigDecimal backwardFactor = factorByDate.get(rawPrice.date());
            BigDecimal forwardFactor = backwardFactor.divide(latestFactor, FACTOR_SCALE, RoundingMode.HALF_UP);
            points.add(new PricePoint(
                    rawPrice.date(),
                    raw,
                    BigDecimal.ONE,
                    roundPrice(raw.multiply(backwardFactor)),
                    backwardFactor,
                    roundPrice(raw.multiply(forwardFactor)),
                    forwardFactor));
        }
        return points;
    }

    private Map<LocalDate, BigDecimal> computeBackwardFactors(List<RawPrice> sortedPrices,
                                                              List<CorporateEvent> sortedEvents,
                                                              Map<LocalDate, BigDecimal> closeByDate) {
        Map<LocalDate, BigDecimal> factorByDate = new LinkedHashMap<>();
        BigDecimal factor = BigDecimal.ONE;
        int priceIndex = 0;
        int eventIndex = 0;

        while (priceIndex < sortedPrices.size() || eventIndex < sortedEvents.size()) {
            LocalDate nextPriceDate = priceIndex < sortedPrices.size()
                    ? sortedPrices.get(priceIndex).date() : null;
            LocalDate nextEventDate = eventIndex < sortedEvents.size()
                    ? sortedEvents.get(eventIndex).effectiveDate() : null;

            LocalDate stepDate;
            boolean eventStep;
            if (nextPriceDate == null) {
                stepDate = nextEventDate;
                eventStep = true;
            } else if (nextEventDate == null) {
                stepDate = nextPriceDate;
                eventStep = false;
            } else if (!nextEventDate.isAfter(nextPriceDate)) {
                stepDate = nextEventDate;
                eventStep = true;
            } else {
                stepDate = nextPriceDate;
                eventStep = false;
            }

            if (eventStep) {
                BigDecimal referenceClose = latestCloseBefore(closeByDate, stepDate);
                while (eventIndex < sortedEvents.size()
                        && sortedEvents.get(eventIndex).effectiveDate().equals(stepDate)) {
                    CorporateEvent event = sortedEvents.get(eventIndex);
                    factor = factor.multiply(event.params().multiplier(referenceClose))
                            .setScale(FACTOR_SCALE, RoundingMode.HALF_UP);
                    eventIndex++;
                }
            } else {
                factorByDate.put(stepDate, factor);
                priceIndex++;
            }
        }
        return factorByDate;
    }

    private BigDecimal latestCloseBefore(Map<LocalDate, BigDecimal> closeByDate, LocalDate date) {
        BigDecimal found = null;
        for (Map.Entry<LocalDate, BigDecimal> entry : closeByDate.entrySet()) {
            if (entry.getKey().isBefore(date)) {
                found = entry.getValue();
            } else {
                break;
            }
        }
        if (found == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "no raw close price before effective date " + date
                            + "; ingest the pre-event price before registering this event");
        }
        return found;
    }

    private BigDecimal normalizeInput(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "raw close price must be positive, got " + value);
        }
        return value.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal roundPrice(BigDecimal value) {
        return value.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }
}
