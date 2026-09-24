package com.betterf.foundation.internal.http;

public record ApiEnvelope<T>(T data, ApiError error) {
    public record ApiError(String code, String message) {}
    public static <T> ApiEnvelope<T> success(T data) { return new ApiEnvelope<>(data, null); }
    public static ApiEnvelope<Void> failure(String code, String message) {
        return new ApiEnvelope<>(null, new ApiError(code, message));
    }
}
