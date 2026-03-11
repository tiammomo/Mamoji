package com.mamoji.ai.eval;

import com.mamoji.ai.quality.AiQualityGateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Offline evaluator for AI quality rules.
 */
@Service
@RequiredArgsConstructor
public class AiOfflineEvaluationService {

    private final AiQualityGateService qualityGateService;

    /**
     * Evaluates cases and produces aggregate pass/warning report.
     */
    public EvaluationReport evaluate(List<EvaluationCase> cases) {
        if (cases == null || cases.isEmpty()) {
            return new EvaluationReport(0, 0, 0, 0.0, Map.of());
        }

        int warningCount = 0;
        int failCount = 0;
        List<CaseResult> caseResults = new ArrayList<>(cases.size());

        for (EvaluationCase evalCase : cases) {
            List<String> warnings = qualityGateService.validate(
                evalCase.assistantType(),
                evalCase.question(),
                evalCase.answer()
            );
            warningCount += warnings.size();
            if (!warnings.isEmpty()) {
                failCount++;
            }
            caseResults.add(new CaseResult(evalCase.assistantType(), warnings));
        }

        Map<String, Integer> warningBreakdown = caseResults.stream()
            .flatMap(r -> r.warnings().stream())
            .collect(Collectors.groupingBy(Function.identity(), Collectors.summingInt(x -> 1)));

        return new EvaluationReport(
            cases.size(),
            failCount,
            warningCount,
            roundTo4((cases.size() - failCount) / (double) cases.size()),
            warningBreakdown
        );
    }

    /**
     * Rounds double value to 4 decimal places.
     */
    private double roundTo4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    /**
     * Internal per-case evaluation result.
     */
    private record CaseResult(String assistantType, List<String> warnings) {
    }

    /**
     * Input case for offline evaluation.
     */
    public record EvaluationCase(String assistantType, String question, String answer) {
    }

    /**
     * Output report for offline quality evaluation.
     */
    public record EvaluationReport(
        int totalCases,
        int failedCases,
        int totalWarnings,
        double passRate,
        Map<String, Integer> warningBreakdown
    ) {
    }
}
