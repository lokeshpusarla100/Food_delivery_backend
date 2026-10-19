package com.fooddelivery.restaurant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

@Component @RequiredArgsConstructor
public class OutboxWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public void append(String aggregateId,String type,Object detail) {
        try {
            String payload=mapper.writeValueAsString(Map.of(
                "aggregateId",aggregateId,"eventType",type,"detail",detail));
            jdbc.update("INSERT INTO restaurant_outbox(event_id,aggregate_id,event_type,payload) "
                +"VALUES (?,?,?,CAST(? AS JSON))",UUID.randomUUID().toString(),aggregateId,type,payload);
        } catch(JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize domain event",e);
        }
    }
}
