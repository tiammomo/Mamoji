package com.mamoji.service.support;

import com.mamoji.notification.domain.OutboxEvent;

public interface OutboxEventHandler {
    void handle(OutboxEvent event);
}
