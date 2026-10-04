package com.betterf.identity.internal.repository;

import com.betterf.identity.internal.entity.VerificationEmailEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VerificationEmailRepository extends JpaRepository<VerificationEmailEntity, UUID> {}
