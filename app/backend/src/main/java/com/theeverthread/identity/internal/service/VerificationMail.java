package com.theeverthread.identity.internal.service;

import java.util.UUID;

public interface VerificationMail {
    String send(String email, UUID id, String token);
}
