package com.betterf.identity.internal.repository;

import com.betterf.identity.internal.entity.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, UUID> {}
