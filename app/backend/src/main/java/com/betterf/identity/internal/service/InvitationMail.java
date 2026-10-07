package com.betterf.identity.internal.service;
public interface InvitationMail {
    String send(InvitationTransactions.Claim invitation, String token);
}
