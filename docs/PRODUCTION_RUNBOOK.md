# Mamoji Production Runbook

## 部署前检查

- 复制 `.env.production.example` 为 `.env.production`，替换所有默认密码、密钥、域名和邮箱。
- 如同一台服务器存在多套环境，确保 `MAMOJI_COMPOSE_PROJECT_NAME` 不同，避免复用同名 volume。
- 设置 `MAMOJI_RUNTIME_ENVIRONMENT=production`，启用生产启动 guard。guard 会拒绝 demo/open/localhost/default secret 等高风险配置。
- 保持 `MAMOJI_SINGLE_INSTANCE_GUARD_ENABLED=true`。当前进程内读模型只支持一个后端实例；第二个实例会因 PostgreSQL advisory lock 启动失败，禁止使用 `--scale backend=2`。完成数据库直读仓储改造后才能解除该限制。
- 设置 `MAMOJI_BOOTSTRAP_MODE=bootstrap`、`MAMOJI_BOOTSTRAP_ADMIN_EMAIL` 和 `MAMOJI_BOOTSTRAP_ADMIN_PASSWORD`。它只在首次空库初始化时创建管理员、公司主体和管理员员工档案；系统已有用户后，改密码请走应用内操作。
- 设置 `MAMOJI_BOOTSTRAP_COMPANY_NAME`。生产 bootstrap 模式不会生成测试账号、演示流水、演示员工、演示税费或家庭资产主体。
- 保持 `MAMOJI_FLYWAY_ENABLED=true`，由 Flyway 管理 PostgreSQL schema 版本；只有排障时才临时关闭。
- 保持 `MAMOJI_SCHEMA_COMPATIBILITY_ENABLED=false`，生产不依赖启动时兼容补列。
- 保持 `MAMOJI_REGISTRATION_MODE=invite`，生产环境不开放公开注册。首次管理员登录后，通过 `POST /api/v1/auth/invitations` 创建新用户邀请。
- 设置 `MAMOJI_ALLOWED_ORIGINS` 为生产前端域名，多个域名用英文逗号分隔；不要在生产保留本地开发来源。
- 保持 `MAMOJI_PASSWORD_MIN_LENGTH=12`、`MAMOJI_PASSWORD_REQUIRE_COMPLEXITY=true`；首次管理员、注册和改密都会执行该策略，复杂度要求至少包含大小写、数字、符号中的三类。
- 登录失败保护默认按账号 5 次锁定 15 分钟，并按来源 50 次锁定 15 分钟；可通过 `MAMOJI_AUTH_MAX_FAILED_ATTEMPTS`、`MAMOJI_AUTH_MAX_FAILED_ATTEMPTS_PER_SOURCE`、`MAMOJI_AUTH_FAILURE_WINDOW_MINUTES`、`MAMOJI_AUTH_LOCK_MINUTES` 调整。
- 保持 `MAMOJI_OUTBOX_ENABLED=true`。当前项目先使用数据库 Outbox 承接异步事件，不直接引入 RocketMQ；详细说明见 `docs/OUTBOX_EVENTS.md`。
- 设置 `MAMOJI_SMOKE_EMAIL` 和 `MAMOJI_SMOKE_PASSWORD`，用于发布后自动冒烟验证。
- 固定 `MAMOJI_CADDY_VERSION`、`MAMOJI_MINIO_VERSION`、`MAMOJI_PROMETHEUS_VERSION` 和 `MAMOJI_BACKUP_HELPER_IMAGE`，不要使用 `latest`。
- 确认服务器只对外开放 `80/443`，Prometheus 端口默认绑定 `127.0.0.1:39090`。
- 核对 `docker-compose.prod.yml` 的单机资源基线是否适合主机：后端 2 CPU/1536 MiB、PostgreSQL 2 CPU/2 GiB、前端 1 CPU/768 MiB、MinIO 与 Prometheus 各 1 CPU/1 GiB、Caddy 0.5 CPU/256 MiB。可用对应的 `MAMOJI_<SERVICE>_CPUS`、`MAMOJI_<SERVICE>_MEMORY_LIMIT` 和 `MAMOJI_<SERVICE>_PIDS_LIMIT` 覆盖；不要让所有容器限制之和超过主机可用内存。
- 保持后端 Docker 停止宽限期大于 Spring 优雅停机窗口：默认分别为 `45s` 和 `30s`。发布或关机时使用 `docker compose stop`，不要直接发送 `SIGKILL`。
- 确认 DNS 已指向部署服务器，`MAMOJI_PUBLIC_HOST` 与证书域名一致。
- 确认磁盘容量足够 PostgreSQL、MinIO 附件和本地备份保留。
- 先在预生产环境跑完登录、员工、薪酬、税务、票据上传和备份恢复演练。

