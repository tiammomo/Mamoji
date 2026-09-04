# Mamoji Production Runbook

## 部署前检查

- 复制 `.env.production.example` 为 `.env.production`，替换所有默认密码、密钥、域名和邮箱。
- 如同一台服务器存在多套环境，确保 `MAMOJI_COMPOSE_PROJECT_NAME` 不同，避免复用同名 volume。
- 设置 `MAMOJI_RUNTIME_ENVIRONMENT=production`，启用生产启动 guard。guard 会拒绝 demo/open/localhost/default secret 等高风险配置。
- `MAMOJI_BACKEND_REPLICAS` 默认为 1，可按容量改为多个后端副本；部署脚本会显式传给 Compose。扩容前必须按副本总数核算数据库连接、CPU、内存和外部 Webhook 吞吐。
- 当 `MAMOJI_BACKEND_REPLICAS` 大于 1 时，部署脚本会自动执行逐副本运行验收：检查实际副本数和每个实例的 readiness，在第一个实例登录后逐一访问其余实例，并从最后一个实例注销后确认第一个实例立即拒绝旧令牌。只有紧急处置时才使用 `SKIP_REPLICA_SMOKE=true` 跳过，并在发布记录中说明原因。
- 设置 `MAMOJI_BOOTSTRAP_MODE=bootstrap`、`MAMOJI_BOOTSTRAP_ADMIN_EMAIL` 和 `MAMOJI_BOOTSTRAP_ADMIN_PASSWORD`。首次管理员、公司主体、工作区、管理部门和管理员员工档案由同一个事务命令创建；多个副本同时启动时通过 PostgreSQL transaction advisory lock 串行化，失败事务完整回滚，等待实例随后可重试。系统已有公司后，改密码请走应用内操作。
- 设置 `MAMOJI_BOOTSTRAP_COMPANY_NAME`。生产 bootstrap 模式不会注册 demo 初始化器，也不会生成测试账号、演示账户、演示流水、演示员工、演示预算、演示周期事项、演示税费、演示票据或家庭资产主体。
- 保持 `MAMOJI_FLYWAY_ENABLED=true`，由 Flyway 管理 PostgreSQL schema 版本；生产启动 guard 会拒绝关闭该配置。
- 应用运行时不具备兼容建表通道；所有 schema 变更必须先以 Flyway migration 发布。
- 已有公司数据经过 `EnterpriseDataInitializer` 时只执行存在性检查，不扫描或改写公司、员工和成员全表；成员授权以 `company_memberships` 为准，不从职位名称反向推断。若审计发现缺失关系，应依据真实授权记录显式修复。
- 保持 `MAMOJI_REGISTRATION_MODE=invite`，生产环境不开放公开注册。首次管理员登录后，通过 `POST /api/v1/auth/invitations` 创建新用户邀请。
- 注册邀请原始 token 只在创建响应中返回一次，随后列表只展示元数据，PostgreSQL 仅保存 SHA-256 摘要。管理员应立即通过受控渠道发送邀请链接；若丢失原始 token，请创建新邀请，不要尝试从数据库或列表找回。
- 设置 `MAMOJI_ALLOWED_ORIGINS` 为生产前端域名，多个域名用英文逗号分隔；不要在生产保留本地开发来源。
- 保持 `MAMOJI_PASSWORD_MIN_LENGTH=12`、`MAMOJI_PASSWORD_REQUIRE_COMPLEXITY=true`；首次管理员、注册和改密都会执行该策略，复杂度要求至少包含大小写、数字、符号中的三类。
- 登录失败保护默认按账号 5 次锁定 15 分钟，并按来源 50 次锁定 15 分钟；状态以 SHA-256 摘要键保存在 PostgreSQL，不保存邮箱或 IP 明文，应用重启或切换实例不会清空。可通过 `MAMOJI_AUTH_MAX_FAILED_ATTEMPTS`、`MAMOJI_AUTH_MAX_FAILED_ATTEMPTS_PER_SOURCE`、`MAMOJI_AUTH_FAILURE_WINDOW_MINUTES`、`MAMOJI_AUTH_LOCK_MINUTES` 调整。
- 成功登录只清除该账号的失败窗口，不清除来源地址的累计窗口；来源记录在停止失败请求后自动过期并由定时清理任务删除，防止单个有效账号绕过来源级密码喷洒保护。
- 后端直连默认不信任客户端提交的 `X-Forwarded-For`。生产 Compose 仅通过 Caddy 暴露入口，Caddy 会用真实连接地址覆盖外部来源头，并显式启用 `MAMOJI_AUTH_TRUST_FORWARDED_HEADERS=true`；若改变网络拓扑，必须同步复核可信代理边界。
- 本地会话只在 PostgreSQL 保存 SHA-256 令牌摘要和 `TIMESTAMPTZ` 有效期；过期会话每小时自动清理。结构化业务恢复会撤销全部现有会话，恢复完成后管理员必须重新登录，避免旧令牌映射到恢复后的账号数据。
- 本地用户账户以 PostgreSQL 为唯一在线事实来源，不维护进程内用户缓存。角色、权限、资料和密码升级提交后，后续认证与通知收件人计算会直接读取已提交数据。
- 经营流水同样以 PostgreSQL 为唯一在线事实来源，不维护进程内流水副本；交易列表、报表、导入去重和票据关联均读取已提交数据库状态。
- 经营分类同样以 PostgreSQL 为唯一在线事实来源，不维护进程内分类副本；分类按公司和用户共同隔离，同范围同类型名称唯一，已被流水或预算引用后不能改变收支类型。
- 税务事项同样以 PostgreSQL 为唯一在线事实来源，不维护进程内税务副本；同一公司同一税种和期间只能有一条事项，申报/缴款状态和金额关系同时受应用规则与数据库约束保护。
- 账本与账本成员同样只从 PostgreSQL 读写。企业默认账本由数据库唯一索引保证并发下只有一个；成员必须先是同公司的有效成员，账本 owner 成员不能被删除或降级。
- 保持 `MAMOJI_OUTBOX_ENABLED=true`。当前项目先使用数据库 Outbox 承接异步事件，不直接引入 RocketMQ；详细说明见 `docs/OUTBOX_EVENTS.md`。
- 通知提醒默认每 60 秒尝试一次，`MAMOJI_NOTIFICATION_REMINDER_LEASE_MS` 默认 600000。租约应明显大于生产数据量下一次完整税务、人员和票据提醒扫描的 P99 耗时；不要为了加快故障接管把它调到正常扫描时长以内。
- 设置 `MAMOJI_SMOKE_EMAIL` 和 `MAMOJI_SMOKE_PASSWORD`，用于发布后自动冒烟验证。
- 固定 `MAMOJI_CADDY_VERSION`、`MAMOJI_MINIO_VERSION`、`MAMOJI_PROMETHEUS_VERSION` 和 `MAMOJI_BACKUP_HELPER_IMAGE`，不要使用 `latest`。
- 确认服务器只对外开放 `80/443`，Prometheus 端口默认绑定 `127.0.0.1:39090`。
- 核对 `docker-compose.prod.yml` 的单机资源基线是否适合主机：后端 2 CPU/1536 MiB、PostgreSQL 2 CPU/2 GiB、前端 1 CPU/768 MiB、MinIO 与 Prometheus 各 1 CPU/1 GiB、Caddy 0.5 CPU/256 MiB。可用对应的 `MAMOJI_<SERVICE>_CPUS`、`MAMOJI_<SERVICE>_MEMORY_LIMIT` 和 `MAMOJI_<SERVICE>_PIDS_LIMIT` 覆盖；不要让所有容器限制之和超过主机可用内存。
- 保持后端 Docker 停止宽限期大于 Spring 优雅停机窗口：默认分别为 `45s` 和 `30s`。发布或关机时使用 `docker compose stop`，不要直接发送 `SIGKILL`。
- 确认 DNS 已指向部署服务器，`MAMOJI_PUBLIC_HOST` 与证书域名一致。
- 确认磁盘容量足够 PostgreSQL、MinIO 附件和本地备份保留。
- 先在预生产环境跑完登录、员工、薪酬、税务、票据上传和备份恢复演练。

