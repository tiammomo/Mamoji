# AI 助手架构说明

## 1. 目标

AI 助手用于回答财务和股票相关问题，并尽量输出结构化、可执行的建议。

## 2. 入口与模式

接口入口:

- `POST /api/v1/ai/chat`
- `POST /api/v1/ai/chat/v2`
- `POST /api/v1/ai/chat/stream`

支持模式:

- `Auto`: 由后端自动选择 Agent 或 LLM 路径
- `Agent`: 工具优先（查询预算、流水、分类、行情等）
- `LLM`: 纯模型回答（必要时附保底模版）

助手类型:

- `finance`: 财务助手，优先回答预算、交易、分类、收支问题
- `stock`: 股票助手，优先回答行情、板块、个股与新闻问题

## 3. 核心组件

- `AIController`: HTTP 层入口与兼容路由
- `AiOrchestratorService`: 模式编排与路由
- `ReActAgentService`: Agent 主流程（工具、知识检索、记忆、质量门控）
- `FinanceIntentClassifier`: 财务意图分类（预算/分类/流水/收支）
- `AiToolRouter`: 工具选择与执行
- `AiQualityGateService`: 回答质量检查与重写触发

## 4. 处理流程（简化）

1. 接收问题与模式参数
2. 识别助手类型（finance/stock）
3. `Auto` 模式下根据意图和复杂度选择路径
4. Agent 路径执行:
   - 意图分类
   - 工具调用
   - 可选知识检索
   - 结构化输出解析
   - 质量门控
5. 返回结构化响应（含 `answer/sources/actions/warnings/usage`）

### 4.1 财务意图分类

当前分类器会优先识别以下问题类型:

- `budget`: 预算执行率、预算是否超支、预算建议
- `category`: 哪类支出最多、分类变化、分类占比
- `transaction`: 最近支出、最近收入、大额流水、某段时间流水
- `cashflow`: 本月/本年收入、支出、结余与现金流健康度

分类结果会直接影响工具选择精度与 `Auto` 模式下的路由。

## 5. 结构化响应契约

```json
{
  "answer": "string",
  "sources": ["tool:finance.query_budget"],
  "actions": ["finance.query_budget"],
  "warnings": ["quality_rewrite_retry"],
  "usage": {
    "inputChars": 420,
    "outputChars": 320,
    "estimatedTokens": 185
  }
}
```

## 6. 质量策略

质量门控关注:

- 输出格式是否合法
- 是否包含必要结论与行动建议
- 股票场景是否包含风险提示
- 回答长度与问题匹配程度

当门控失败时:

1. 触发一次重写
2. 重写失败则回退模板回答
3. 保留 `warnings` 便于前端提示

### 6.1 常见 warnings

| warning | 含义 |
| --- | --- |
| `quality_rewrite_retry` | 触发过一次质量重写 |
| `schema_*` | 结构化解析或字段契约存在问题 |
| `tool_template_fallback` | 模型结构化失败，改用工具模板兜底 |

## 7. 观测与排障

建议观测项:

- 模式命中比例（Auto/Agent/LLM）
- 工具调用成功率与耗时
- 质量门控触发率
- 模板回退触发率

排障优先级:

1. 先看 `warnings` 与 `actions`
2. 再看 `traceId` 与后端日志
3. 对比工具返回数据与最终回答是否一致

## 8. 迭代方向

1. 意图分类器升级（多标签 + 置信度门槛）
2. 工具选择策略可配置化
3. 质量门控指标可视化（报表页）
4. 增加面向财务场景的 few-shot 模板
5. 为股票助手增加更稳定的市场概览与新闻摘要模板
