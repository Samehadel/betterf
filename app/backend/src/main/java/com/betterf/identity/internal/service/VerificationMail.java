package com.betterf.identity.internal.service;

import java.util.UUID;

public interface VerificationMail {
    void send(String email, UUID id, String token);
}
