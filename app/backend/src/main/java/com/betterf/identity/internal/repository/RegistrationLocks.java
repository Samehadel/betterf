package com.betterf.identity.internal.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RegistrationLocks {
    private final JdbcTemplate jdbc;

    public RegistrationLocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lock(String key) {
        jdbc.execute(
                (java.sql.Connection connection) -> {
                    var statement =
                            connection.prepareStatement(
                                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))");
                    statement.setString(1, key);
                    return statement;
                },
                (org.springframework.jdbc.core.PreparedStatementCallback<Void>)
                        statement -> {
                            statement.execute();
                            return null;
                        });
    }
}
