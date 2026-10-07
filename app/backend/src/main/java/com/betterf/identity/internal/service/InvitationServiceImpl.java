package com.betterf.identity.internal.service;
import com.betterf.identity.api.dto.*;
import com.betterf.identity.api.exception.EmailDeliveryException;
import com.betterf.identity.api.service.InvitationService;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.util.Base64;
@Service
public class InvitationServiceImpl implements InvitationService {
    private final InvitationTransactions transactions;
    private final InvitationMail mail;
    private final SecureRandom random = new SecureRandom();
    public InvitationServiceImpl(InvitationTransactions transactions, InvitationMail mail) {
        this.transactions = transactions; this.mail = mail;
    }
    public InvitationView send(String actor, InvitationRequest request) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var claim = transactions.claim(actor, request, token);
        if (claim.companyName() == null) return claim.view();
        String messageId;
        try { messageId = mail.send(claim, token); }
        catch (EmailDeliveryException failure) {
            return transactions.complete(claim.view().id(), token, null, failure.failureCode());
        }
        return transactions.complete(claim.view().id(), token, messageId, null);
    }
    public InvitationView status(String actor, InvitationRequest request) {
        return transactions.status(actor, request);
    }
}
