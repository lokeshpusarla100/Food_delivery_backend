package com.fooddelivery.restaurant.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class RestaurantOutboxClaims {
    private final JdbcTemplate jdbc;
    public record PendingEvent(String id,String aggregateId,String type,String payload,int attempts) {}

    @Transactional
    public List<PendingEvent> claim(int size) {
        // SKIP LOCKED enables horizontally scaled publisher workers without blocking peers.
        List<PendingEvent> pending=jdbc.query(
            "SELECT event_id,aggregate_id,event_type,payload,attempt_count FROM restaurant_outbox "
            +"WHERE (status='PENDING' AND next_retry_at<=UTC_TIMESTAMP(6)) "
            +"OR (status='PUBLISHING' AND claimed_until<UTC_TIMESTAMP(6)) "
            +"ORDER BY created_at,event_id LIMIT ? FOR UPDATE SKIP LOCKED",
            (r,n)->new PendingEvent(r.getString("event_id"),r.getString("aggregate_id"),
                r.getString("event_type"),r.getString("payload"),r.getInt("attempt_count")),size);
        for (PendingEvent item:pending) jdbc.update(
            "UPDATE restaurant_outbox SET status='PUBLISHING',attempt_count=attempt_count+1,"
            +"claimed_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 60 SECOND) WHERE event_id=?",item.id());
        return pending;
    }

    @Transactional
    public void success(String eventId) {
        jdbc.update("UPDATE restaurant_outbox SET status='PUBLISHED',published_at=UTC_TIMESTAMP(6),"
          +"claimed_until=NULL,last_error=NULL WHERE event_id=? AND status='PUBLISHING'",eventId);
    }

    @Transactional
    public void failure(String eventId,int attempts,String summary) {
        long seconds=Math.min(3600,(long)Math.pow(2,Math.min(11,attempts+1)));
        jdbc.update("UPDATE restaurant_outbox SET status=?,next_retry_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? SECOND),"
            +"claimed_until=NULL,last_error=? WHERE event_id=? AND status='PUBLISHING'",
            attempts>=20?"FAILED":"PENDING",seconds,
            summary.length()>255?summary.substring(0,255):summary,eventId);
    }
}
