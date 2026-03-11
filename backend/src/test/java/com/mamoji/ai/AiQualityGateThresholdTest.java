package com.mamoji.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.ai.eval.AiOfflineEvaluationService;
import com.mamoji.ai.metrics.AiMetricsService;
import com.mamoji.ai.quality.AiQualityGateService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.io.InputStream;
import java.util.List;

/**
 * Test suite for AiQualityGateThresholdTest.
 */

class AiQualityGateThresholdTest {

    private static final double MIN_PASS_RATE = 0.5;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldMeetConfiguredQualityGate() throws Exception {
        InputStream input = getClass().getClassLoader().getResourceAsStream("ai/eval-dataset.json");
        Assertions.assertNotNull(input, "eval dataset missing");

        List<AiOfflineEvaluationService.EvaluationCase> cases = objectMapper.readValue(
            input,
            new TypeReference<>() {
            }
        );

        AiOfflineEvaluationService service = new AiOfflineEvaluationService(newQualityGateService());
        AiOfflineEvaluationService.EvaluationReport report = service.evaluate(cases);
        Assertions.assertTrue(
            report.passRate() >= MIN_PASS_RATE,
            "AI quality gate failed: passRate=" + report.passRate() + ", required=" + MIN_PASS_RATE
        );
    }

    private AiQualityGateService newQualityGateService() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = Mockito.mock(ObjectProvider.class);
        AiMetricsService metricsService = new AiMetricsService(provider);
        return new AiQualityGateService(new AiProperties(), metricsService);
    }
}



