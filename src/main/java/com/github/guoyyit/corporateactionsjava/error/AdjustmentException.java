package com.github.guoyyit.corporateactionsjava.error;

/**
 * 复权计算过程中发现的「事件与行情数据不一致」错误。
 *
 * <p>与参数校验错误不同，这类错误取决于事件登记与价格录入的组合状态，
 * 后续补录或修正历史价格后可能自动恢复。携带稳定的 {@link #code()}，
 * 便于调用方区分具体场景。
 */
public class AdjustmentException extends RuntimeException {

    /** 生效日之前不存在任何原始收盘价，无法确定除权基准价。 */
    public static final String CODE_MISSING_PRECEDING_CLOSE = "MISSING_PRECEDING_CLOSE";

    /** 每股红利大于等于前收盘价，除权后价格将为零或负数。 */
    public static final String CODE_DIVIDEND_EXCEEDS_PREVIOUS_CLOSE =
            "DIVIDEND_EXCEEDS_PREVIOUS_CLOSE";

    private final String code;

    public AdjustmentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
