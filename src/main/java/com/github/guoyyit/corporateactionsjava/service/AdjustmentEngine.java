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
 * 后复权口径保持除权日之前的价格不变，把除权日（含）之后的价格
 * 调回除权前水平，使序列在除权除息日连续。
 *
 * <p>单个事件的日因子：
 * <ul>
 *   <li>拆股 {@code ratio = newShares / oldShares}（&gt;1）：r = ratio</li>
 *   <li>合并（缩股）{@code ratio = newShares / oldShares}（&lt;1）：r = ratio</li>
 *   <li>分红：r = P / (P - d)，P 为生效日前最后一个交易日的原始收盘价，d 为每股红利。
 *       除息参考价为 P - d，当生效日原始收盘价等于 P - d 时，
 *       后复权价 = (P - d) * r = P，与前收盘完全连续</li>
 *   <li>配股：r = P*(1+s) / (P + s*p)，s 为配股比例，p 为配股价。
 *       除权参考价为 (P + s*p) / (1+s)，当生效日原始收盘价等于该参考价时，
 *       后复权价回到 P，与前收盘完全连续</li>
 * </ul>
 * 所有除法统一 {@link RoundingMode#HALF_UP}、固定 34 位精度，因此无论事件如何
 * 分组逐条应用还是整体重放，连乘结果逐位一致。
 *
 * <p>数据不一致时抛出 {@link AdjustmentException}：生效日之前没有任何
 * 原始收盘价，或每股红利大于等于前收盘价（会产生零/负价格）。
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
                            dailyRatio(action, sortedPrices));
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
                                         List<RawPrice> sortedPrices) {
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
            BigDecimal exDividendClose = previousClose.subtract(dividend.perShare());
            if (exDividendClose.signum() <= 0) {
                throw new AdjustmentException(
                        AdjustmentException.CODE_DIVIDEND_EXCEEDS_PREVIOUS_CLOSE,
                        "标的 " + action.symbol() + " 在 " + effectiveDate
                                + " 的每股红利 " + dividend.perShare()
                                + " 大于等于前收盘价 " + previousClose
                                + "，除息后价格将为零或负数，无法复权");
            }
            return previousClose
                    .divide(exDividendClose, MATH_SCALE, RoundingMode.HALF_UP);
        }
        if (params instanceof ActionParams.RightsIssueParams rights) {
            BigDecimal previousClose = previousClose(sortedPrices, effectiveDate);
            BigDecimal ratio = rights.ratio();
            BigDecimal exRightsReference =
                    previousClose.add(ratio.multiply(rights.subscriptionPrice()));
            return previousClose.multiply(BigDecimal.ONE.add(ratio))
                    .divide(exRightsReference, MATH_SCALE, RoundingMode.HALF_UP);
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
                    AdjustmentException.CODE_MISSING_PRECEDING_CLOSE,
                    "生效日 " + effectiveDate + " 之前缺少原始收盘价，无法确定除权基准价");
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
