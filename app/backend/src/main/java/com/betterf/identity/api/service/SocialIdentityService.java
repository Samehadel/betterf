package com.betterf.identity.api.service;

import com.betterf.identity.api.dto.IdentityViews.AccountView;
import com.betterf.identity.api.dto.SocialIdentityView;

public interface SocialIdentityService {
    AccountView authenticate(SocialIdentityView identity);

    AccountView link(String authenticatedEmail, SocialIdentityView identity);
}
