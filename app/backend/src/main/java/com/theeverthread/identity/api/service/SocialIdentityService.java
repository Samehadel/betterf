package com.theeverthread.identity.api.service;

import com.theeverthread.identity.api.dto.IdentityViews.AccountView;
import com.theeverthread.identity.api.dto.SocialIdentityView;

public interface SocialIdentityService {
    AccountView authenticate(SocialIdentityView identity);

    AccountView link(String authenticatedEmail, SocialIdentityView identity);
}
