# ADR 0012：纵向收口票据应用与持久化边界

- 状态：Accepted
- 日期：2026-09-05

## 背景

票据已经拥有 `evidence.domain` 与部分 infrastructure 代码，但 HTTP Controller 和 792 行用例服务仍位于横向的 `controller/service` 目录。用例服务直接持有 `JdbcTemplate` 读写 `receipt_file_hashes`，票据仓储也是可直接注入的具体 JDBC 类；审批适配器还依赖完整票据服务来同步状态。这些依赖使模块所有权不清晰，并让票据用例、SQL 和跨模块写入口难以独立演进。

文件去重原先采用“先查询、后上传、最后依赖唯一索引写入”的方式。两个副本并发上传同一文件时都可能通过查询并写入对象存储，其中一个请求最终以数据库约束异常失败，而不是稳定的业务冲突。

## 决策

1. 将票据 Controller 与用例服务迁入 `evidence.api` 和 `evidence.application`，公开 HTTP 路径、状态码与 JSON 结构保持不变。
2. 在应用层定义 `ReceiptVoucherRepository` 和 `ReceiptFileHashRepository`，由 `JdbcReceiptVoucherRepository` 与 `JdbcReceiptFileHashRepository` 实现；应用服务不再依赖 `JdbcTemplate` 或包含 SQL。
3. 上传文件在访问对象存储前，按 `company_id + SHA-256` 获取 PostgreSQL transaction advisory lock，再检查重复哈希。并发请求因此串行判定，只允许一个凭证进入写流程，另一个稳定返回 HTTP 409。
4. 暴露 `ReceiptApprovalStatusService` 作为审批流程唯一可调用的票据写契约，Approval 适配器不再注入完整票据用例服务。
5. 将附件下载结果从 Service 嵌套记录提升为 `ReceiptFileDownload` 应用类型，避免 API 层依赖具体实现的内部类型。

## 影响

- Evidence 模块拥有明确的 API、应用和 JDBC 持久化边界，后续可以在不触碰 Controller 的情况下继续拆分 typed DTO 与领域模型。
- 票据读取方依赖 Evidence 应用层仓储契约，不再依赖具体 JDBC 类；跨模块票据状态写入只有一个窄入口。
- 同文件并发上传不会创建重复票据，也不会在冲突请求中重复写对象存储。
- 本次不修改数据库结构、HTTP 契约或客户端调用方式，不需要迁移数据和双写。

## 验证与回退

- 模块边界测试禁止票据入口返回旧横向目录，并禁止应用服务持有 JDBC 类型或 infrastructure 实现。
- PostgreSQL 集成测试并发上传同一文件，断言一个成功、一个冲突，且只产生一个票据与一个文件哈希。
- 企业工作流回归继续覆盖租户隔离、票据创建、审批同步、禁止绕过审批及过账状态机。
- 回退只需回退代码提交；数据库与客户端无需回滚。
