package com.mamoji.service.support;

import com.mamoji.domain.Models.OutboxEvent;

public interface OutboxEventHandler {
    void handle(OutboxEvent event);
}
