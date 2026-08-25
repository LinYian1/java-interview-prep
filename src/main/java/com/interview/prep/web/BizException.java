package com.interview.prep.web;

/**
 * 业务异常：status 直接映射为 HTTP 状态码，message 返回给前端展示。
 */
public class BizException extends RuntimeException {

    private final int status;

    public BizException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static BizException bad(String message) {
        return new BizException(400, message);
    }

    public static BizException conflict(String message) {
        return new BizException(409, message);
    }
}
