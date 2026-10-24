package com.theeverthread.knowledge.internal.service;

import com.theeverthread.identity.api.service.IdentityService;
import com.theeverthread.knowledge.api.dto.*;
import com.theeverthread.knowledge.api.exception.KnowledgeException;
import com.theeverthread.knowledge.api.service.KnowledgeService;
import com.theeverthread.knowledge.internal.entity.TicketEntity;
import com.theeverthread.knowledge.internal.mapper.TicketMapper;
import com.theeverthread.knowledge.internal.repository.TicketRepository;
import com.theeverthread.knowledge.internal.repository.TicketSummaryRepository;

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
    private final TicketSummaryRepository summaries;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final KnowledgeAi ai;

    @Override
    @Transactional
    public TicketView create(String email, UUID workspaceId, CreateTicketRequest request) {
        var account = identity.current(email, workspaceId);
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
            if (!existing.get().getWorkspaceId().equals(workspaceId)
                    || !mapper.content(existing.get().getContent()).equals(request.content()))
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
        ticket.setWorkspaceId(account.workspaceId());
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
    public TicketView get(String email, UUID workspaceId, UUID id) {
        var account = identity.current(email, workspaceId);
        return tickets.findByIdAndWorkspaceId(id, account.workspaceId())
                .map(mapper::view)
                .orElseThrow(
                        () ->
                                new KnowledgeException(
                                        404,
                                        "TICKET_NOT_FOUND",
                                        "This ticket is unavailable in your workspace."));
    }

    @Override
    @Transactional(readOnly = true)
    public TicketPage browse(
            String email,
            UUID workspaceId,
            String detailsType,
            Integer limit,
            String encodedCursor) {
        var account = identity.current(email, workspaceId);
        var type = detailsType == null ? "" : detailsType;
        if (detailsType != null && !TicketRules.TEMPLATES.contains(type))
            TicketRules.invalid("Choose a supported Details type.");
        if (limit != null && limit < 1) TicketRules.invalid("Page size must be positive.");
        int size = limit == null ? 20 : Math.min(limit, 50);
        var cursor = TicketCursor.decode(encodedCursor, type);
        var rows = summaries.browse(account.workspaceId(), account.id(), type, cursor, size + 1);
        var items = rows.stream().limit(size).toList();
        String next = null;
        if (rows.size() > size) {
            var last = items.getLast();
            next = new TicketCursor(1, last.createdAt(), last.id(), type).encode();
        }
        return new TicketPage(items, next);
    }

    @Override
    public RefinementView refine(String email, UUID workspaceId, RefinementRequest request) {
        identity.current(email, workspaceId);
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
