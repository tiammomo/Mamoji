# API 文档

## 1. 文档范围

本文档覆盖当前 `backend/src/main/java/com/mamoji/controller` 中公开的主要 REST API，重点面向前端联调、测试编写和接口维护。

## 2. 通用约定

### 2.1 Base URL

- 本地开发：`http://localhost:38080/api/v1`
- 文档中各模块路径均基于 `/api/v1`

### 2.2 鉴权

- 除注册、登录等开放接口外，其余接口默认需要 JWT。
- 请求头格式：

```http
Authorization: Bearer <token>
```

### 2.3 统一响应结构

成功响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

错误响应：

```json
{
  "code": 1001,
  "message": "业务错误信息",
  "data": null
}
```

### 2.4 常见字段约定

| 字段 | 说明 |
| --- | --- |
| `id` | 主键 ID |
| `type` | 收支类型，通常 `1=收入`、`2=支出` |
| `status` | 状态字段，通常 `1=启用/有效`、`0=停用/无效` |
| `startDate/endDate` | 日期区间，格式通常为 `YYYY-MM-DD` |
| `month` | 月份参数，兼容 `YYYY-MM` 与 `YYYY-MM-DD` |

### 2.5 分页约定

部分接口使用以下查询参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `page` | `1` | 页码，从 1 开始 |
| `pageSize` | `20` | 每页条数 |

## 3. 认证模块

Base: `/auth`

| 方法 | 路径 | 说明 | 备注 |
| --- | --- | --- | --- |
| `POST` | `/register` | 注册新用户 | 开放接口 |
| `POST` | `/login` | 用户登录 | 开放接口 |
| `GET` | `/me` | 获取当前用户信息 | 需要登录 |
| `PUT` | `/profile` | 更新昵称等资料 | 需要登录 |
| `PUT` | `/password` | 修改登录密码 | 需要登录 |

典型登录请求：

```json
{
  "email": "demo@example.com",
  "password": "123456"
}
```

## 4. 账户模块

Base: `/accounts`

| 方法 | 路径 | 说明 | 关键参数 |
| --- | --- | --- | --- |
| `GET` | `` | 获取账户列表 | 无 |
| `GET` | `/{id}` | 获取账户详情 | `id` |
| `POST` | `` | 创建账户 | `name`、`type`、`balance` 等 |
| `PUT` | `/{id}` | 更新账户 | `id` + 部分可变字段 |
| `DELETE` | `/{id}` | 删除账户 | `id` |
| `GET` | `/summary` | 获取账户汇总 | 可选 `startDate`、`endDate` |

说明：

- 写接口需要账户管理权限或管理员角色。
- `/summary` 当前主要返回总资产、总负债、净资产。

## 5. 分类模块

Base: `/categories`

| 方法 | 路径 | 说明 | 关键参数 |
| --- | --- | --- | --- |
| `GET` | `` | 获取分类列表 | 可按类型筛选 |
| `POST` | `` | 创建分类 | `name`、`type`、`icon` |
| `PUT` | `/{id}` | 更新分类 | `id` |
| `DELETE` | `/{id}` | 删除分类 | `id` |

说明：

- 系统分类不允许删除。
- 写接口需要分类管理权限或管理员角色。

## 6. 预算模块

Base: `/budgets`

| 方法 | 路径 | 说明 | 关键参数 |
| --- | --- | --- | --- |
| `GET` | `` | 获取预算列表 | 可带时间范围 |
| `GET` | `/active` | 获取当前生效预算 | 无 |
| `GET` | `/{id}` | 获取预算详情 | `id` |
| `POST` | `` | 创建预算 | `name`、`amount`、`startDate`、`endDate`、`categoryId` |
| `PUT` | `/{id}` | 更新预算 | `id` |
| `DELETE` | `/{id}` | 删除预算 | `id` |

说明：

- 后端会校验时间区间、预算重叠与预算归属。
- 响应通常会包含 `spent`、`remaining`、`usageRate`、`warningThreshold` 等派生信息。

## 7. 交易模块

Base: `/transactions`

| 方法 | 路径 | 说明 | 关键参数 |
| --- | --- | --- | --- |
| `GET` | `` | 获取交易列表 | 支持日期、分类、账户、账本等筛选 |
| `POST` | `` | 创建交易 | `type`、`amount`、`date`、`accountId`、`categoryId` |
| `PUT` | `/{id}` | 更新交易 | `id` |
| `DELETE` | `/{id}` | 删除交易 | `id` |
| `GET` | `/refundable` | 获取可退款交易 | 可配合前端退款弹窗 |
| `POST` | `/{id}/refund` | 对指定交易发起退款 | `id` + 退款信息 |

说明：

- 交易写路径会触发预算同步和风控规则。
- 风控结果可能体现在返回结构、警告信息或后续统计中。

典型创建交易请求：

```json
{
  "type": 2,
  "amount": 48.63,
  "date": "2026-03-18",
  "accountId": 1,
  "categoryId": 12,
  "ledgerId": 1,
  "remark": "午餐"
}
```

## 8. 账本模块

Base: `/ledgers`

| 方法 | 路径 | 说明 | 关键参数 |
| --- | --- | --- | --- |
| `GET` | `` | 获取账本列表 | 无 |
| `GET` | `/{id}` | 获取账本详情 | `id` |
| `POST` | `` | 创建账本 | `name`、`description`、`currency` |
| `PUT` | `/{id}` | 更新账本 | `id` |
| `DELETE` | `/{id}` | 删除账本 | `id` |
| `GET` | `/{id}/members` | 获取账本成员 | `id` |
| `POST` | `/{id}/members` | 添加账本成员 | `userId`、`role` |
| `DELETE` | `/{id}/members/{memberId}` | 移除成员 | `id`、`memberId` |
| `GET` | `/default` | 获取默认账本 ID | 无 |

