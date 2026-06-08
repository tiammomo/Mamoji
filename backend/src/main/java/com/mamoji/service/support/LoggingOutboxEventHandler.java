package com.mamoji.service.support;

import com.mamoji.domain.Models.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LoggingOutboxEventHandler implements OutboxEventHandler {
    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxEventHandler.class);

    @Override
    public void handle(OutboxEvent event) {
        log.info(
            "Processed outbox event id={} eventId={} type={} aggregate={}:{} companyId={} actorUserId={}",
            event.id,
            event.eventId,
            event.eventType,
            event.aggregateType,
            event.aggregateId,
            event.companyId,
            event.actorUserId
        );
    }
}
