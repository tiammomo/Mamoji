package com.mamoji.ai.tool;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory registry of available AI tool handlers.
 *
 * <p>Spring injects all {@link AiToolHandler} beans and this registry indexes
 * them by handler name for routing lookup.
 */
@Component
public class AiToolRegistry {

    private final Map<String, AiToolHandler> handlers = new HashMap<>();

    public AiToolRegistry(List<AiToolHandler> handlers) {
        for (AiToolHandler handler : handlers) {
            this.handlers.put(handler.name(), handler);
        }
    }

    /**
     * Returns handler by tool name.
     */
    public Optional<AiToolHandler> find(String name) {
        return Optional.ofNullable(handlers.get(name));
    }

    /**
     * Returns all registered tool names.
     */
    public Set<String> allNames() {
        return handlers.keySet();
    }
}

