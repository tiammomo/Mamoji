# ADR 0018：收窄票据关联流水的查询契约

- 状态：Accepted
- 日期：2026-09-05

## 背景

Evidence 创建、上传或更新票据时，只需要确认一条流水是否存在，以及它的公司和所有者是否与当前操作一致。但票据应用服务此前依赖 Operations 的通用 `TransactionQueryRepository`，取得包含金额、账户、分类、退款、预算和生命周期等字段的可变 `TransactionRecord`；JDBC 为此还联表读取账户与分类展示字段。

这既扩大了跨模块编译依赖和查询数据面，也让 Evidence 可以意外使用并不属于关联校验的流水业务状态。V31 已用 `(company_id, transaction_id)` 复合外键保护最终租户关系，应用层仍需要一个最小契约保留“不存在”与“当前用户无权关联”的 HTTP 语义。

## 决策

1. Operations 应用层公开 `TransactionLinkQuery`，只按流水 ID 返回可选的 `TransactionLinkTarget`。
2. `TransactionLinkTarget` 是不可变 record，仅包含正数 `transactionId`、`companyId` 和 `ownerUserId`，不暴露流水金额、账户、分类或可变聚合。
3. `JdbcTransactionLinkQuery` 只从 `transactions` 读取 `id/company_id/user_id`，不联表、不使用 `SELECT *`，也不复用面向流水列表和详情的通用仓储查询。
4. Evidence 使用该投影区分不存在的流水（HTTP 404）与公司或所有者不匹配（HTTP 403）；空值和兼容性的 `transactionId=0` 继续表示不关联流水。
5. V31 的公司范围复合外键仍是并发与绕过应用入口时的最终完整性防线。本批不修改 HTTP JSON、数据库 schema 或现有成功响应。

## 影响

- Evidence 不再依赖 `TransactionQueryRepository` 或 `TransactionRecord`，Operations 的内部聚合演进不会扩散到票据模块。
- 每次票据关联校验只读取三个标量列，避免无用的账户/分类联表和完整流水对象分配。
- 关联权限继续保持当前“流水所有者本人 + 同公司”规则；公司管理员不会仅因拥有公司权限而关联其他成员的个人流水。

## 验证与回退

- 值对象测试固定三个 ID 的正数不变量，JDBC 测试固定最小列集合且禁止联表和 `SELECT *`。
- 模块边界测试禁止 `ReceiptApplicationService` 重新依赖通用流水仓储或领域聚合。
- PostgreSQL HTTP 集成测试覆盖合法关联、流水不存在、跨公司和同公司不同所有者，失败请求不得产生票据。
- 本批没有数据迁移或 API 变化；回退代码提交即可，V31/V32 数据与客户端无需回滚。
