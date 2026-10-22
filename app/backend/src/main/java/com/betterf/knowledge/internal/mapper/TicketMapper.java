package com.betterf.knowledge.internal.mapper;

import com.betterf.knowledge.api.dto.*;
import com.betterf.knowledge.internal.entity.TicketEntity;

import org.mapstruct.Mapper;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface TicketMapper {
    TicketView view(TicketEntity entity);

    default TicketContent content(String json) {
        return JsonMapper.shared().readValue(json, TicketContent.class);
    }

    default List<String> attributes(String json) {
        return List.of(JsonMapper.shared().readValue(json, String[].class));
    }
}