## 首次部署

从 V13 或更早版本升级时，V14 会把用户时间字段转换为 `TIMESTAMPTZ`，并拒绝规范化后重复邮箱、非法角色/权限、空密码摘要或不可解析时间戳。V15 会把周期事项金额和日期分别转换为 `NUMERIC(16,2)` 与 `DATE`，并拒绝空公司归属、孤立用户/公司、非法频率/日历字段以及倒退的执行游标。V16 会把预算金额、日期和时间戳转换为 `NUMERIC(16,2)`、`DATE` 与 `TIMESTAMPTZ`，并拒绝无公司归属、孤立用户、跨公司账本/分类、超过两位小数、非法风险投影或倒序日期时间。V17 会把流水金额、日期、退款标记和生命周期时间转换为 `NUMERIC(18,4)`、`DATE`、`BOOLEAN` 与 `TIMESTAMPTZ`，并拒绝无公司归属、孤立用户、跨公司或缺失的账户/分类/账本/预算/原流水、非法类型与退款关系、越界金额、非法幂等键或倒序时间。V18 会把资金账户金额、日期、净资产标记和生命周期时间转换为 `NUMERIC(20,4)`、`DATE`、`BOOLEAN` 与 `TIMESTAMPTZ`，并拒绝无公司归属、孤立用户、跨公司账本、负信用额度/冻结金额、非法账户类型/币种/状态、倒序对账日期或生命周期时间。V19 会把账本默认标记和账本/成员时间转换为 `BOOLEAN` 与 `TIMESTAMPTZ`，为成员补充公司归属，并拒绝无公司或孤立账本、非公司成员、重复默认账本/成员、非法币种/状态/角色以及缺失或不匹配的 owner 成员。V20 会把分类生命周期转换为 `TIMESTAMPTZ`，规范化名称、图标、颜色和类型，并拒绝无公司归属、孤立用户、缺失公司成员关系、同一用户公司范围内的同类型重名、非法颜色/类型/状态或倒序时间。V21 会把税务事项金额、税率、日期和生命周期时间转换为 `NUMERIC`、`DATE` 与 `TIMESTAMPTZ`，并拒绝孤立公司、规范化后同公司同税种同期间重复、非法税种/期间、申报/缴款状态、过度缴款、错误税率、不可解析日期或倒序时间。V22 会把部门预算和生命周期时间转换为 `NUMERIC(20,2)` 与 `TIMESTAMPTZ`，并拒绝孤立公司、同公司规范化后重名、非法预算/状态以及负责人、员工或成员的跨公司部门引用。V23 会把员工薪酬、日期和生命周期字段转换为 `NUMERIC(20,4)`、`DATE` 与 `TIMESTAMPTZ`，并拒绝孤立公司/用户、跨公司直属经理、规范化后重复邮箱/工号/账号、负数或精度越界的薪酬缴费数据、非法人员状态及倒序日期时间。V24 会把任职事件的生效日期和创建时间转换为 `DATE` 与 `TIMESTAMPTZ`，并拒绝孤立公司/员工/操作者、跨公司员工、非法事件类型、空或过长备注以及不可解析时间。V25 会先延续旧启动逻辑，对空主体类型、中国地域默认值、深圳地域/政策画像和非法财年起始月做确定性补全，再把公司主体生命周期转换为 `TIMESTAMPTZ`、增加乐观锁版本并规范化主体资料与统一信用代码；孤立所有者、重复信用代码和其余非法资料会阻断升级。历史无公司账本、分类、孤立税务事项和跨公司组织关系都不会被迁移静默删除或猜测归属；应先核实业务引用并显式修复或清理。先在备份副本或预生产执行升级并核对 V25 补全结果；迁移失败时修复源数据并重新演练，不要修改已发布 migration 或跳过 Flyway 校验。

