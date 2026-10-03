package com.github.guoyyit.corporateactionsjava.service;

import com.github.guoyyit.corporateactionsjava.domain.ActionParams;
import com.github.guoyyit.corporateactionsjava.domain.AdjustedPoint;
import com.github.guoyyit.corporateactionsjava.domain.CorporateAction;
import com.github.guoyyit.corporateactionsjava.domain.RawPrice;
import com.github.guoyyit.corporateactionsjava.error.AdjustmentException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 复权计算引擎（纯函数，无状态）。
 *
 * <p>后复权因子定义：以价格序列首个交易日为基准日（基准因子=1），
 * 公司行为在生效日（除权除息日）当日及之后的因子上生效。
 *
 * <p>后复权因子在事件生效日（除权除息日）当日及之后生效，方向为保持价格序列连续：
 * <ul>
 *   <li>拆股 {@code ratio = newShares / oldShares}（&gt;1）：r = ratio</li>
 *   <li>合并（缩股）{@code ratio = newShares / oldShares}（&lt;1）：r = ratio</li>
 *   <li>分红：r = P / (P - d)，P 为生效日前最后一个交易日的原始收盘价，d 为每股红利。
 *       除权参考价为 {@code P - d}，后复权因子把除权日价格抬回除权前水平。</li>
 *   <li>配股：r = P / ref，其中除权参考价
 *       {@code ref = (P + s*p) / (1 + s)}，s 为配股比例，p 为配股价，
 *       参考价按最小价格单位四舍五入。</li>
 * </ul>
 * 因子方向与前复权自洽：前复权价 = 原始价 × F(t) / F(最新日)，因此分红与配股
 * 生效日之前的历史价格被向下调整，与事件压低价格的经济含义一致。
 *
 * <p>所有除法统一 {@link RoundingMode#HALF_UP}、固定 34 位精度，因此无论事件如何
 * 分组逐条应用还是整体重放，连乘结果逐位一致。
 */
@Component
public class AdjustmentEngine {

    static final int MATH_SCALE = 34;
    static final int FACTOR_SCALE = 12;
    static final int RAW_SCALE = 4;

    public List<AdjustedPoint> compute(String symbol,
                                       List<RawPrice> prices,
                                       List<CorporateAction> actions,
                                       BigDecimal tick) {
        List<RawPrice> sortedPrices = prices.stream()
                .filter(price -> symbol.equals(price.symbol()))
                .sorted(Comparator.comparing(RawPrice::date))
                .toList();
        if (sortedPrices.isEmpty()) {
            return List.of();
        }

        List<CorporateAction> sortedActions = actions.stream()
                .filter(action -> symbol.equals(action.symbol()))
                // 早于首根行情的事件已反映在原始价中，纳入会破坏“首日因子=1”的基准
                .filter(action -> !action.effectiveDate().isBefore(sortedPrices.get(0).date()))
                .sorted(Comparator.comparing(CorporateAction::effectiveDate)
                        .thenComparing(CorporateAction::id))
                .toList();

        // 先计算每个事件生效日的日因子，再按日期与价格合并。
        List<BigDecimal> backwardFactors = new ArrayList<>(sortedPrices.size());
        BigDecimal cumulative = BigDecimal.ONE;
            int actionIndex = 0;
            for (RawPrice price : sortedPrices) {
                LocalDate date = price.date();
                while (actionIndex < sortedActions.size()
                        && !sortedActions.get(actionIndex).effectiveDate().isAfter(date)) {
                    CorporateAction action = sortedActions.get(actionIndex);
                    cumulative = cumulative.multiply(
                            dailyRatio(action, sortedPrices, tick));
                    actionIndex++;
                }
                backwardFactors.add(cumulative);
            }

        BigDecimal latestFactor = cumulative.stripTrailingZeros().compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ONE
                : cumulative;

        List<AdjustedPoint> points = new ArrayList<>(sortedPrices.size());
        for (int index = 0; index < sortedPrices.size(); index++) {
            RawPrice price = sortedPrices.get(index);
            BigDecimal raw = price.close();
            BigDecimal backwardFactor = backwardFactors.get(index)
                    .setScale(FACTOR_SCALE, RoundingMode.HALF_UP);
            BigDecimal backwardExact = raw.multiply(backwardFactors.get(index));
            BigDecimal backward = roundToTick(backwardExact, tick);

            // 前复权：以序列最新交易日为锚，f_forward = f_backward / f_latest
            BigDecimal forwardExact = raw
                    .multiply(backwardFactors.get(index))
                    .divide(latestFactor, MATH_SCALE, RoundingMode.HALF_UP);
            BigDecimal forward = roundToTick(forwardExact, tick);

            points.add(new AdjustedPoint(
                    price.date(),
                    raw.setScale(RAW_SCALE, RoundingMode.HALF_UP),
                    forward,
                    backward,
                    backwardFactor));
        }
        return points;
    }

    private static BigDecimal dailyRatio(CorporateAction action,
                                         List<RawPrice> sortedPrices,
                                         BigDecimal tick) {
        LocalDate effectiveDate = action.effectiveDate();
        ActionParams params = action.params();
        if (params instanceof ActionParams.SplitParams split) {
            return split.ratio();
        }
        if (params instanceof ActionParams.MergeParams merge) {
            return merge.ratio();
        }
        if (params instanceof ActionParams.DividendParams dividend) {
            BigDecimal previousClose = previousClose(sortedPrices, effectiveDate);
            BigDecimal reference = previousClose.subtract(dividend.perShare());
            if (reference.signum() <= 0) {
                throw new AdjustmentException(
                        AdjustmentException.INVALID_DIVIDEND,
                        "分红事件生效日 " + effectiveDate + " 的每股红利 " + dividend.perShare()
                                + " 不小于前收盘价 " + previousClose
                                + "，除权参考价 " + reference + " 非正，无法计算复权因子");
            }
            reference = roundToTick(reference, tick);
            return previousClose.divide(reference, MATH_SCALE, RoundingMode.HALF_UP);
        }
        if (params instanceof ActionParams.RightsIssueParams rights) {
            BigDecimal previousClose = previousClose(sortedPrices, effectiveDate);
            BigDecimal ratio = rights.ratio();
            BigDecimal reference = previousClose
                    .add(ratio.multiply(rights.subscriptionPrice()))
                    .divide(BigDecimal.ONE.add(ratio), MATH_SCALE, RoundingMode.HALF_UP);
            reference = roundToTick(reference, tick);
            return previousClose.divide(reference, MATH_SCALE, RoundingMode.HALF_UP);
        }
        throw new IllegalStateException("未知事件参数类型: " + params);
    }

    private static BigDecimal previousClose(List<RawPrice> sortedPrices, LocalDate effectiveDate) {
        BigDecimal previous = null;
        for (RawPrice price : sortedPrices) {
            if (price.date().isBefore(effectiveDate)) {
                previous = price.close();
            } else {
                break;
            }
        }
        if (previous == null) {
            throw new AdjustmentException(
                    AdjustmentException.INSUFFICIENT_PRICE_HISTORY,
                    "生效日 " + effectiveDate + " 之前不存在任何原始收盘价，无法计算除权因子");
        }
        return previous;
    }

    static BigDecimal roundToTick(BigDecimal value, BigDecimal tick) {
        if (tick == null || tick.signum() <= 0) {
            return value.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal ticks = value.divide(tick, MATH_SCALE, RoundingMode.HALF_UP)
                .setScale(0, RoundingMode.HALF_UP);
        return ticks.multiply(tick).stripTrailingZeros();
    }
}
