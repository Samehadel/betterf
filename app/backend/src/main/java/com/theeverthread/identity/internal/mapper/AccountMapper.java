package com.theeverthread.identity.internal.mapper;

import com.theeverthread.identity.api.dto.IdentityViews.*;
import com.theeverthread.identity.internal.entity.*;

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