V26 会把主体划转金额、日期和生命周期时间转换为 `NUMERIC(20,4)`、`DATE` 与 `TIMESTAMPTZ`，并拒绝孤立主体或操作者、自划转、未知类型、非正或精度越界金额、非法币种/日期/状态及倒序时间。升级前应在备份副本检查这些数据，并确认历史划转只追加、不做原位修改。

V27 会一次性回填历史票据的税期、业务状态、会计分录、会计凭证号、入账时间和文件存储默认值；完成后应用启动不再执行票据全表兼容修复。迁移会在改写前拒绝无法解析的 `amount`、`tax_amount` 或 `tax_rate`，因此升级前应先在备份副本确认这三个文本字段均为空值或合法数字，并核对回填后的票据状态与会计分录。

V28 会把历史公司的所有者成员关系规范为有效 founder，并只在公司完全没有账本时创建默认经营账本；已有账本不会被替换。迁移还会按公司所有者分别补齐缺失的收入或支出分类，已有自定义分类保持不变。升级后新公司由 `CompanyProvisioningService` 在同一业务事务中创建主体、成员、账本和分类，生产启动不再执行公司全表扫描。

V29 为 `notification_deliveries` 增加 Webhook 消费租约令牌。每次认领都会生成新令牌，投递成功、失败重试和死信转换只接受当前令牌，避免超时旧实例覆盖新实例状态。正式 Webhook 请求携带稳定 `Idempotency-Key`；自建接收方必须按该值去重，第三方入口仍按至少一次投递预期处理。

