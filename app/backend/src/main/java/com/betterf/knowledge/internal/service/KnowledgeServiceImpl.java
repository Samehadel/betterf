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
    public RefinementView refine(String email, RefinementRequest request) {
        identity.current(email);
        if (request == null
                || !("title".equals(request.field()) || "capture".equals(request.field()))) {
            TicketRules.invalid("Choose Title or Capture for refinement.");
        }
        TicketRules.validate(request.content());
        String original = RefinementRules.original(request.content(), request.field());
        try {
            var text = ai.refine(original, request.field());
            TicketRules.text(
                    text,
                    RefinementRules.maxCharacters(original, request.field()),
                    "Suggested wording");
            return new RefinementView(request.field(), text);
        } catch (RuntimeException exception) {
            throw new KnowledgeException(
                    503,
                    "REFINEMENT_UNAVAILABLE",
                    "Refinement is unavailable. Your writing is safe; retry or publish as"
                            + " written.");
        }
    }
}
