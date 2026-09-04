# ADR 0013：类型化票据写契约并收口领域模型

- 状态：Accepted
- 日期：2026-09-05

## 背景

Evidence 纵向边界建立后，票据 JSON 创建和更新接口仍接收 `Map<String, Object>`。金额、日期和状态值直到应用服务执行时才被字符串转换或静默归一化，错误输入不能稳定返回字段级问题；部分更新又依赖 `containsKey` 区分字段省略和显式 `null`，不能直接换成普通 record。票据实体同时仍嵌套在全局 `Models` 中，Evidence 之外的代码可以继续把它视为无归属共享模型。

## 决策

1. 将 `ReceiptVoucher` 迁入 `evidence.domain`，删除 `Models.ReceiptVoucher`；既有 JSON 字段保持不变。
2. `POST /api/v1/receipts` 和 `PUT /api/v1/receipts/{id}` 分别使用 `ReceiptCreateRequest` 与 `ReceiptUpdateRequest`，在 API 边界校验金额精度与范围、日期、状态白名单和文本长度。
3. 公开 DTO 明确禁止写入 `approvalStatus`，审批状态仍只能经 `ReceiptApprovalStatusService` 修改。
4. 更新 DTO 记录可空字段的 JSON presence，使字段省略继续表示“不修改”，显式 `null` 继续表示“清空”。
5. Controller 将 DTO 映射为 `ReceiptCreateCommand` 或 `ReceiptUpdateCommand`；应用层只依赖 transport-independent command，不反向依赖 API 包。

## 影响

- 违反 DTO 约束的 JSON 写请求在落库前返回统一的 HTTP 400 Problem Detail，错误码为 `validation_failed` 并包含字段错误。
- 未声明状态不再被静默降级为默认状态，减少错误业务数据进入票据流程。
- 票据领域类型拥有明确模块归属，跨模块读取方需要显式依赖 Evidence 领域或应用契约。
- HTTP 路径、成功响应结构和前端现有合法 payload 保持兼容；multipart 上传元数据与批量结果随后由 [ADR 0014](0014-type-receipt-upload-contracts.md) 类型化。

## 验证与回退

- 模块边界测试禁止 `ReceiptVoucher` 回到共享 `Models`，并禁止 JSON 创建/更新命令退回 Map。
- PostgreSQL 集成测试验证非法字段不会落库、公开接口不能修改审批状态，以及显式 `null` 清空字段时未提交字段不变。
- 企业票据、审批、附件和会计过账回归继续覆盖原有成功路径。
- 本次没有数据库迁移；回退代码提交即可，客户端和数据无需回滚。
