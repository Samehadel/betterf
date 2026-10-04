package com.betterf.identity.api.exception;

public class IdentityException extends RuntimeException {
    private final String code;
    private final int status;

    public IdentityException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
