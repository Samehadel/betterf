package com.betterf.identity.api.service;
import com.betterf.identity.api.dto.*;
public interface InvitationService {
    InvitationView send(String administratorEmail, InvitationRequest request);
    InvitationView status(String administratorEmail, InvitationRequest request);
}
