package com.betterf.identity.internal.mapper;

import com.betterf.identity.api.dto.ActiveMemberView;
import com.betterf.identity.api.dto.IdentityViews.AccountView;
import com.betterf.identity.internal.entity.AccountEntity;
import com.betterf.identity.internal.service.ProfessionalRoles;

import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface AccountMapper {
    @Mapping(target = "organizationId", source = "organization.id")
    @Mapping(target = "organizationName", source = "organization.name")
    AccountView view(AccountEntity account);

    @Mapping(
            target = "professionalRole",
            source = "professionalRole",
            qualifiedByName = "roleLabel")
    ActiveMemberView member(AccountEntity account);

    @Named("roleLabel")
    default String roleLabel(String id) {
        return ProfessionalRoles.ALL.stream()
                .filter(role -> role.id().equals(id))
                .map(role -> role.label())
                .findFirst()
                .orElse(id);
    }
}
