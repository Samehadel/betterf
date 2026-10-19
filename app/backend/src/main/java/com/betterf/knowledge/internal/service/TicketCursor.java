package com.betterf.knowledge.internal.service;

import com.betterf.knowledge.api.exception.KnowledgeException;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Versioned pagination context; tenant authorization is always resolved independently. */
public record TicketCursor(int version, Instant createdAt, UUID id, String detailsType) {
    public static TicketCursor decode(String encoded, String type) {
        if (encoded == null) return null;
        try {
            if (encoded.isBlank() || encoded.length() > 1024 || !encoded.matches("[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException();
            var cursor =
                    JsonMapper.shared()
                            .readValue(Base64.getUrlDecoder().decode(encoded), TicketCursor.class);
            if (cursor.version() != 1
                    || cursor.createdAt() == null
                    || cursor.id() == null
                    || cursor.detailsType() == null
                    || !cursor.detailsType().equals(type)
                    || cursor.createdAt().isBefore(Instant.EPOCH)
                    || cursor.createdAt().isAfter(Instant.parse("9999-12-31T23:59:59Z")))
                throw new IllegalArgumentException();
            return cursor;
        } catch (RuntimeException exception) {
            throw new KnowledgeException(
                    400, "INVALID_CURSOR", "Invalid pagination cursor for this Details type.");
        }
    }

    public String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(JsonMapper.shared().writeValueAsBytes(this));
    }
}