V30 创建 `scheduled_job_leases`。通知提醒先使用 PostgreSQL 时钟原子认领 `notification-reminders`，完成后才推进下一运行时间；实例崩溃后由租约超时允许其他实例接管，陈旧 token 不能写完成或失败状态。该表属于运行协调状态，不进入应用结构化备份，并在结构化恢复时清空。

V31 会先预检票据及文件哈希的租户引用、金额/税率、日期、枚举、长度和生命周期组合，再把票据金额与税率改为 `NUMERIC`、业务日期改为 `DATE`、生命周期时间改为 `TIMESTAMPTZ`，并增加公司复合外键、检查约束、不可变触发器和公司前缀查询索引。存在非法历史行时整个迁移回滚并保持 V30；应按异常信息修复源数据后在备份副本重新演练。该迁移会锁定并重写票据表，且旧应用的字符串 JDBC 写法不兼容新 schema，因此必须安排维护窗口并使用默认一致性备份，不得设置 `SKIP_BACKUP=true`。

生产 bootstrap 不新增 schema。`ProductionBootstrapCommand` 在一个数据库事务内取得固定 transaction advisory lock，并在持锁后重新检查公司是否存在；首次管理员和完整公司工作区要么一起提交，要么一起回滚。原生命周期级单实例连接锁和 `MAMOJI_SINGLE_INSTANCE_GUARD_ENABLED` 已删除。

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

## 多副本运行验收

`scripts/replica-smoke.sh` 直接枚举当前 Compose 项目的后端容器，不依赖负载均衡碰巧把请求分配到不同实例。默认验收不会停止服务，只执行以下检查：

- 实际运行的后端容器数与 `MAMOJI_BACKEND_REPLICAS` 一致，且每个容器的 Docker health 与 Spring readiness 都正常。
- 在一个实例签发的数据库会话令牌可被全部实例读取。
- 从另一个实例注销后，原实例立即返回 401，证明撤销状态也不是进程内状态。

多副本部署会自动运行默认验收，也可以单独重复执行：

```bash
scripts/replica-smoke.sh
```

预生产首次扩容或更改 Caddy、Docker 网络和停止宽限期后，再执行单副本故障切换演练：

```bash
MAMOJI_REPLICA_SMOKE_ALLOW_RESTART=yes scripts/replica-smoke.sh
```

该模式会优雅停止第一个后端容器，确认剩余实例保持就绪，公网 `/healthz` 和带原会话的 `/api/v1/auth/me` 仍可用，然后重新启动被停止实例并确认它恢复就绪且仍接受共享会话。只有在至少两个健康副本的预生产或批准维护窗口执行；脚本异常退出时会尝试恢复被停止的容器并撤销临时会话。原始令牌通过标准输入传给 `curl`，不会出现在 Docker 或 curl 命令参数中。

## 运行容量与超时

默认值面向单副本、中小规模生产部署，并避免数据库故障时请求线程无限堆积；多副本使用相同的每实例默认值：

- HTTP：Tomcat 最大 100 个工作线程、10 个预热线程、100 个等待连接、4096 个连接上限；连接建立/请求头等待为 5 秒，Keep-Alive 空闲为 20 秒。
- 数据库：Hikari 最大 20、最小空闲 4；获取连接 5 秒、校验 2 秒、空闲 10 分钟、连接寿命 30 分钟、Keep-Alive 2 分钟；PostgreSQL 建连 5 秒、socket 读写 30 秒。
- 停机：Spring 最多等待 30 秒完成在途请求，Docker 后端停止宽限期为 45 秒。
- JVM：生产容器默认把容器内存的 70% 作为最大堆上限并在 OOM 时退出，由 `restart: unless-stopped` 恢复；修改 `MAMOJI_JAVA_TOOL_OPTIONS` 前应先压测。
- 日志：所有生产容器的 `json-file` 日志默认每文件 20 MiB、保留 5 个，使用 `MAMOJI_LOG_MAX_SIZE`、`MAMOJI_LOG_MAX_FILES` 调整。

常用调优变量包括 `MAMOJI_BACKEND_REPLICAS`、`MAMOJI_HTTP_MAX_THREADS`、`MAMOJI_HTTP_ACCEPT_COUNT`、`MAMOJI_DB_POOL_MAX_SIZE`、`MAMOJI_DB_POOL_MIN_IDLE`、`MAMOJI_DB_POOL_CONNECTION_TIMEOUT_MS`、`MAMOJI_DB_SOCKET_TIMEOUT_SECONDS` 和 `MAMOJI_SHUTDOWN_TIMEOUT`。增加副本或 HTTP 线程前先确认数据库池等待、PostgreSQL `max_connections`、CPU 和 p95 延迟；数据库连接预算至少按 `MAMOJI_BACKEND_REPLICAS × MAMOJI_DB_POOL_MAX_SIZE` 计算，不要仅靠扩大并发掩盖慢查询。

