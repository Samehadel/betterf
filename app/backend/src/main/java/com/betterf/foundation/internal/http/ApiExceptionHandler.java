package com.betterf.foundation.internal.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiEnvelope<Void>> unavailable(DataAccessException exception) {
        LOG.warn("Database readiness check failed", exception);
        return ResponseEntity.status(503).body(ApiEnvelope.failure("SERVICE_UNAVAILABLE", "The service is temporarily unavailable."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiEnvelope<Void>> handle(Exception exception) {
        int status = exception instanceof ErrorResponse error ? error.getStatusCode().value() : 500;
        String code = switch (status) {
            case 400 -> "INVALID_REQUEST";
            case 403 -> "ACCESS_DENIED";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            default -> status < 500 ? "INVALID_REQUEST" : "INTERNAL_ERROR";
        };
        if (status >= 500) LOG.error("Unhandled request failure", exception);
        return ResponseEntity.status(status).body(ApiEnvelope.failure(code,
            status >= 500 ? "An unexpected error occurred." : "The request could not be processed."));
    }
}
