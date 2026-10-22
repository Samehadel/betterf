package com.betterf.foundation.internal.security;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "REFRESH_SESSION")
class RefreshSession {
    @Id UUID id;
    @Column(nullable = false) String email;
    @Column(nullable = false, length = 64) String tokenHash;
    @Column(length = 64) String previousHash;
    @Column(nullable = false, length = 64) String credentialHash;
    @Column(nullable = false) Instant expiresAt;
}
