package com.theeverthread.knowledge.internal.repository;

import com.theeverthread.knowledge.api.dto.TicketSummary;
import com.theeverthread.knowledge.internal.service.TicketCursor;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class TicketSummaryRepository {
    private final JdbcTemplate jdbc;

    /** Selects only summary fields and applies tenant/type constraints before the keyset limit. */
    public List<TicketSummary> browse(
            UUID workspace, UUID accountId, String type, TicketCursor cursor, int limit) {
        var sql =
                new StringBuilder(
                        """
                        SELECT ID, CONTENT::jsonb->>'title' AS TITLE,
                               LEFT(CONTENT::jsonb->>'capture', 280) AS CAPTURE_PREVIEW,
                               CONTENT::jsonb->>'template' AS DETAILS_TYPE,
                               AUTHOR_NAME, CREATED_AT, CLASSIFICATION_STATUS, ATTRIBUTES
                        FROM KNOWLEDGE_TICKET WHERE
                        """);
        var parameters = new ArrayList<Object>();
        sql.append(" WORKSPACE_ID = ?");
        parameters.add(workspace);
        if (!type.isEmpty()) {
            sql.append(" AND CONTENT::jsonb->>'template' = ?");
            parameters.add(type);
        }
        if (cursor != null) {
            sql.append(" AND (CREATED_AT, ID) < (?, ?)");
            parameters.add(Timestamp.from(cursor.createdAt()));
            parameters.add(cursor.id());
        }
        sql.append(" ORDER BY CREATED_AT DESC, ID DESC LIMIT ?");
        parameters.add(limit);
        return jdbc.query(
                sql.toString(),
                (row, index) ->
                        new TicketSummary(
                                row.getObject("ID", UUID.class),
                                row.getString("TITLE"),
                                row.getString("CAPTURE_PREVIEW"),
                                row.getString("DETAILS_TYPE"),
                                row.getString("AUTHOR_NAME"),
                                row.getTimestamp("CREATED_AT").toInstant(),
                                row.getString("CLASSIFICATION_STATUS"),
                                List.of(
                                        JsonMapper.shared()
                                                .readValue(
                                                        row.getString("ATTRIBUTES"),
                                                        String[].class))),
                parameters.toArray());
    }
}
