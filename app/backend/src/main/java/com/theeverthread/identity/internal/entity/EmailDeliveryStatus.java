package com.theeverthread.identity.internal.entity;

public enum EmailDeliveryStatus {
    PENDING,
    SENDING,
    SMTP_ACCEPTED,
    FAILED,
    CANCELLED
}
