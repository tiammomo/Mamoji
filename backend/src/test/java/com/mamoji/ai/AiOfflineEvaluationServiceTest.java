package com.mamoji.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.ai.metrics.AiMetricsService;
import com.mamoji.ai.eval.AiOfflineEvaluationService;
import com.mamoji.ai.quality.AiQualityGateService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import io.micrometer.core.instrument.MeterRegistry;

import java.io.InputStream;
import java.util.List;

/**
 * Test suite for AiOfflineEvaluationServiceTest.
 */

class AiOfflineEvaluationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiOfflineEvaluationService service = new AiOfflineEvaluationService(newQualityGateService());

    @Test
    void shouldGenerateDeterministicEvaluationReportFromJsonDataset() throws Exception {
        InputStream input = getClass().getClassLoader().getResourceAsStream("ai/eval-dataset.json");
        Assertions.assertNotNull(input, "eval dataset missing");

        List<AiOfflineEvaluationService.EvaluationCase> cases = objectMapper.readValue(
            input,
            new TypeReference<>() {
            }
        );
        AiOfflineEvaluationService.EvaluationReport report = service.evaluate(cases);

        Assertions.assertEquals(4, report.totalCases());
        Assertions.assertEquals(2, report.failedCases());
        Assertions.assertEquals(3, report.totalWarnings());
        Assertions.assertEquals(0.5, report.passRate());
        Assertions.assertEquals(2, report.warningBreakdown().get("too_short"));
    }

    private AiQualityGateService newQualityGateService() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = Mockito.mock(ObjectProvider.class);
        AiMetricsService metricsService = new AiMetricsService(provider);
        return new AiQualityGateService(new AiProperties(), metricsService);
    }
}



