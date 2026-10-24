package com.theeverthread.knowledge.api.exception;

public class KnowledgeException extends RuntimeException {
    private final int status;
    private final String code;

    public KnowledgeException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
