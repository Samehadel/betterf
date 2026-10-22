package com.betterf.identity.internal.mapper;

import com.betterf.identity.api.dto.IdentityViews.*;
import com.betterf.identity.internal.entity.*;

import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AccountMapper {
    default WorkspaceView workspace(MembershipEntity membership) {
        return new WorkspaceView(
                membership.getWorkspace().getId(),
                membership.getWorkspace().getName(),
                membership.getRole());
    }
}
