package com.mamoji.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.ai.metrics.AiMetricsService;
import com.mamoji.ai.quality.AiQualityGateService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import io.micrometer.core.instrument.MeterRegistry;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Test suite for AiQualityRegressionBaselineTest.
 */

class AiQualityRegressionBaselineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiQualityGateService qualityGateService = newQualityGateService();

    @Test
    void shouldMatchRegressionBaselineWarnings() throws Exception {
        InputStream input = getClass().getClassLoader().getResourceAsStream("ai/regression-baseline.json");
        Assertions.assertNotNull(input, "baseline file missing");

        List<Map<String, Object>> cases = objectMapper.readValue(input, new TypeReference<>() {});
        for (Map<String, Object> item : cases) {
            String assistantType = item.get("assistantType").toString();
            String question = item.get("question").toString();
            String answer = item.get("answer").toString();
            @SuppressWarnings("unchecked")
            List<String> expectedWarnings = (List<String>) item.get("expectWarnings");

            List<String> actual = qualityGateService.validate(assistantType, question, answer);
            Assertions.assertEquals(expectedWarnings, actual, "baseline mismatch for question: " + question);
        }
    }

    private AiQualityGateService newQualityGateService() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = Mockito.mock(ObjectProvider.class);
        AiMetricsService metricsService = new AiMetricsService(provider);
        return new AiQualityGateService(new AiProperties(), metricsService);
    }
}



