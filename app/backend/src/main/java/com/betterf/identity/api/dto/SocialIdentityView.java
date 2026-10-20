package com.betterf.identity.api.dto;

/** Provider claims accepted only after the security adapter validates authentication. */
public record SocialIdentityView(
        String provider, String subject, String email, String name, Boolean emailVerified)
        implements java.io.Serializable {}