附件访问：

- MinIO API 和控制台默认不直接暴露公网。
- 后端生成的短时效签名下载 URL 会使用 `MAMOJI_MINIO_EXTERNAL_URL`。
- Caddy 只代理 `/<bucket>/*` 到 MinIO，用于访问已签名的对象 URL。
- Bucket 保持私有，不要开启匿名读。

## 日常发布

1. 拉取代码并确认变更清单。
2. 执行 `mvn --settings docker/maven-settings.xml -f backend/pom.xml test`。
3. 执行 `cd frontend && npm audit --omit=dev --registry=https://registry.npmjs.org && npm run lint && npm run build`。
4. 执行 `scripts/deploy-prod.sh`。脚本会先完成镜像构建，再进入一致性备份窗口；成功备份后旧入口、前端和后端保持停止，直到新服务启动，最后自动冒烟。
5. 多副本部署确认 `scripts/replica-smoke.sh` 自动验收通过；首次扩容在预生产显式执行一次允许重启的故障切换演练。
6. 人工抽查登录、员工列表、薪酬页、薪酬月结生成/锁定、税务合规、票据上传/下载和审计日志查询。

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

应用内结构化备份当前格式为 `2.2`，包含权威 `company_memberships`、带公司范围的 `ledger_members` 和预算占用账本 `budget_reservations`。恢复器仍接受 `2.1` 与旧 `2.0` 文件：缺失的账本成员公司范围会从账本派生，旧邀请明文会在写回前摘要化；`2.0` 缺失的公司成员关系会在恢复账本前从公司负责人和员工档案重建。旧备份若含无公司账本、无公司分类、孤立或重复的税务事项，或无法重建的成员关系会拒绝恢复，不会静默删除；`2.0` 本身也未包含预算占用历史，因此关键生产恢复仍应优先使用 PostgreSQL + MinIO 完整备份。

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
- Webhook 投递出现 `dead`，或 `notification_deliveries` 的 `pending/failed` 持续积压。
- `notification-reminders` 长期没有完成记录，或失败时间持续推进且 `last_error` 未消除。

Prometheus 已内置后端不可抓取、5xx、堆内存和 HikariCP 等规则；生产通知仍需接入公司现有告警平台或 Alertmanager。

Outbox 积压检查：

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production exec postgres \
  psql -U "$MAMOJI_POSTGRES_USER" -d "$MAMOJI_POSTGRES_DB" \
  -c "SELECT status, count(*) FROM outbox_events GROUP BY status ORDER BY status;"
```

Webhook 投递积压检查：

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production exec postgres \
  psql -U "$MAMOJI_POSTGRES_USER" -d "$MAMOJI_POSTGRES_DB" \
  -c "SELECT status, count(*) FROM notification_deliveries GROUP BY status ORDER BY status;"
```

定时任务租约检查：

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production exec postgres \
  psql -U "$MAMOJI_POSTGRES_USER" -d "$MAMOJI_POSTGRES_DB" \
  -c "SELECT job_name, locked_until, next_run_at, last_completed_at, last_failed_at, last_error FROM scheduled_job_leases ORDER BY job_name;"
```

## 回滚

1. 保留上一个可用镜像 tag 或代码 tag。
2. 先备份当前现场。
3. 如果 schema 仍与上一版本兼容，切回上一版本代码或镜像并执行 `docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build`。
4. 如果涉及 V31 等破坏性 schema 变更，保持应用停止，先使用发布前一致性备份恢复 PostgreSQL 与 MinIO，再启动上一版本；不要让旧镜像直接访问新 schema。

## 审计

管理员可通过 `GET /api/v1/audit-logs` 查询关键操作日志。支持参数：

- `companyId`
- `entityType`
- `entityId`
- `action`
- `actorUserId`
- `keyword`
- `page`
- `size`

查询由数据库完成筛选、计数和分页，`size` 取值为 `1-200`，非法筛选或分页参数会返回 `400`，不会退化成无条件全表查询。审计持久化边界只暴露追加与读取操作，员工等业务记录发生变化时不会清理既有审计轨迹。

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
