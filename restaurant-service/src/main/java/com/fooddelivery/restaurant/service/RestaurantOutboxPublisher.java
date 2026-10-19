package com.fooddelivery.restaurant.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component @RequiredArgsConstructor
public class RestaurantOutboxPublisher {
    private static final Logger log=LoggerFactory.getLogger(RestaurantOutboxPublisher.class);
    private final RestaurantOutboxClaims claims;
    private final KafkaTemplate<String,String> kafka;
    @Value("${restaurant.outbox.enabled:false}") private boolean enabled;
    @Value("${restaurant.outbox.topic:restaurant.catalog.events.v1}") private String topic;

    @Scheduled(fixedDelayString="${restaurant.outbox.poll-ms:1000}")
    public void publishBatch() {
        if(!enabled) return;
        for(var event:claims.claim(10)) {
            try {
                // Key by aggregate for per-partition event locality.
                // event_id must be used by consumers to deduplicate at-least-once delivery.
                kafka.send(topic,event.aggregateId(),event.payload()).get(3,TimeUnit.SECONDS);
                claims.success(event.id());
            } catch (Exception e) {
                log.warn("Restaurant outbox publish retry eventId={} type={}",event.id(),event.type());
                claims.failure(event.id(),event.attempts()+1,e.getClass().getSimpleName());
                if(e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