说明：

- 账本 owner 拥有最高权限。
- 添加/移除成员时会校验请求人角色。

## 9. 周期记账模块

Base: `/recurring`

| 方法 | 路径 | 说明 | 关键参数 |
| --- | --- | --- | --- |
| `GET` | `` | 获取周期记账列表 | 可带 `page`、`pageSize` |
| `GET` | `/{id}` | 获取周期记账详情 | `id` |
| `POST` | `` | 创建周期记账项 | `name`、`type`、`amount`、`recurrenceType` |
| `PUT` | `/{id}` | 更新周期记账项 | `id` |
| `DELETE` | `/{id}` | 删除周期记账项 | `id` |
| `POST` | `/{id}/toggle` | 启用或停用 | `id` |
| `POST` | `/{id}/execute` | 手动执行一次 | `id` |

说明：

- 当前实现为内存存储，适合联调与界面验证，不适合作为生产持久化方案。
- `execute` 会返回一份“生成交易”的结构化结果。

## 10. 报表统计模块

Base: `/stats`

| 方法 | 路径 | 说明 | 关键参数 |
| --- | --- | --- | --- |
| `GET` | `/overview` | 月度总览 | 可选 `month` |
| `GET` | `/trend` | 趋势图数据 | `startDate`、`endDate` |
| `GET` | `/categories` | 分类统计 | `type`，可选时间范围 |
| `GET` | `/annual` | 年度报表 | `year` |
| `GET` | `/balance-sheet` | 资产负债摘要 | 无 |
| `GET` | `/comparison` | 环比/同比对比 | 可选 `month` |
| `GET` | `/insights` | 高级洞察 | 可选 `month` |

说明：

- `/insights` 重点返回：
  - 最近支出
  - 最近收入
  - 最近大额支出
  - 最近大额收入
  - 预算告警
  - 异常分类
- 首页和高级报表均可复用这些聚合结果。

## 11. AI 助手模块

Base: `/ai`

| 方法 | 路径 | 说明 | 关键参数 |
| --- | --- | --- | --- |
| `POST` | `/chat` | 兼容旧版的普通对话接口 | `message`、`assistantType`、`mode` |
| `POST` | `/chat/v2` | 结构化对话接口 | `message`、`assistantType`、`mode`、`sessionId` |
| `POST` | `/chat/legacy` | 旧版兼容接口 | 不建议新客户端继续使用 |
| `POST` | `/chat/stream` | SSE 流式输出 | 与 `chat/v2` 类似，返回事件流 |

请求字段说明：

| 字段 | 说明 |
| --- | --- |
| `message` | 用户问题 |
| `assistantType` | 助手类型，例如财务助手、股票助手 |
| `mode` | `auto` / `agent` / `llm` |
| `sessionId` | 会话 ID，用于多轮对话 |

`chat/v2` 结构化响应通常包含：

| 字段 | 说明 |
| --- | --- |
| `answer` | 主回答文本 |
| `warnings` | 风险提示、兜底说明 |
| `sources` | 数据来源或工具来源 |
| `actions` | 建议操作 |
| `usage` | 模型或工具调用元信息 |
| `modeUsed` | 实际使用模式 |
| `traceId` | 追踪 ID |

流式接口说明：

- 返回 `text/event-stream`。
- 通常包含 `chunk` 事件和最终 `done` 事件。

典型问答请求：

```json
{
  "message": "我的预算执行率如何？",
  "assistantType": "finance",
  "mode": "agent",
  "sessionId": "demo-session-001"
}
```

## 12. 备份模块

Base: `/backup`

| 方法 | 路径 | 说明 | 关键参数 |
| --- | --- | --- | --- |
| `GET` | `/status` | 获取备份数据量摘要 | 无 |
| `GET` | `/export` | 导出 JSON 备份 | 无 |
| `POST` | `/import` | 上传备份文件做格式校验 | multipart `file` |

说明：

- 当前导入接口主要做文件存在性和格式校验。
- 真正的数据库导入仍处于占位阶段。

## 13. 管理员模块

Base: `/admin/users`

| 方法 | 路径 | 说明 | 关键参数 |
| --- | --- | --- | --- |
| `GET` | `` | 获取用户列表 | 管理员权限 |
| `POST` | `` | 创建用户 | `email`、`password`、`role`、`permissions` |
| `PUT` | `/{id}` | 更新用户 | `id` |
| `DELETE` | `/{id}` | 删除用户 | `id` |

说明：

- 管理员不可删除自己。
- 创建用户时会校验邮箱是否已注册。

## 14. 常见错误码

| 错误码 | 说明 |
| --- | --- |
| `0` | 成功 |
| `1001` | 通用业务错误 |
| `1003` | 权限不足 |
| `1004` | 非法操作，例如删除自己 |
| `2002` | 资源冲突，例如邮箱已注册 |
| `4001` | 备份文件为空 |
| `4002` | 备份文件格式不支持 |

## 15. 联调建议

1. 新页面优先使用 `chat/v2` 和结构化统计接口，减少前端拼装成本。
2. 涉及预算和交易写入时，同步验证列表页、首页摘要和 AI 助手结果是否一致。
3. 若接口字段有变更，请同时更新本文件、对应 Controller 注释和前端 `api.types.ts`。
