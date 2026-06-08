# Mamoji Go-Live Checklist

## P0 发布闸门

- `.env.production` 已从 `.env.production.example` 复制，并替换所有 `replace-with`、`example.com`、默认密码和默认 MinIO 密钥。
- `MAMOJI_BOOTSTRAP_MODE=bootstrap`，首次管理员密码长度不少于 12 位，且至少包含大小写、数字、符号中的三类。
- `MAMOJI_REGISTRATION_MODE=invite`，生产注册只允许邀请链接。
- `MAMOJI_ALLOWED_ORIGINS` 只包含生产域名，例如 `https://mamoji.example.com`。
- `MAMOJI_PASSWORD_REQUIRE_COMPLEXITY=true`，`MAMOJI_PASSWORD_MIN_LENGTH>=12`。
- `MAMOJI_AUTH_MAX_FAILED_ATTEMPTS`、`MAMOJI_AUTH_MAX_FAILED_ATTEMPTS_PER_SOURCE`、锁定窗口和锁定时长已确认符合公司安全策略。
- `MAMOJI_OUTBOX_ENABLED=true`，`MAMOJI_OUTBOX_CONSUMER_ENABLED=true`，异步事件先走数据库 Outbox。
- 公网只开放 `80/443`；PostgreSQL、后端、前端、MinIO API/Console 和 Prometheus 不直接暴露公网。
- `docker compose -f docker-compose.prod.yml --env-file .env.production config` 通过。

## 数据与备份

- `MAMOJI_FLYWAY_ENABLED=true`，数据库迁移由 Flyway 管理。
- 正式投产前已执行一次 `scripts/backup-prod.sh`。
- 已在预生产或临时恢复环境执行 `CONFIRM_RESTORE=yes scripts/restore-prod.sh <backup-dir>` 并验证业务可用。
- 备份目录有独立磁盘或外部对象存储同步策略。
- 已配置每日备份 cron，保留周期与公司数据恢复要求一致。
- 已记录最近一次可回滚代码 tag、镜像 tag 或发布包版本。

## 业务验收

- 管理员可登录并通过邀请创建新用户。
- 员工档案字段完整：基础身份、任职信息、合同信息、学历、毕业年份、技能证书、履历、紧急联系人和薪酬相关字段。
- 薪酬页可生成当月批次，批次锁定后不能被误改，审计日志可查到 `payroll_run`。
- 税务合规页可看到增值税、附加税、企业所得税、个税/社保、公积金、发票和申报提醒。
- 附件上传、签名下载和 MinIO 私有 bucket 策略已验证。
- 关键操作审计可查：登录、失败登录、退出、注册邀请、权限、员工、薪酬、税务和公司主体变更。

## 监控与运维

- Prometheus 可访问 `http://127.0.0.1:39090` 并成功抓取 `mamoji-backend`。
- `docker/prometheus/alerts.yml` 中的后端不可用、5xx、堆内存和连接池告警规则已加载。
- `outbox_events` 没有 `dead` 状态事件，`pending/failed` 没有持续积压。
- 告警通知渠道已接入公司现有平台，或已规划 Alertmanager 接入。
- `/healthz` 已接入负载均衡或外部探针。
- 磁盘空间、CPU、内存、PostgreSQL volume、MinIO volume 已纳入主机级监控。
- 发布后执行 `scripts/smoke-prod.sh` 并人工抽查登录、员工、薪酬、税务、附件和审计日志。
