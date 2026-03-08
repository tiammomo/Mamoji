package com.mamoji.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class StructuredAnswerParser {

    private static final Pattern MARKDOWN_CODE_BLOCK = Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");

    private final ObjectMapper objectMapper;

    public Optional<ParsedAnswer> parse(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return Optional.empty();
        }

        for (String candidate : candidateJsonStrings(rawAnswer)) {
            Optional<ParsedAnswer> parsed = parseCandidate(candidate);
            if (parsed.isPresent()) {
                return parsed;
            }
        }

        return Optional.empty();
    }

    private Set<String> candidateJsonStrings(String rawAnswer) {
        Set<String> candidates = new LinkedHashSet<>();
        String trimmed = rawAnswer.trim();
        candidates.add(trimmed);

        String markdownBody = extractMarkdownBody(trimmed);
        if (!markdownBody.isBlank()) {
            candidates.add(markdownBody);
        }

        String jsonFromRaw = extractJsonObject(trimmed);
        if (!jsonFromRaw.isBlank()) {
            candidates.add(jsonFromRaw);
        }

        if (!markdownBody.isBlank()) {
            String jsonFromMarkdown = extractJsonObject(markdownBody);
            if (!jsonFromMarkdown.isBlank()) {
                candidates.add(jsonFromMarkdown);
            }
        }

        return candidates;
    }

    private Optional<ParsedAnswer> parseCandidate(String candidate) {
        try {
            JsonNode node = objectMapper.readTree(candidate);
            if (node == null || !node.isObject()) {
                return Optional.empty();
            }

            String answer = firstNonBlank(
                textOrNull(node.get("answer")),
                textOrNull(node.get("content")),
                textOrNull(node.path("output").get("text"))
            );

            if (answer == null || answer.isBlank()) {
                return Optional.empty();
            }

            return Optional.of(
                new ParsedAnswer(
                    answer,
                    readStringArray(node.get("warnings")),
                    readStringArray(node.get("sources")),
                    readStringArray(node.get("actions"))
                )
            );
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String extractMarkdownBody(String raw) {
        Matcher matcher = MARKDOWN_CODE_BLOCK.matcher(raw);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1).trim();
        }
        return "";
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText(null);
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && !item.isNull()) {
                String value = item.asText("");
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record ParsedAnswer(String answer, List<String> warnings, List<String> sources, List<String> actions) {
    }
}
