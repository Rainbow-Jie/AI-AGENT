package com.goocidata.agent.common;

/**
 * 业务异常
 *
 * @author nzj
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        super(message);
        this.code = 500;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException of(String message) {
        return new BizException(message);
    }

    public int getCode() {
        return code;
    }
}
