# ADR 0007：为外部通知投递增加租约 fencing

- 状态：Accepted
- 日期：2026-09-05

## 背景

Webhook 投递使用 `FOR UPDATE SKIP LOCKED` 避免多个实例同时认领同一记录，并会把超时的 `processing` 记录恢复为 `failed`。此前成功或失败回写只按投递 ID 更新：当旧请求超过租约时间、新实例重新认领后，旧工作线程稍后返回仍能覆盖新租约状态。Outbox 事件已经使用唯一 `lock_token` 解决相同竞态，外部通知投递需要采用一致边界。

网络超时还存在不可消除的不确定性：接收方可能已处理请求，但响应没有返回。数据库 fencing 可以保护 Mamoji 自身状态，不能自动撤销已经发生的外部副作用。

## 决策

1. Flyway V29 为 `notification_deliveries` 增加 `lock_token`。
2. 每次认领 Webhook 投递时生成新的 UUID 令牌，并与 `processing` 状态、尝试次数和锁定时间一起提交。
3. delivered、failed 和 dead 终态写入必须同时匹配记录 ID、`processing` 状态与当前令牌；陈旧工作线程的回写只记录警告，不修改数据库。
4. 恢复陈旧租约时清除旧令牌；重试认领获得全新令牌。
5. 正式 Webhook 请求发送稳定的 `Idempotency-Key: mamoji:notification-delivery:<delivery_id>`。测试请求使用随机键，避免人工重复测试被接收方误去重。

## 影响

- 多实例或慢网络环境下，旧请求不能把已被重新认领的投递覆盖为 delivered、failed 或 dead。
- 自建接收方可以按稳定请求头实现副作用幂等。
- Webhook 仍是至少一次投递；不识别幂等键的第三方入口可能收到重复消息。
- 业务事务仍只写 Outbox 和站内通知数据，不会等待外部 HTTP 请求。

## 验证

真实 PostgreSQL 集成测试分别构造当前租约和陈旧租约，验证成功、失败与非法终态转换；迁移测试确认最新版本和 `lock_token` 列。全量测试继续覆盖 Outbox、通知创建和生产启动配置。

## 回滚

V29 只增加可空列。应用回退时旧版本会忽略该列，但不再具备通知终态 fencing；数据库列保留，由后续前向 migration 调整，不删除 Flyway 历史记录。