## 首次部署

```bash
cp .env.production.example .env.production
vi .env.production
scripts/check-prod-env.sh
scripts/deploy-prod.sh
docker compose -f docker-compose.prod.yml --env-file .env.production ps
```

健康检查：

```bash
curl -fsS https://$MAMOJI_PUBLIC_HOST/healthz
docker compose -f docker-compose.prod.yml --env-file .env.production exec backend \
  curl -fsS http://localhost:38080/actuator/health/readiness
```

Docker 使用 `/actuator/health/readiness`，其中包含应用 readiness 与数据库检查；`/actuator/health/liveness` 只判断进程自身。公网继续只暴露 `/healthz`，不要开放完整 actuator。

## 运行容量与超时

默认值面向单实例、中小规模生产部署，并避免数据库故障时请求线程无限堆积：

- HTTP：Tomcat 最大 100 个工作线程、10 个预热线程、100 个等待连接、4096 个连接上限；连接建立/请求头等待为 5 秒，Keep-Alive 空闲为 20 秒。
- 数据库：Hikari 最大 20、最小空闲 4；获取连接 5 秒、校验 2 秒、空闲 10 分钟、连接寿命 30 分钟、Keep-Alive 2 分钟；PostgreSQL 建连 5 秒、socket 读写 30 秒。
- 停机：Spring 最多等待 30 秒完成在途请求，Docker 后端停止宽限期为 45 秒。
- JVM：生产容器默认把容器内存的 70% 作为最大堆上限并在 OOM 时退出，由 `restart: unless-stopped` 恢复；修改 `MAMOJI_JAVA_TOOL_OPTIONS` 前应先压测。
- 日志：所有生产容器的 `json-file` 日志默认每文件 20 MiB、保留 5 个，使用 `MAMOJI_LOG_MAX_SIZE`、`MAMOJI_LOG_MAX_FILES` 调整。

常用调优变量包括 `MAMOJI_HTTP_MAX_THREADS`、`MAMOJI_HTTP_ACCEPT_COUNT`、`MAMOJI_DB_POOL_MAX_SIZE`、`MAMOJI_DB_POOL_MIN_IDLE`、`MAMOJI_DB_POOL_CONNECTION_TIMEOUT_MS`、`MAMOJI_DB_SOCKET_TIMEOUT_SECONDS` 和 `MAMOJI_SHUTDOWN_TIMEOUT`。增加 HTTP 线程前先确认数据库池等待、PostgreSQL `max_connections`、CPU 和 p95 延迟；不要仅靠扩大线程池掩盖慢查询。

附件访问：

- MinIO API 和控制台默认不直接暴露公网。
- 后端生成的短时效签名下载 URL 会使用 `MAMOJI_MINIO_EXTERNAL_URL`。
- Caddy 只代理 `/<bucket>/*` 到 MinIO，用于访问已签名的对象 URL。
- Bucket 保持私有，不要开启匿名读。

## 日常发布

1. 拉取代码并确认变更清单。
2. 执行 `mvn --settings docker/maven-settings.xml -f backend/pom.xml test`。
3. 执行 `cd frontend && npm audit --omit=dev --registry=https://registry.npmjs.org && npm run lint && npm run build`。
4. 执行 `scripts/deploy-prod.sh`。脚本会先备份，再重建服务，最后自动冒烟。
5. 人工抽查登录、员工列表、薪酬页、薪酬月结生成/锁定、税务合规、票据上传/下载和审计日志查询。

