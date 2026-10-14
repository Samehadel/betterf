package com.betterf.identity.internal.service;

import com.betterf.identity.api.dto.*;
import com.betterf.identity.api.exception.EmailDeliveryException;
import com.betterf.identity.api.service.InvitationService;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

@Service
@lombok.RequiredArgsConstructor
public class InvitationServiceImpl implements InvitationService {
    private final InvitationTransactions transactions;
    private final InvitationMail mail;
    private final SecureRandom random = new SecureRandom();

    @Override
    public InvitationPageView history(String actor, int page) {
        return transactions.history(actor, page);
    }

    public java.util.List<InvitationView> sent(String actor) {
        return transactions.sent(actor);
    }

    public java.util.List<ActiveMemberView> members(String actor) {
        return transactions.members(actor);
    }

    public InvitationView resend(String actor, java.util.UUID id) {
        String token = token();
        var claim = transactions.claimResend(actor, id, token);
        try {
            String messageId = mail.send(claim, token);
            return transactions.completeResend(id, token, messageId, null);
        } catch (EmailDeliveryException failure) {
            return transactions.completeResend(id, token, null, failure.failureCode());
        }
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public InvitationView send(String actor, InvitationRequest request) {
        String token = token();
        var claim = transactions.claim(actor, request, token);
        if (claim.companyName() == null) {
            return claim.view();
        }
        String messageId;
        try {
            messageId = mail.send(claim, token);
        } catch (EmailDeliveryException failure) {
            return transactions.complete(claim.view().id(), token, null, failure.failureCode());
        }
        return transactions.complete(claim.view().id(), token, messageId, null);
    }

    public InvitationView status(String actor, InvitationRequest request) {
        return transactions.status(actor, request);
    }
}
