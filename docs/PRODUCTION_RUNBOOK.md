# Mamoji Production Runbook

## 部署前检查

- 复制 `.env.production.example` 为 `.env.production`，替换所有默认密码、密钥、域名和邮箱。
- 如同一台服务器存在多套环境，确保 `MAMOJI_COMPOSE_PROJECT_NAME` 不同，避免复用同名 volume。
- 设置 `MAMOJI_BOOTSTRAP_MODE=bootstrap`、`MAMOJI_BOOTSTRAP_ADMIN_EMAIL` 和 `MAMOJI_BOOTSTRAP_ADMIN_PASSWORD`。它只在首次空库初始化时创建管理员、公司主体和管理员员工档案；系统已有用户后，改密码请走应用内操作。
- 设置 `MAMOJI_BOOTSTRAP_COMPANY_NAME`。生产 bootstrap 模式不会生成测试账号、演示流水、演示员工、演示税费或家庭资产主体。
- 保持 `MAMOJI_FLYWAY_ENABLED=true`，由 Flyway 管理 PostgreSQL schema 版本；只有排障时才临时关闭。
- 保持 `MAMOJI_REGISTRATION_MODE=invite`，生产环境不开放公开注册。首次管理员登录后，通过 `POST /api/v1/auth/invitations` 创建新用户邀请。
- 设置 `MAMOJI_ALLOWED_ORIGINS` 为生产前端域名，多个域名用英文逗号分隔；不要在生产保留本地开发来源。
- 保持 `MAMOJI_PASSWORD_MIN_LENGTH=12`、`MAMOJI_PASSWORD_REQUIRE_COMPLEXITY=true`；首次管理员、注册和改密都会执行该策略，复杂度要求至少包含大小写、数字、符号中的三类。
- 登录失败保护默认按账号 5 次锁定 15 分钟，并按来源 50 次锁定 15 分钟；可通过 `MAMOJI_AUTH_MAX_FAILED_ATTEMPTS`、`MAMOJI_AUTH_MAX_FAILED_ATTEMPTS_PER_SOURCE`、`MAMOJI_AUTH_FAILURE_WINDOW_MINUTES`、`MAMOJI_AUTH_LOCK_MINUTES` 调整。
- 保持 `MAMOJI_OUTBOX_ENABLED=true`。当前项目先使用数据库 Outbox 承接异步事件，不直接引入 RocketMQ；详细说明见 `docs/OUTBOX_EVENTS.md`。
- 设置 `MAMOJI_SMOKE_EMAIL` 和 `MAMOJI_SMOKE_PASSWORD`，用于发布后自动冒烟验证。
- 确认服务器只对外开放 `80/443`，Prometheus 端口默认绑定 `127.0.0.1:39090`。
- 确认 DNS 已指向部署服务器，`MAMOJI_PUBLIC_HOST` 与证书域名一致。
- 确认磁盘容量足够 PostgreSQL、MinIO 附件和本地备份保留。
- 先在预生产环境跑完登录、员工、薪酬、税务、票据上传和备份恢复演练。

## 首次部署

```bash
cp .env.production.example .env.production
vi .env.production
scripts/deploy-prod.sh
docker compose -f docker-compose.prod.yml --env-file .env.production ps
```

健康检查：

```bash
curl -fsS https://$MAMOJI_PUBLIC_HOST/healthz
docker compose -f docker-compose.prod.yml --env-file .env.production exec backend \
  curl -fsS http://localhost:38080/actuator/health
```

如果通过公网不暴露 actuator，可以只保留 `/healthz` 给负载均衡或运维探针使用。

附件访问：

- MinIO API 和控制台默认不直接暴露公网。
- 后端生成的短时效签名下载 URL 会使用 `MAMOJI_MINIO_EXTERNAL_URL`。
- Caddy 只代理 `/<bucket>/*` 到 MinIO，用于访问已签名的对象 URL。
- Bucket 保持私有，不要开启匿名读。

## 日常发布

1. 拉取代码并确认变更清单。
2. 执行 `mvn --settings ~/.local/share/mamoji-tools/maven-settings-aliyun.xml -DskipTests compile`。
3. 执行 `cd frontend && npm run lint && npm run build`。
4. 执行 `scripts/deploy-prod.sh`。脚本会先备份，再重建服务，最后自动冒烟。
5. 人工抽查登录、员工列表、薪酬页、薪酬月结生成/锁定、税务合规、票据上传/下载和审计日志查询。

