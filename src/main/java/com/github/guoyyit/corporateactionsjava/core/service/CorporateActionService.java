package com.github.guoyyit.corporateactionsjava.core.service;

import com.github.guoyyit.corporateactionsjava.core.domain.CorporateEvent;
import com.github.guoyyit.corporateactionsjava.core.domain.DividendParams;
import com.github.guoyyit.corporateactionsjava.core.domain.EventParams;
import com.github.guoyyit.corporateactionsjava.core.domain.MergeParams;
import com.github.guoyyit.corporateactionsjava.core.domain.PricePoint;
import com.github.guoyyit.corporateactionsjava.core.domain.RightsParams;
import com.github.guoyyit.corporateactionsjava.core.domain.SplitParams;
import com.github.guoyyit.corporateactionsjava.core.engine.AdjustmentEngine;
import com.github.guoyyit.corporateactionsjava.core.error.ApiException;
import com.github.guoyyit.corporateactionsjava.core.error.ErrorCode;
import com.github.guoyyit.corporateactionsjava.core.repo.CorporateEventRepository;
import com.github.guoyyit.corporateactionsjava.core.repo.RawPriceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class CorporateActionService {

    private static final int MAX_SYMBOL_LOCKS = 4096;

    private final CorporateEventRepository eventRepository;
    private final RawPriceRepository rawPriceRepository;
    private final AdjustmentEngine adjustmentEngine;
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> symbolLocks = new ConcurrentHashMap<>();

    public CorporateActionService(CorporateEventRepository eventRepository,
                                  RawPriceRepository rawPriceRepository,
                                  AdjustmentEngine adjustmentEngine) {
        this.eventRepository = eventRepository;
        this.rawPriceRepository = rawPriceRepository;
        this.adjustmentEngine = adjustmentEngine;
    }

    @Transactional
    public CorporateEvent register(CorporateEvent event) {
        validateShape(event);
        event.params().validate();
        ReentrantReadWriteLock lock = lockFor(event.symbol());
        lock.writeLock().lock();
        try {
            eventRepository.findById(event.eventId()).ifPresent(existing -> {
                if (sameBusinessEvent(existing, event)) {
                    throw new ApiException(ErrorCode.DUPLICATE_EVENT,
                            "event already registered with id " + event.eventId());
                }
                throw new ApiException(ErrorCode.CONFLICTING_EVENT,
                        "event id " + event.eventId()
                                + " already exists with different payload; use PUT to amend it");
            });
            checkSameDayConflict(event);
            eventRepository.insert(event);
            return event;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Transactional
    public CorporateEvent amend(CorporateEvent event) {
        validateShape(event);
        event.params().validate();
        CorporateEvent existing = eventRepository.findById(event.eventId())
                .orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND,
                        "event not found: " + event.eventId()));
        String symbol = existing.symbol();
        if (!symbol.equals(event.symbol())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "amending the symbol of an event is not supported; revoke and re-register");
        }
        ReentrantReadWriteLock lock = lockFor(symbol);
        lock.writeLock().lock();
        try {
            List<CorporateEvent> others = eventRepository.findBySymbol(symbol).stream()
                    .filter(other -> !other.eventId().equals(event.eventId()))
                    .toList();
            checkSameDayConflict(event, others);
            eventRepository.update(event);
            return event;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Transactional
    public void revoke(String eventId) {
        CorporateEvent existing = eventRepository.findById(eventId)
                .orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND,
                        "event not found: " + eventId));
        ReentrantReadWriteLock lock = lockFor(existing.symbol());
        lock.writeLock().lock();
        try {
            eventRepository.delete(eventId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<PricePoint> queryPrices(String symbol) {
        ReentrantReadWriteLock lock = lockFor(symbol);
        lock.readLock().lock();
        try {
            return adjustmentEngine.compute(
                    symbol,
                    rawPriceRepository.findBySymbol(symbol),
                    eventRepository.findBySymbol(symbol));
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<CorporateEvent> listEvents(String symbol) {
        ReentrantReadWriteLock lock = lockFor(symbol);
        lock.readLock().lock();
        try {
            return eventRepository.findBySymbol(symbol);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void validateShape(CorporateEvent event) {
        if (event == null || isBlank(event.eventId()) || isBlank(event.symbol())
                || event.effectiveDate() == null || event.type() == null || event.params() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "event requires eventId, symbol, effectiveDate, type and typed params");
        }
        if (event.type() != event.params().type())
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "event type " + event.type() + " does not match params type " + event.params().type());
    }

    private void checkSameDayConflict(CorporateEvent incoming) {
        checkSameDayConflict(incoming, eventRepository.findBySymbol(incoming.symbol()));
    }

    private void checkSameDayConflict(CorporateEvent incoming, List<CorporateEvent> existingEvents) {
        for (CorporateEvent existing : existingEvents) {
            if (existing.effectiveDate().equals(incoming.effectiveDate())
                    && existing.type() == incoming.type()) {
                throw new ApiException(ErrorCode.CONFLICTING_EVENT,
                        "conflicting " + incoming.type()
                                + " event on " + incoming.effectiveDate()
                                + " for symbol " + incoming.symbol()
                                + ": existing event " + existing.eventId()
                                + "; amend or revoke it first");
            }
        }
    }

    private boolean sameBusinessEvent(CorporateEvent a, CorporateEvent b) {
        return Objects.equals(a.symbol(), b.symbol())
                && Objects.equals(a.effectiveDate(), b.effectiveDate())
                && a.type() == b.type()
                && paramsEqual(a.params(), b.params());
    }

    private boolean paramsEqual(EventParams a, EventParams b) {
        if (a instanceof SplitParams split && b instanceof SplitParams other) {
            return split.from() == other.from() && split.to() == other.to();
        }
        if (a instanceof MergeParams merge && b instanceof MergeParams other) {
            return merge.from() == other.from() && merge.to() == other.to();
        }
        if (a instanceof DividendParams dividend && b instanceof DividendParams other) {
            return dividend.dividendPerShare().compareTo(other.dividendPerShare()) == 0;
        }
        if (a instanceof RightsParams rights && b instanceof RightsParams other) {
            return rights.subscriptionPrice().compareTo(other.subscriptionPrice()) == 0
                    && rights.ratio().compareTo(other.ratio()) == 0;
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ReentrantReadWriteLock lockFor(String symbol) {
        ReentrantReadWriteLock lock = symbolLocks.computeIfAbsent(symbol,
                key -> new ReentrantReadWriteLock(true));
        if (symbolLocks.size() > MAX_SYMBOL_LOCKS) {
            symbolLocks.entrySet().removeIf(entry ->
                    !entry.getValue().isWriteLocked()
                            && entry.getValue().getReadLockCount() == 0
                            && entry.getValue().getQueueLength() == 0
                            && !entry.getValue().hasQueuedThreads());
        }
        return lock;
    }
}
