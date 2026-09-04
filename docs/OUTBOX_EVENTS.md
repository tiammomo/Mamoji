# Outbox Events

Mamoji 当前不直接依赖 RocketMQ。生产环境先使用数据库 Outbox 表承接异步事件，保证关键业务动作落库后有可追踪、可重试的事件记录。

## 事件表

核心表：`outbox_events`

主要状态：

- `pending`：已写入，等待本地消费者处理。
- `processing`：消费者已通过唯一 `lock_token` 抢占，正在处理。
- `failed`：处理失败，等待下次重试。
- `processed`：已处理完成。
- `dead`：超过最大重试次数，需要人工排查。

## 默认配置

```env
MAMOJI_OUTBOX_ENABLED=true
MAMOJI_OUTBOX_CONSUMER_ENABLED=true
MAMOJI_OUTBOX_CONSUMER_FIXED_DELAY_MS=5000
MAMOJI_OUTBOX_CONSUMER_BATCH_SIZE=20
MAMOJI_OUTBOX_CONSUMER_MAX_ATTEMPTS=8
MAMOJI_OUTBOX_CONSUMER_STALE_LOCK_MINUTES=10
```

## 已接入事件

- `auth.user.registered`
- `auth.registration_invite.created`
- `auth.registration_invite.accepted`
- `payroll.run.created`
- `payroll.run.closed`
- `enterprise.<entity>.<action>`
- `receipt_voucher.<action>`
- `accounting.account.<action>`
- `operations.category.<action>`

## 运维查询

查看积压：

```sql
SELECT status, count(*)
FROM outbox_events
GROUP BY status
ORDER BY status;
```

查看死信：

```sql
SELECT id, event_type, aggregate_type, aggregate_id, attempts, last_error, updated_at
FROM outbox_events
WHERE status = 'dead'
ORDER BY updated_at DESC
LIMIT 50;
```

手工重试死信：

```sql
UPDATE outbox_events
SET status = 'pending',
    next_attempt_at = NULL,
    locked_at = NULL,
    lock_token = NULL,
    last_error = NULL,
    updated_at = now()::text
WHERE id = :event_id
  AND status = 'dead';
```

## 消费租约与重复投递

每次认领事件都会生成新的 `lock_token`。处理成功或失败时，消费者必须同时匹配事件 ID、`processing` 状态和当前令牌才能更新终态。陈旧处理锁被恢复后，旧工作线程即使稍后返回，也不能覆盖新消费者写入的状态。

Outbox 保证至少一次投递，不承诺外部副作用天然只执行一次。站内通知使用 `event_id` 生成去重键；后续消息适配器也必须把 `event_id` 作为消息键，并由下游按该键实现幂等。

## 未来接 RocketMQ

未来需要 RocketMQ 时，保留业务侧 `OutboxEventService.publish(...)` 不变，只替换 `OutboxEventHandler` 的实现，把本地日志处理改为 RocketMQ 发布即可。发布到 RocketMQ 成功且当前消费租约仍有效后再标记 `processed`，失败继续沿用现有重试和死信机制。
