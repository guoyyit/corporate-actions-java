package com.github.guoyyit.corporateactionsjava.error;

/**
 * 复权计算过程中发现数据不合法时抛出的运行时异常。
 *
 * <p>与参数非法不同，此类异常表示事件参数本身合法，但与当前行情数据叠加后
 * 无法计算出有意义的复权因子（例如生效日之前缺少收盘价、每股红利不小于前收盘价）。
 * 服务层捕获后转换为对应错误码的 400 响应。
 */
public class AdjustmentException extends RuntimeException {

    public static final String INSUFFICIENT_PRICE_HISTORY = "INSUFFICIENT_PRICE_HISTORY";
    public static final String INVALID_DIVIDEND = "INVALID_DIVIDEND";

    private final String code;

    public AdjustmentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
