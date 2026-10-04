package com.betterf.identity.internal.entity;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "ORGANIZATION")
@Getter
@Setter
public class OrganizationEntity {
    @Id private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 253)
    private String domain;

    @Column(nullable = false, length = 200)
    private String specialization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrganizationStatus status;
}
