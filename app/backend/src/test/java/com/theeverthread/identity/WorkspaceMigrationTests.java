package com.theeverthread.identity;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;

/**
 * Verifies the upgrade using historical schema and real legacy rows, not only an empty database.
 */
@Testcontainers
class WorkspaceMigrationTests {
    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.6-alpine");

    @Test
    void preservesLegacyAccountsTicketsAndInvitationsAndCreatesIndependentDefaults()
            throws Exception {
        try (var connection =
                        DriverManager.getConnection(
                                database.getJdbcUrl(),
                                database.getUsername(),
                                database.getPassword());
                var statement = connection.createStatement()) {
            for (String file :
                    new String[] {
                        "identity/20261004-001-registration.sql",
                                "identity/20261004-002-email-worker.sql",
                        "identity/20261007-001-invitations.sql",
                                "identity/20261008-001-invitation-acceptance.sql",
                        "identity/20261015-001-invitation-resend.sql",
                                "knowledge/20261018-001-tickets.sql",
                        "knowledge/20261019-001-browsing.sql",
                                "identity/20261021-001-individuals.sql",
                        "knowledge/20261021-001-personal-tickets.sql"
                    }) apply(statement, file);
            UUID shared = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID solo = UUID.randomUUID();
            statement.execute(
                    "INSERT INTO ORGANIZATION(ID, NAME, DOMAIN, SPECIALIZATION, STATUS) VALUES ('"
                            + shared
                            + "', 'Existing knowledge', 'example.com', 'Old metadata', 'ACTIVE')");
            legacyAccount(statement, owner, shared, "ADMIN", "ORGANIZATION");
            legacyAccount(statement, member, shared, "MEMBER", "ORGANIZATION");
            legacyAccount(statement, solo, null, "INDIVIDUAL", "INDIVIDUAL");
            UUID ticket = UUID.randomUUID();
            UUID personal = UUID.randomUUID();
            ticket(statement, ticket, owner, shared);
            ticket(statement, personal, solo, null);
            statement.execute(
                    "INSERT INTO OAUTH_IDENTITY(ID, ACCOUNT_ID, PROVIDER, SUBJECT_ID) VALUES ('"
                            + UUID.randomUUID()
                            + "', '"
                            + solo
                            + "', 'linkedin', 'preserved-subject')");
            UUID invitation = UUID.randomUUID();
            statement.execute(
                    "INSERT INTO TEAM_INVITATION(ID, ORGANIZATION_ID, EMAIL, ADMINISTRATOR_NAME,"
                        + " ADMINISTRATOR_ID, STATUS, TOKEN_HASH, EXPIRES_AT, ATTEMPTED_AT) VALUES"
                        + " ('"
                            + invitation
                            + "', '"
                            + shared
                            + "', 'invitee@example.com', 'Preserved Name', '"
                            + owner
                            + "', 'SENT', 'preserved-token-hash', CURRENT_TIMESTAMP + INTERVAL '7"
                            + " days', CURRENT_TIMESTAMP)");
            apply(statement, "identity/20261021-004-workspaces.sql");
            assertThat(
                            value(
                                    statement,
                                    "SELECT WORKSPACE_ID FROM TEAM_INVITATION WHERE ID='"
                                            + invitation
                                            + "'"))
                    .isEqualTo(shared.toString());
            assertThat(
                            value(
                                    statement,
                                    "SELECT TOKEN_HASH FROM TEAM_INVITATION WHERE ID='"
                                            + invitation
                                            + "'"))
                    .isEqualTo("preserved-token-hash");
            assertThat(value(statement, "SELECT COUNT(*) FROM ACCOUNT")).isEqualTo("3");
            assertThat(value(statement, "SELECT COUNT(*) FROM WORKSPACE_MEMBERSHIP"))
                    .isEqualTo("4");
            assertThat(value(statement, "SELECT COUNT(*) FROM WORKSPACE")).isEqualTo("3");
            assertThat(
                            value(
                                    statement,
                                    "SELECT DEFAULT_WORKSPACE_ID FROM ACCOUNT WHERE ID='"
                                            + owner
                                            + "'"))
                    .isEqualTo(shared.toString());
            assertThat(
                            value(
                                    statement,
                                    "SELECT DEFAULT_WORKSPACE_ID FROM ACCOUNT WHERE ID='"
                                            + member
                                            + "'"))
                    .isEqualTo(member.toString());
            assertThat(
                            value(
                                    statement,
                                    "SELECT WORKSPACE_ID FROM KNOWLEDGE_TICKET WHERE ID='"
                                            + personal
                                            + "'"))
                    .isEqualTo(solo.toString());
            assertThat(
                            value(
                                    statement,
                                    "SELECT AUTHOR_ID FROM KNOWLEDGE_TICKET WHERE ID='"
                                            + ticket
                                            + "'"))
                    .isEqualTo(owner.toString());
            assertThat(
                            value(
                                    statement,
                                    "SELECT PASSWORD_HASH FROM ACCOUNT WHERE ID='" + solo + "'"))
                    .isEqualTo("unchanged-hash");
            assertThat(value(statement, "SELECT SUBJECT_ID FROM OAUTH_IDENTITY"))
                    .isEqualTo("preserved-subject");
            assertThatThrownBy(
                            () ->
                                    statement.execute(
                                            "UPDATE KNOWLEDGE_TICKET SET WORKSPACE_ID='"
                                                    + shared
                                                    + "' WHERE ID='"
                                                    + personal
                                                    + "'"))
                    .isInstanceOf(SQLException.class);
        }
    }

    private void legacyAccount(
            Statement statement, UUID id, UUID workspace, String role, String type)
            throws SQLException {
        statement.execute(
                "INSERT INTO ACCOUNT(ID, EMAIL, FULL_NAME, PASSWORD_HASH, PROFESSIONAL_ROLE,"
                    + " STATUS, ORGANIZATION_ID, ACCESS_ROLE, ACCOUNT_TYPE, UPDATED_AT) VALUES ('"
                        + id
                        + "', '"
                        + id
                        + "@example.com', 'Preserved Name', 'unchanged-hash', 'OTHER', 'ACTIVE', "
                        + literal(workspace)
                        + ", '"
                        + role
                        + "', '"
                        + type
                        + "', CURRENT_TIMESTAMP)");
    }

    private void ticket(Statement statement, UUID id, UUID author, UUID workspace)
            throws SQLException {
        statement.execute(
                "INSERT INTO KNOWLEDGE_TICKET(ID, AUTHOR_ID, ORGANIZATION_ID, AUTHOR_NAME,"
                    + " SUBMISSION_ID, CONTENT, CREATED_AT, CLASSIFICATION_STATUS, NEXT_ATTEMPT_AT)"
                    + " VALUES ('"
                        + id
                        + "', '"
                        + author
                        + "', "
                        + literal(workspace)
                        + ", 'Preserved Name', '"
                        + UUID.randomUUID()
                        + "', '{\"title\":\"Preserved content\"}', CURRENT_TIMESTAMP, 'PENDING',"
                        + " CURRENT_TIMESTAMP)");
    }

    private String literal(UUID id) {
        return id == null ? "NULL" : "'" + id + "'";
    }

    private String value(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private void apply(Statement statement, String path) throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/" + path)) {
            statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
