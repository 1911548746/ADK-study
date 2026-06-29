package com.hh.examples.study12.productionBestPractices;

public class ProductionAgentException extends RuntimeException {

    private final String requestId;
    private final String errorCode;

    public ProductionAgentException(String requestId, String errorCode, Throwable cause) {
        super(cause.getMessage(), cause);
        this.requestId = requestId;
        this.errorCode = errorCode;
    }

    public String requestId() {
        return requestId;
    }

    public String errorCode() {
        return errorCode;
    }
}
