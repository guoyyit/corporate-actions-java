package com.github.guoyyit.corporateactionsjava.service;

import com.github.guoyyit.corporateactionsjava.domain.ActionParams;
import com.github.guoyyit.corporateactionsjava.domain.AdjustedPoint;
import com.github.guoyyit.corporateactionsjava.domain.CorporateAction;
import com.github.guoyyit.corporateactionsjava.domain.PriceView;
import com.github.guoyyit.corporateactionsjava.domain.RawPrice;
import com.github.guoyyit.corporateactionsjava.dto.ActionResponse;
import com.github.guoyyit.corporateactionsjava.dto.IngestPricesRequest;
import com.github.guoyyit.corporateactionsjava.dto.PricePointRequest;
import com.github.guoyyit.corporateactionsjava.dto.PriceSeriesResponse;
import com.github.guoyyit.corporateactionsjava.dto.RegisterActionRequest;
import com.github.guoyyit.corporateactionsjava.error.ApiException;
import com.github.guoyyit.corporateactionsjava.store.JsonStateStore;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class CorporateActionService {

    private final JsonStateStore store;
    private final AdjustmentEngine engine;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private List<CorporateAction> actions = new ArrayList<>();
    private List<RawPrice> prices = new ArrayList<>();

    private final BigDecimal tick;

    public CorporateActionService(JsonStateStore store,
                                  AdjustmentEngine engine,
                                  @Value("${corporate-actions.tick:0.01}") String tick) {
        this.store = store;
        this.engine = engine;
        this.tick = new BigDecimal(tick);
    }

    @PostConstruct
    public void restore() {
        JsonStateStore.PersistedState state = store.load();
        this.actions = new ArrayList<>(state.actions());
        this.prices = new ArrayList<>(state.prices());
    }

    public ActionResponse register(RegisterActionRequest request) {
        validate(request);
        String id = UUID.randomUUID().toString();
        CorporateAction candidate = new CorporateAction(
                id, request.symbol().trim(), request.effectiveDate(), request.params());

        lock.writeLock().lock();
        try {
            guardDuplicateOrConflict(candidate, null);
            actions.add(candidate);
            persist();
        } finally {
            lock.writeLock().unlock();
        }
        return toResponse(candidate);
    }

    public ActionResponse modify(String id, RegisterActionRequest request) {
        validate(request);
        lock.writeLock().lock();
        try {
            int index = findIndex(id);
            CorporateAction existing = actions.get(index);
            CorporateAction candidate = new CorporateAction(
                    existing.id(), request.symbol().trim(), request.effectiveDate(), request.params());
            guardDuplicateOrConflict(candidate, existing.id());
            actions.set(index, candidate);
            persist();
            return toResponse(candidate);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void revoke(String id) {
        lock.writeLock().lock();
        try {
            int index = findIndex(id);
            actions.remove(index);
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<ActionResponse> list(String symbol) {
        lock.readLock().lock();
        try {
            return actions.stream()
                    .filter(action -> symbol == null || symbol.isBlank()
                            || action.symbol().equals(symbol.trim()))
                    .sorted(Comparator.comparing(CorporateAction::symbol)
                            .thenComparing(CorporateAction::effectiveDate)
                            .thenComparing(CorporateAction::id))
                    .map(CorporateActionService::toResponse)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void ingestPrices(IngestPricesRequest request) {
        if (request == null || request.symbol() == null || request.symbol().isBlank()) {
            throw ApiException.invalidParam("标的代码 symbol 不能为空");
        }
        if (request.points() == null || request.points().isEmpty()) {
            throw ApiException.invalidParam("价格点 points 不能为空");
        }
        String symbol = request.symbol().trim();
        List<RawPrice> newPrices = new ArrayList<>();
        for (PricePointRequest point : request.points()) {
            if (point.date() == null) {
                throw ApiException.invalidParam("价格日期不能为空");
            }
            if (point.close() == null || point.close().signum() <= 0) {
                throw ApiException.invalidParam(
                        "日期 " + point.date() + " 的收盘价必须为正数");
            }
            newPrices.add(new RawPrice(symbol, point.date(), point.close()));
        }
        lock.writeLock().lock();
        try {
            List<RawPrice> merged = new ArrayList<>(prices);
            for (RawPrice incoming : newPrices) {
                merged.removeIf(existing ->
                        existing.symbol().equals(incoming.symbol())
                                && existing.date().equals(incoming.date()));
                merged.add(incoming);
            }
            this.prices = merged;
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public PriceSeriesResponse query(String symbol, PriceView view, LocalDate from, LocalDate to) {
        if (symbol == null || symbol.isBlank()) {
            throw ApiException.invalidParam("标的代码 symbol 不能为空");
        }
        String normalized = symbol.trim();
        lock.readLock().lock();
        try {
            List<RawPrice> symbolPrices = prices.stream()
                    .filter(price -> price.symbol().equals(normalized))
                    .toList();
            List<CorporateAction> symbolActions = actions.stream()
                    .filter(action -> action.symbol().equals(normalized))
                    .toList();

            List<AdjustedPoint> points;
            try {
                points = engine.compute(normalized, symbolPrices, symbolActions, tick);
            } catch (IllegalStateException ex) {
                throw ApiException.invalidParam(ex.getMessage());
            }
            List<AdjustedPoint> filtered = points.stream()
                    .filter(point -> from == null || !point.date().isBefore(from))
                    .filter(point -> to == null || !point.date().isAfter(to))
                    .toList();
            return new PriceSeriesResponse(normalized, view, tick, BigDecimal.ONE, filtered);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void validate(RegisterActionRequest request) {
        if (request == null) {
            throw ApiException.invalidParam("请求体不能为空");
        }
        if (request.symbol() == null || request.symbol().isBlank()) {
            throw ApiException.invalidParam("标的代码 symbol 不能为空");
        }
        if (request.effectiveDate() == null) {
            throw ApiException.invalidParam("生效日期 effectiveDate 不能为空");
        }
        ActionParams params = request.params();
        if (params == null) {
            throw ApiException.invalidParam(
                    "事件参数 params 不能为空，需以 kind 字段标识 SPLIT/MERGE/DIVIDEND/RIGHTS_ISSUE");
        }
        if (params instanceof ActionParams.SplitParams split) {
            requirePositive(split.ratio(), "拆股比例 ratio");
            if (split.ratio().compareTo(BigDecimal.ONE) <= 0) {
                throw ApiException.invalidParam("拆股比例 ratio 必须大于 1（例如 10 送 10 填 2）");
            }
        } else if (params instanceof ActionParams.MergeParams merge) {
            requirePositive(merge.ratio(), "合并比例 ratio");
            if (merge.ratio().compareTo(BigDecimal.ONE) >= 0) {
                throw ApiException.invalidParam("合并比例 ratio 必须小于 1 且大于 0（例如 10 合 1 填 0.1）");
            }
        } else if (params instanceof ActionParams.DividendParams dividend) {
            requirePositive(dividend.perShare(), "每股红利 perShare");
        } else if (params instanceof ActionParams.RightsIssueParams rights) {
            requirePositive(rights.ratio(), "配股比例 ratio");
            requirePositive(rights.subscriptionPrice(), "配股价 subscriptionPrice");
        } else {
            throw ApiException.invalidParam("未知事件参数类型: " + params.getClass().getSimpleName());
        }
    }

    private static void requirePositive(BigDecimal value, String field) {
        if (value == null) {
            throw ApiException.invalidParam(field + " 不能为空");
        }
        if (value.signum() <= 0) {
            throw ApiException.invalidParam(field + " 必须为正数，实际为 " + value);
        }
    }

    private void guardDuplicateOrConflict(CorporateAction candidate, String ignoreId) {
        Optional<CorporateAction> collision = actions.stream()
                .filter(action -> !action.id().equals(ignoreId))
                .filter(action -> action.symbol().equals(candidate.symbol()))
                .filter(action -> action.effectiveDate().equals(candidate.effectiveDate()))
                .findFirst();
        if (collision.isEmpty()) {
            return;
        }
        CorporateAction other = collision.get();
        if (other.type() == candidate.type() && other.params().equals(candidate.params())) {
            throw ApiException.duplicate(String.format(
                    "重复事件：标的 %s 在 %s 已存在相同的 %s 事件（事件ID %s）",
                    candidate.symbol(), candidate.effectiveDate(),
                    candidate.type(), other.id()));
        }
        throw ApiException.conflict(String.format(
                "冲突事件：标的 %s 在 %s 已存在 %s 事件（事件ID %s），"
                        + "同一标的同一生效日只允许一个公司行为",
                candidate.symbol(), candidate.effectiveDate(),
                other.type(), other.id()));
    }

    private int findIndex(String id) {
        for (int index = 0; index < actions.size(); index++) {
            if (actions.get(index).id().equals(id)) {
                return index;
            }
        }
        throw ApiException.notFound("事件不存在: " + id);
    }

    private void persist() {
        store.save(new JsonStateStore.PersistedState(
                List.copyOf(actions), List.copyOf(prices)));
    }

    private static ActionResponse toResponse(CorporateAction action) {
        return new ActionResponse(
                action.id(),
                action.symbol(),
                action.effectiveDate(),
                action.type().name(),
                action.params());
    }
}
