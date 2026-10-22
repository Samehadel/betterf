package com.betterf.foundation.internal.service;

import com.betterf.foundation.api.dto.StatusView;
import com.betterf.foundation.api.service.StatusService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class StatusServiceImpl implements StatusService {
    private final JdbcTemplate jdbc;

    StatusServiceImpl(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public StatusView status() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return new StatusView("UP");
    }
}
