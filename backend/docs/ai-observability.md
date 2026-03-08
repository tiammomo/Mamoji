# AI Observability (Backend)

## Endpoints

- `GET /actuator/health`
- `GET /actuator/metrics`
- `GET /actuator/prometheus`

## Core Metrics

- `ai.request.count{provider,success}`: upstream AI request count.
- `ai.request.latency{provider,success}`: upstream AI latency timer.
- `ai.request.tokens{provider}`: estimated request+response token usage.
- `ai.model.route.count{assistantType,model}`: model routing hit count.
- `ai.model.route.reason.count{assistantType,model,reason}`: routing reason hit count.
- `ai.model.fallback.count{primary,fallback}`: fallback model switch count.
- `ai.tool.count{tool,success}`: tool call count.
- `ai.tool.latency{tool,success}`: tool call latency timer.
- `ai.quality.warnings{assistantType}`: quality warning distribution.
- `ai.quality.rule.hit{assistantType,rule}`: quality rule trigger count.
- `ai.cache.access.count{layer,cache,hit}`: cache hit/miss count.
- `ai.metrics.dimension.missing.count{metric,dimension}`: missing/invalid metric dimension count.

## Cardinality Policy

- Tags are normalized to lowercase.
- Blank tags are mapped to `unknown`.
- Unexpected or non-conforming tags are mapped to `other`.
- This policy currently applies to provider, assistantType, model, routing reason, tool, rule, cache and layer dimensions.

## Suggested Alerts

- AI request failure ratio > 10% (5m):
  - `sum(rate(ai_request_count{success="false"}[5m])) / sum(rate(ai_request_count[5m])) > 0.1`
- AI p95 latency > 3s (5m):
  - `histogram_quantile(0.95, sum(rate(ai_request_latency_seconds_bucket[5m])) by (le)) > 3`
- Tool failure ratio > 15% by tool (10m):
  - `sum by (tool) (rate(ai_tool_count{success="false"}[10m])) / sum by (tool) (rate(ai_tool_count[10m])) > 0.15`
- Fallback switch spike > 5/min (10m):
  - `sum(rate(ai_model_fallback_count[10m])) > 0.083`
- Missing dimensions detected (5m):
  - `sum(rate(ai_metrics_dimension_missing_count[5m])) > 0`

## Routing Insights

- Route split by reason (5m):
  - `sum by (reason) (rate(ai_model_route_reason_count[5m]))`
- Finance assistant route mix (5m):
  - `sum by (model,reason) (rate(ai_model_route_reason_count{assistantType="finance"}[5m]))`

## Runbook

### 1) AI failure ratio alert

- Check `ai.request.count{success="false"}` by provider.
- Correlate with `ai.model.fallback.count` to confirm provider instability.
- If failures are concentrated in one model, switch to fallback model in config.

### 2) AI latency alert

- Check `ai.request.latency` and `ai.tool.latency` separately.
- If request latency is high but tool latency is normal, inspect upstream AI provider.
- If tool latency is high, inspect downstream APIs and tool guard/circuit state.

### 3) Missing dimension alert

- Query `ai.metrics.dimension.missing.count` by `metric` and `dimension`.
- If `provider` missing: verify AI provider config and runtime injection.
- If `model` missing in routing metrics: verify router output and route instrumentation path.
- If `tool` missing: verify tool handler registration and tool name propagation.

### 4) Route reason anomaly

- Compare `ai.model.route.reason.count` with expected traffic profile.
- If `default` suddenly dominates, review routing thresholds and assistantType input quality.
- If `routing_disabled` appears in production, check profile/config drift.