## 备份

手工备份：

```bash
scripts/backup-prod.sh
```

建议通过 cron 每天执行一次：

```cron
15 2 * * * cd /opt/mamoji && ENV_FILE=/opt/mamoji/.env.production BACKUP_ROOT=/data/mamoji-backups scripts/backup-prod.sh >> /var/log/mamoji-backup.log 2>&1
```

备份产物包含：

- `postgres.dump`：PostgreSQL custom-format dump。
- `minio-data.tar.gz`：MinIO 对象数据。
- `SHA256SUMS`：恢复前校验文件。
- `manifest.env`：备份时间和核心环境信息。

## 恢复

恢复会覆盖当前 PostgreSQL 数据库和 MinIO 数据，必须显式确认：

```bash
CONFIRM_RESTORE=yes scripts/restore-prod.sh /data/mamoji-backups/20260608-021500
```

恢复后检查：

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production ps
curl -fsS https://$MAMOJI_PUBLIC_HOST/healthz
scripts/smoke-prod.sh
```

## 监控

- Prometheus: `http://127.0.0.1:39090`
- 后端指标: `/actuator/prometheus`
- 后端健康: `/actuator/health`
- 公网健康: `/healthz`
- 内置告警规则: `docker/prometheus/alerts.yml`

最低告警建议：

- 后端不可抓取超过 2 分钟。
- `/healthz` 连续失败超过 2 次。
- 后端 5xx 持续升高。
- JVM 堆内存持续高于 90%。
- PostgreSQL 连接池出现等待连接或连接数异常。
- 磁盘可用空间低于 20%。
- 备份任务失败或 24 小时内没有新备份。
- Outbox `dead` 状态事件数量大于 0，或 `pending/failed` 积压持续增长。

Prometheus 已内置后端不可抓取、5xx、堆内存和 HikariCP 等规则；生产通知仍需接入公司现有告警平台或 Alertmanager。

Outbox 积压检查：

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production exec postgres \
  psql -U "$MAMOJI_POSTGRES_USER" -d "$MAMOJI_POSTGRES_DB" \
  -c "SELECT status, count(*) FROM outbox_events GROUP BY status ORDER BY status;"
```

## 回滚

1. 保留上一个可用镜像 tag 或代码 tag。
2. 先备份当前现场。
3. 切回上一版本代码或镜像。
4. 执行 `docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build`。
5. 如果涉及破坏性 schema 变更，使用最近一次备份恢复。

## 审计

管理员可通过 `GET /api/v1/audit-logs` 查询关键操作日志。支持参数：

- `companyId`
- `entityType`
- `action`
- `actorUserId`
- `keyword`
- `page`
- `size`

当前覆盖登录、失败登录、退出、注册、注册邀请、改密码、用户权限、公司主体、部门、员工、薪酬月结、税费事项、主体划转和资金账户变更。

## 投产验收清单

完整清单见 `docs/GO_LIVE_CHECKLIST.md`。

- `.env.production` 中没有 `replace-with`、`example.com` 或默认 MinIO 密钥。
- `MAMOJI_ALLOWED_ORIGINS` 只包含生产域名，`MAMOJI_PASSWORD_REQUIRE_COMPLEXITY=true`。
- 公网只开放 `80/443`；PostgreSQL、后端、前端、MinIO API/Console 不直接暴露公网。
- `docker compose -f docker-compose.prod.yml --env-file .env.production ps` 全部 healthy。
- `scripts/backup-prod.sh` 成功生成备份，且 `SHA256SUMS` 校验通过。
- 在预生产环境执行过 `CONFIRM_RESTORE=yes scripts/restore-prod.sh <backup-dir>`。
- `scripts/smoke-prod.sh` 通过。
- 管理员能查询 `/api/v1/audit-logs`，并能看到登录、员工、税务和权限变更记录。
- `outbox_events` 无 `dead` 事件，关键动作能产生并消费 Outbox 事件。
- 生产注册入口必须携带有效邀请 token；无邀请的公开注册请求应返回 403。
- 薪酬页能生成当月批次，锁定后批次状态为 `closed`，审计日志能查到 `payroll_run`。
- 已记录最近一次可回滚代码 tag 或镜像 tag。
