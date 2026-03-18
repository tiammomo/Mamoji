# 数据库设计

## 1. 概览

当前项目以 MySQL 为主存储，Redis 用于缓存与会话/AI 辅助能力。

- 本地 MySQL 端口映射: `33306`
- 本地 Redis 端口映射: `36379`
- JPA 负责实体映射和表结构演进（`ddl-auto=update`）

## 2. 核心实体与表

| 实体 | 表名 | 说明 |
| --- | --- | --- |
| `User` | `users` | 用户信息与权限 |
| `Account` | `account` | 账户与资金容器 |
| `Category` | `categories` | 收支分类 |
| `Transaction` | `transactions` | 交易流水 |
| `Budget` | `budget` | 预算周期与额度 |
| `Ledger` | `ledger` | 账本 |
| `LedgerMember` | `ledger_member` | 账本成员关系 |

## 3. 关键字段说明

### 3.1 users

- `email`: 唯一登录账号
- `password_hash`: 密码哈希
- `role`: 角色值（管理员/普通用户）
- `family_id`: 家庭维度（兼容旧模型）

### 3.2 account

- `name`: 账户名称
- `type`/`sub_type`: 账户类型与细分
- `user_id`: 所属用户
- `ledger_id`: 所属账本

### 3.3 transactions

- `type`: 交易类型（收入/支出/退款等）
- `amount`: 交易金额
- `date`: 交易日期
- `category_id`: 分类关联
- `account_id`: 账户关联
- `original_transaction_id`: 退款关联原始交易
- `budget_id`: 命中预算关联

### 3.4 budget

- `name`: 预算名称
- `amount`: 预算额度
- `start_date`/`end_date`: 生效区间
- `category_id`: 分类预算维度（可空表示总预算）

### 3.5 ledger / ledger_member

- `ledger.owner_id`: 账本创建者
- `ledger_member.role`: 成员角色（owner/admin/editor/viewer）

## 4. 索引建议

建议为以下高频查询字段维护索引:

- `transactions(user_id, date)`
- `transactions(account_id, date)`
- `transactions(category_id, date)`
- `budget(user_id, start_date, end_date)`
- `ledger_member(ledger_id, user_id)`

## 5. 数据一致性建议

1. 交易写入与账户余额更新应在同一事务内。
2. 预算消耗计算应以交易最终状态为准。
3. 退款交易应保留原始交易关联，避免重复统计。
4. 账本成员变更需校验最小管理员数（避免孤儿账本）。

## 6. 备份策略

- 逻辑备份: `mysqldump`
- 备份脚本: `tools/backup`
- 建议保留策略:
  - 日备份 7 天
  - 周备份 4 周
  - 月备份 6 个月

## 7. 迁移与演进

当表结构变更时建议:

1. 先在测试环境执行迁移脚本
2. 验证历史数据兼容
3. 与后端实体字段一起提交
4. 同步更新本文件与 `docs/ARCHITECTURE.md`
5. 如涉及预算或交易写路径，同时检查 `docs/RISK_CONTROL.md`
