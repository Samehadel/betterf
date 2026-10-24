package com.theeverthread.identity.internal.service;

public interface InvitationMail {
    String send(InvitationTransactions.Claim invitation, String token);
}
