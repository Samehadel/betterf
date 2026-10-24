package com.theeverthread.knowledge.internal.service;

import com.theeverthread.knowledge.api.dto.TicketContent;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClassificationTransactions {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public record Work(UUID id, UUID token, TicketContent content) {}

    /** Durable leases recover abandoned work; token checks fence late worker responses. */
    @Transactional
    public Work claim() {
        jdbc.update(
                "UPDATE KNOWLEDGE_TICKET SET CLASSIFICATION_STATUS='FAILED', LEASE_TOKEN=NULL,"
                    + " LEASE_UNTIL=NULL WHERE CLASSIFICATION_STATUS='PROCESSING' AND LEASE_UNTIL <"
                    + " CURRENT_TIMESTAMP AND ATTEMPTS >= 3");
        var rows =
                jdbc.query(
                        """
                        WITH candidate AS (
                            SELECT ID FROM KNOWLEDGE_TICKET
                            WHERE ATTEMPTS < 3 AND (
                                (CLASSIFICATION_STATUS IN ('PENDING', 'RETRYING') AND NEXT_ATTEMPT_AT <= CURRENT_TIMESTAMP)
                                OR (CLASSIFICATION_STATUS='PROCESSING' AND LEASE_UNTIL < CURRENT_TIMESTAMP))
                            ORDER BY NEXT_ATTEMPT_AT, ID LIMIT 1 FOR UPDATE SKIP LOCKED
                        )
                        UPDATE KNOWLEDGE_TICKET t SET CLASSIFICATION_STATUS='PROCESSING', ATTEMPTS=t.ATTEMPTS+1,
                            LEASE_TOKEN=?, LEASE_UNTIL=CURRENT_TIMESTAMP + INTERVAL '2 minutes'
                        FROM candidate c WHERE t.ID=c.ID RETURNING t.ID, t.LEASE_TOKEN, t.CONTENT
                        """,
                        (rs, row) ->
                                new Work(
                                        rs.getObject(1, UUID.class),
                                        rs.getObject(2, UUID.class),
                                        json.readValue(rs.getString(3), TicketContent.class)),
                        UUID.randomUUID());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public void complete(Work work, List<String> attributes) {
        var valid = AttributeCatalog.validate(attributes);
        jdbc.update(
                "UPDATE KNOWLEDGE_TICKET SET ATTRIBUTES=?, CLASSIFICATION_STATUS='COMPLETE',"
                        + " LEASE_TOKEN=NULL, LEASE_UNTIL=NULL WHERE ID=? AND LEASE_TOKEN=?",
                json.writeValueAsString(valid),
                work.id(),
                work.token());
    }

    @Transactional
    public void fail(Work work) {
        jdbc.update(
                """
                UPDATE KNOWLEDGE_TICKET SET CLASSIFICATION_STATUS=CASE WHEN ATTEMPTS >= 3 THEN 'FAILED' ELSE 'RETRYING' END,
                    NEXT_ATTEMPT_AT=CURRENT_TIMESTAMP + ATTEMPTS * INTERVAL '30 seconds', LEASE_TOKEN=NULL, LEASE_UNTIL=NULL
                WHERE ID=? AND LEASE_TOKEN=?
                """,
                work.id(),
                work.token());
    }
}
