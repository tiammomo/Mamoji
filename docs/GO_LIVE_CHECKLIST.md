# Mamoji Go-Live Checklist

## P0 发布闸门

- `.env.production` 已从 `.env.production.example` 复制，并替换所有 `replace-with`、`example.com`、默认密码和默认 MinIO 密钥。
- `MAMOJI_RUNTIME_ENVIRONMENT=production`，`scripts/check-prod-env.sh` 已通过，生产启动 guard 未报错。
- `MAMOJI_SINGLE_INSTANCE_GUARD_ENABLED=true`，且部署未配置多个 backend 副本。
- `MAMOJI_BOOTSTRAP_MODE=bootstrap`，首次管理员密码长度不少于 12 位，且至少包含大小写、数字、符号中的三类。
- `MAMOJI_SCHEMA_COMPATIBILITY_ENABLED=false`，生产只依赖 Flyway migration。
- `MAMOJI_REGISTRATION_MODE=invite`，生产注册只允许邀请链接。
- `MAMOJI_ALLOWED_ORIGINS` 只包含生产域名，例如 `https://mamoji.example.com`。
- `MAMOJI_PASSWORD_REQUIRE_COMPLEXITY=true`，`MAMOJI_PASSWORD_MIN_LENGTH>=12`。
- `MAMOJI_AUTH_MAX_FAILED_ATTEMPTS`、`MAMOJI_AUTH_MAX_FAILED_ATTEMPTS_PER_SOURCE`、锁定窗口和锁定时长已确认符合公司安全策略。
- `MAMOJI_OUTBOX_ENABLED=true`，`MAMOJI_OUTBOX_CONSUMER_ENABLED=true`，异步事件先走数据库 Outbox。
- Caddy、MinIO、Prometheus 和备份 helper 镜像均固定明确版本，不使用 `latest`。
- 公网只开放 `80/443`；PostgreSQL、后端、前端、MinIO API/Console 和 Prometheus 不直接暴露公网。
- 已根据主机容量复核各服务 CPU、内存和 PID 限制，容器限制总和不会挤占宿主机与文件缓存所需余量。
- 后端 Docker 停止宽限期大于 Spring 优雅停机窗口，Hikari 连接池上限与 PostgreSQL `max_connections` 留有运维连接余量。
- `docker compose -f docker-compose.prod.yml --env-file .env.production config` 通过。
- `mvn --settings docker/maven-settings.xml -f backend/pom.xml test`、`npm audit --omit=dev --registry=https://registry.npmjs.org`、`npm run lint`、`npm run build` 全部通过。

## 数据与备份

- `MAMOJI_FLYWAY_ENABLED=true`，数据库迁移由 Flyway 管理。
- 正式投产前已执行一次 `scripts/backup-prod.sh`。
- 已在预生产或临时恢复环境执行 `CONFIRM_RESTORE=yes scripts/restore-prod.sh <backup-dir>` 并验证业务可用。
- 备份目录有独立磁盘或外部对象存储同步策略。
- 已配置每日备份 cron，保留周期与公司数据恢复要求一致。
- 已确认备份维护窗口会短暂停止入口、后端写入和 MinIO，并为探针设置了合理告警延迟。
- 已记录最近一次可回滚代码 tag、镜像 tag 或发布包版本。

## 业务验收

- 管理员可登录并通过邀请创建新用户。
- 员工档案字段完整：基础身份、任职信息、合同信息、学历、毕业年份、技能证书、履历、紧急联系人和薪酬相关字段。
- 薪酬页可生成当月批次，批次锁定后不能被误改，审计日志可查到 `payroll_run`。
- 税务合规页可看到增值税、附加税、企业所得税、个税/社保、公积金、发票和申报提醒。
- 附件上传、签名下载和 MinIO 私有 bucket 策略已验证。
- 通知中心可看到薪酬、税务、票据或人员事件；如启用外部 Webhook，测试投递已成功。
- 关键操作审计可查：登录、失败登录、退出、注册邀请、权限、员工、薪酬、税务和公司主体变更。

## 监控与运维

- Prometheus 可访问 `http://127.0.0.1:39090` 并成功抓取 `mamoji-backend`。
- `docker/prometheus/alerts.yml` 中的后端不可用、5xx、堆内存和连接池告警规则已加载。
- `outbox_events` 没有 `dead` 状态事件，`pending/failed` 没有持续积压。
- `notification_deliveries` 没有 `dead` 状态投递，外部 Webhook 没有持续失败。
- 告警通知渠道已接入公司现有平台，或已规划 Alertmanager 接入。
- `/healthz` 已接入负载均衡或外部探针。
- Docker 后端探针使用 `/actuator/health/readiness` 且数据库中断时会转为非就绪；`/actuator/health/liveness` 不依赖外部服务。
- 磁盘空间、CPU、内存、PostgreSQL volume、MinIO volume 已纳入主机级监控。
- 发布后执行 `scripts/smoke-prod.sh` 并人工抽查登录、员工、薪酬、税务、附件和审计日志。
- 在预生产执行 `scripts/concurrency-smoke.sh` 只读模式并记录并发数、p95/p99、错误率、Hikari 等待和 CPU/内存；混合模式只在显式允许写入的维护窗口执行，且确认临时分类已清理。
- 在预生产执行 `MAMOJI_WORKFLOW_ALLOW_WRITES=yes scripts/workflow-smoke.sh`，确认账户、分类、流水新增/修改/删除与余额回滚闭环通过，且临时业务数据已清理。