可在低峰发布时增加并发只读闸门：

```bash
RUN_CONCURRENCY_SMOKE=true scripts/deploy-prod.sh
```

默认并发配置为 8 个 worker、200 个操作、p95 不高于 2 秒且不允许请求错误。该闸门不执行业务写入。

## 并发烟测

并发只读烟测覆盖当前主体的登录态、经营概览、流水列表、账户汇总、活动预算、票据汇总和企业汇总，并输出总量及各接口 p50/p95/p99：

```bash
MAMOJI_LOAD_CONCURRENCY=8 \
MAMOJI_LOAD_OPERATIONS=200 \
MAMOJI_LOAD_P95_LIMIT_MS=2000 \
MAMOJI_LOAD_MAX_ERROR_RATE_PERCENT=0 \
scripts/concurrency-smoke.sh
```

可通过 `MAMOJI_LOAD_COMPANY_ID` 固定主体，通过 `MAMOJI_LOAD_TOKEN` 使用预先签发的测试 token；未提供 token 时使用 `MAMOJI_LOAD_EMAIL/MAMOJI_LOAD_PASSWORD`，再回退到 smoke 账号。脚本默认预热一轮，各请求有独立 10 秒超时。

混合模式只用于预生产或明确维护窗口。它会按 `MAMOJI_LOAD_WRITE_EVERY` 的频率创建唯一临时费用分类并立即删除，最后再次清理未完成项；不会写流水、账户余额、薪酬或税务数据，但仍可能留下审计记录：

```bash
MAMOJI_LOAD_MODE=mixed \
MAMOJI_LOAD_ALLOW_WRITES=yes \
MAMOJI_LOAD_CONCURRENCY=8 \
MAMOJI_LOAD_OPERATIONS=200 \
MAMOJI_LOAD_WRITE_EVERY=20 \
scripts/concurrency-smoke.sh
```

不要在备份、恢复、Flyway 迁移或高峰期运行。任何错误率/p95 超阈值、临时分类清理失败都会使脚本以非零状态退出。

## 备份

手工备份：

```bash
scripts/backup-prod.sh
```

脚本会进入短暂维护窗口：暂停 Caddy、前端、后端写入和 MinIO，完成 PostgreSQL dump 与静止对象卷快照后恢复原先运行的服务。请安排在低峰期，并让外部探针对该窗口使用合理的告警延迟。

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
- 后端就绪: `/actuator/health/readiness`
- 后端存活: `/actuator/health/liveness`
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
- `scripts/check-prod-env.sh` 通过，且生产启动 guard 未报错。
- `MAMOJI_ALLOWED_ORIGINS` 只包含生产域名，`MAMOJI_PASSWORD_REQUIRE_COMPLEXITY=true`。
- 公网只开放 `80/443`；PostgreSQL、后端、前端、MinIO API/Console 不直接暴露公网。
- `docker compose -f docker-compose.prod.yml --env-file .env.production ps` 全部 healthy。
- `scripts/backup-prod.sh` 成功生成备份，且 `SHA256SUMS` 校验通过。
- 在预生产环境执行过 `CONFIRM_RESTORE=yes scripts/restore-prod.sh <backup-dir>`。
- `scripts/smoke-prod.sh` 通过。
- `scripts/concurrency-smoke.sh` 的并发只读模式通过，并已记录并发数、p95、错误率和主机资源水位。
- 管理员能查询 `/api/v1/audit-logs`，并能看到登录、员工、税务和权限变更记录。
- `outbox_events` 无 `dead` 事件，关键动作能产生并消费 Outbox 事件。
- 生产注册入口必须携带有效邀请 token；无邀请的公开注册请求应返回 403。
- 薪酬页能生成当月批次，锁定后批次状态为 `closed`，审计日志能查到 `payroll_run`。
- 已记录最近一次可回滚代码 tag 或镜像 tag。
