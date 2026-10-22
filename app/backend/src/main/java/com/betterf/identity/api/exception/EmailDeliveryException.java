package com.betterf.identity.api.exception;

/** A safe diagnostic code for a failed SMTP submission; never exposes provider credentials. */
public class EmailDeliveryException extends IdentityException {
    private final String failureCode;

    public EmailDeliveryException() {
        this("SMTP_DELIVERY_FAILED");
    }

    public EmailDeliveryException(String failureCode) {
        super(
                503,
                "EMAIL_UNAVAILABLE",
                "We could not send the verification email. Automatic retry is scheduled.");
        this.failureCode = failureCode;
    }

    public String failureCode() {
        return failureCode;
    }
}
