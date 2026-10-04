package com.betterf.identity.api.exception;

/**
 * The attempt and FAILED state must commit; the previous verification credential stays unchanged.
 */
public class EmailDeliveryException extends IdentityException {
    public EmailDeliveryException() {
        super(
                503,
                "EMAIL_UNAVAILABLE",
                "We could not send the verification email. Your registration is saved. Request"
                    + " another email below; your previous link, if any, is unchanged.");
    }
}
