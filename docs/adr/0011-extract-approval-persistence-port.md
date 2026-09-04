# ADR 0011：提取审批持久化端口

- 状态：Accepted
- 日期：2026-09-05

## 背景

ADR 0010 已将审批用例迁入纵向模块并隔离多态业务对象，但 `ApprovalApplicationService` 仍直接持有 `JdbcTemplate`。分页、汇总、请求与动作写入、行锁和 PostgreSQL advisory lock 与权限、状态机、审计编排混在同一个类中，使应用层依赖具体数据库实现，也让事务并发语义难以独立演进。

审批响应记录同时嵌套在 Service 类中，Controller 和持久化映射都依赖用例实现类型，不是稳定的模块契约。

## 决策

1. 在 `approval.application` 定义 `ApprovalRepository`，统一承接审批请求、动作轨迹、分页、汇总、审批人存在性检查和事务锁。
2. 在 `approval.infrastructure` 提供 `JdbcApprovalRepository`，保留既有 SQL、`FOR UPDATE` 和 `pg_advisory_xact_lock` 并发语义。
3. 将审批请求与动作记录迁入 `approval.domain`，将组合响应 `ApprovalDetail` 迁入 `approval.api`；字段名称、层级与 JSON 序列化保持不变。
4. 应用服务只保留访问控制、输入规则、审批状态机、审计、票据状态同步和事务边界，不再导入 JDBC 类型或包含 SQL。
5. 模块边界测试持续验证应用服务只能依赖审批仓储端口，JDBC 实现只能位于基础设施层。

## 影响

- 审批数据只有一个明确的写入端口，后续增加乐观锁、查询投影或数据库约束时无需修改 Controller 与用例契约。
- 创建审批的幂等锁和同一业务对象待审批防重锁仍位于同一个 Spring 事务中，行为与迁移前一致。
- Java 内部类型由 Service 嵌套记录改为模块类型；公开 HTTP 路径、状态码和 JSON 字段不变。
- 本次不修改数据库结构，也不需要数据迁移或双写。

## 验证与回退

- 企业工作流集成测试覆盖审批分页/汇总、创建、幂等重放、审批、驳回、撤回、动作轨迹和票据状态同步。
- 模块边界测试禁止 `ApprovalApplicationService` 重新持有 `JdbcTemplate` 或票据基础设施依赖。
- 回退只需回退代码提交；数据库和客户端无需回滚。
