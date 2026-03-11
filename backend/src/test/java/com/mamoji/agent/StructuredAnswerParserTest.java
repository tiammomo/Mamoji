package com.mamoji.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test suite for StructuredAnswerParserTest.
 */

class StructuredAnswerParserTest {

    @Test
    void shouldParseStandardStructuredPayload() {
        StructuredAnswerParser parser = new StructuredAnswerParser(new ObjectMapper());
        String raw = "{\"answer\":\"ok\",\"warnings\":[\"w1\"],\"sources\":[\"s1\"],\"actions\":[\"a1\"]}";

        StructuredAnswerParser.ParsedAnswer parsed = parser.parse(raw).orElseThrow();

        Assertions.assertEquals("ok", parsed.answer());
        Assertions.assertEquals(1, parsed.warnings().size());
        Assertions.assertEquals(1, parsed.sources().size());
        Assertions.assertEquals(1, parsed.actions().size());
    }

    @Test
    void shouldParseMarkdownCodeFenceAndOutputTextShape() {
        StructuredAnswerParser parser = new StructuredAnswerParser(new ObjectMapper());
        String raw = """
            Here is the result:
            ```json
            {"output":{"text":"from-spring-ai"}}
            ```
            """;

        StructuredAnswerParser.ParsedAnswer parsed = parser.parse(raw).orElseThrow();

        Assertions.assertEquals("from-spring-ai", parsed.answer());
        Assertions.assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void shouldParseReplyStylePayload() {
        StructuredAnswerParser parser = new StructuredAnswerParser(new ObjectMapper());
        String raw = "{\"reply\":\"this month expense is stable\",\"warnings\":[],\"sources\":[],\"actions\":[]}";

        StructuredAnswerParser.ParsedAnswer parsed = parser.parse(raw).orElseThrow();

        Assertions.assertEquals("this month expense is stable", parsed.answer());
    }
}



