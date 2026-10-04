package com.betterf.identity.internal.mapper;

import com.betterf.identity.api.dto.IdentityViews.AccountView;
import com.betterf.identity.internal.entity.AccountEntity;

import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface AccountMapper {
    @Mapping(target = "organizationId", source = "organization.id")
    @Mapping(target = "organizationName", source = "organization.name")
    AccountView view(AccountEntity account);
}
