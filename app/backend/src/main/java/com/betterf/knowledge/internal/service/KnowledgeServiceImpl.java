package com.betterf.knowledge.internal.service;

import com.betterf.identity.api.service.IdentityService;
import com.betterf.knowledge.api.dto.*;
import com.betterf.knowledge.api.exception.KnowledgeException;
import com.betterf.knowledge.api.service.KnowledgeService;
import com.betterf.knowledge.internal.entity.TicketEntity;
import com.betterf.knowledge.internal.mapper.TicketMapper;
import com.betterf.knowledge.internal.repository.TicketRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl implements KnowledgeService {
    private final IdentityService identity;
    private final TicketRepository tickets;
    private final TicketMapper mapper;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final KnowledgeAi ai;

    @Override
    @Transactional
    public TicketView create(String email, CreateTicketRequest request) {
        var account = identity.current(email);
        if (request == null || request.submissionId() == null)
            TicketRules.invalid("A submission ID is required.");
        TicketRules.validate(request.content());
        // Serialize simultaneous retries for this author/key; the unique constraint is a second
        // guard.
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                Object.class,
                account.id() + ":" + request.submissionId());
        var existing = tickets.findByAuthorIdAndSubmissionId(account.id(), request.submissionId());
        if (existing.isPresent()) {
            if (!mapper.content(existing.get().getContent()).equals(request.content()))
                throw new KnowledgeException(
                        409,
                        "SUBMISSION_CONFLICT",
                        "This submission was already published with different content. Open the"
                                + " published ticket or start a new capture.");
            return mapper.view(existing.get());
        }
        var ticket = new TicketEntity();
        ticket.setId(UUID.randomUUID());
        ticket.setAuthorId(account.id());
        ticket.setOrganizationId(account.organizationId());
        ticket.setAuthorName(account.fullName());
        ticket.setSubmissionId(request.submissionId());
        ticket.setContent(json.writeValueAsString(request.content()));
        ticket.setCreatedAt(clock.instant());
        ticket.setClassificationStatus("PENDING");
        ticket.setAttributes("[]");
        ticket.setNextAttemptAt(clock.instant());
        return mapper.view(tickets.save(ticket));
    }

    @Override
    @Transactional(readOnly = true)
    public TicketView get(String email, UUID id) {
        var account = identity.current(email);
        return tickets.findByIdAndOrganizationId(id, account.organizationId())
                .map(mapper::view)
                .orElseThrow(
                        () ->
                                new KnowledgeException(
                                        404,
                                        "TICKET_NOT_FOUND",
                                        "This ticket is unavailable in your organization."));
    }

    @Override
    public RefinementView refine(String email, TicketContent content) {
        identity.current(email);
        TicketRules.validate(content);
        try {
            var result = ai.refine(content);
            TicketRules.text(result.title(), 200, "Suggested title");
            TicketRules.text(result.capture(), 1000, "Suggested capture");
            return result;
        } catch (RuntimeException exception) {
            throw new KnowledgeException(
                    503,
                    "REFINEMENT_UNAVAILABLE",
                    "Refinement is unavailable. Your writing is safe; retry or publish as"
                            + " written.");
        }
    }
}
