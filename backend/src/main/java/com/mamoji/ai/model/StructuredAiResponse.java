package com.mamoji.ai.model;

import java.util.List;
import java.util.Map;

public record StructuredAiResponse(
    String answer,
    List<String> sources,
    List<String> actions,
    List<String> warnings,
    Map<String, Object> usage,
    String modeUsed,
    String traceId
) {

    public StructuredAiResponse(String answer, List<String> sources, List<String> actions, List<String> warnings, Map<String, Object> usage) {
        this(answer, sources, actions, warnings, usage, null, null);
    }

    public StructuredAiResponse {
        sources = sources == null ? List.of() : List.copyOf(sources);
        actions = actions == null ? List.of() : List.copyOf(actions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        usage = usage == null ? Map.of() : Map.copyOf(usage);
    }
}

