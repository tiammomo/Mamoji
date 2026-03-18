# 风控规则说明

本文档汇总当前 Mamoji 在预算与交易上的风控规则，便于产品、后端和前端统一口径。

## 1. 规则入口

- 交易风控: `TransactionController#buildTransactionRisk`
- 预算风控: `BudgetService#resolveRiskLevel` 与预算快照同步逻辑

建议同时关注的数据出口:

- 首页摘要与洞察接口: `/api/v1/stats/insights`
- AI 助手预算/流水回答
- 交易列表与详情页风控标签

## 2. 风险等级

统一等级（由低到高）:

- `low`
- `medium`
- `high`
- `critical`

前端应按等级展示不同颜色与文案，并支持查看命中 `flags`。

## 3. 交易风控（支出场景）

主要规则维度:

1. 单笔金额阈值
2. 当月收支失衡
3. 当日高频支出
4. 同日重复支出
5. 分类环比突增
6. 预算占用风险

常见风险标记（`flags`）:

- `critical_expense`
- `large_expense`
- `expense_without_income`
- `expense_income_ratio_high`
- `high_frequency_expense`
- `possible_duplicate_expense`
- `category_expense_spike`
- `budget_watch`
- `budget_warning`
- `budget_overrun`

## 4. 预算风控

预算风险来源:

- 预算额度（`amount`）
- 已用金额（`spent`）
- 使用率（`usageRate`）
- 预警阈值（`warningThreshold`）
- 状态（`active/completed/overrun`）

建议状态语义:

- `low`: 安全区间
- `medium`: 进入观察区间
- `high`: 达到预警阈值
- `critical`: 超预算或严重偏离

## 5. 规则命中后的产品动作建议

| 等级 | 建议动作 |
| --- | --- |
| `low` | 正常展示，不打断用户 |
| `medium` | 列表页标签提示，详情页展示建议 |
| `high` | 在详情页与 AI 助手中突出提醒 |
| `critical` | 首页/交易详情可增加明显 CTA，如“调整预算”或“查看异常流水” |

## 6. 输出结构约定

交易接口中风控字段放在 `risk`:

```json
{
  "risk": {
    "level": "high",
    "flags": ["budget_warning", "large_expense"],
    "message": "Potential budget pressure detected: monitor this transaction closely.",
    "budget": {
      "budgetId": 12,
      "usageRate": 92.4,
      "status": "warning"
    }
  }
}
```

## 7. 前端展示建议

1. 列表页显示 `level` 与 `flags` 的摘要标签
2. 详情页展示预算快照（额度/已用/剩余/使用率）
3. `high` 与 `critical` 级别显示明显 CTA（调整预算/回看大额支出）

## 8. 迭代建议

1. 将阈值参数外置到配置中心（便于灰度）
2. 增加规则命中统计，支持“规则有效性评估”
3. 对关键规则补充自动化回归测试
