# Mamoji Go-Live Checklist

## P0 发布闸门

- `.env.production` 已从 `.env.production.example` 复制，并替换所有 `replace-with`、`example.com`、默认密码和默认 MinIO 密钥。
- `MAMOJI_RUNTIME_ENVIRONMENT=production`，`scripts/check-prod-env.sh` 已通过，生产启动 guard 未报错。
- `MAMOJI_SINGLE_INSTANCE_GUARD_ENABLED=true`，且部署未配置多个 backend 副本。
- `MAMOJI_BOOTSTRAP_MODE=bootstrap`，首次管理员密码长度不少于 12 位，且至少包含大小写、数字、符号中的三类；该模式不会向 Spring 上下文注册 demo 业务初始化器。
- 应用启动不执行运行时 DDL，生产 schema 只由已校验的 Flyway migration 管理。
- 从 V13 及更早版本升级前，已确认 `users` 不存在规范化后重复邮箱、非法角色/权限、空密码摘要或不可解析时间戳，V14 预检可以通过。
- 升级到 V15 前，已确认 `recurring_items` 都有有效用户和公司归属，金额、频率、日期、日历字段及执行游标满足 V15 预检约束。
- 升级到 V16 前，已确认 `budgets` 都有有效用户和公司归属，账本/费用分类属于同一公司，金额最多两位小数，日期、时间戳和风险投影满足 V16 预检约束。
- 升级到 V17 前，已确认 `transactions` 都有有效用户和公司归属，账户/分类/账本/预算/原流水属于同一公司，收入与支出分类匹配，退款指向同用户原支出，金额最多四位小数，幂等键、日期和生命周期时间满足 V17 预检约束。
- 升级到 V18 前，已确认 `accounts` 都有有效用户和公司归属，账本属于同一公司，金额最多四位小数，信用额度与冻结金额非负，账户类型、币种、状态、对账日期和生命周期时间满足 V18 预检约束。
- 升级到 V19 前，已确认 `ledgers` 均有有效公司归属，所有者及成员都是同公司的有效 `company_memberships`，每公司最多一个默认账本且不存在重复成员；名称、描述、币种、状态、角色和生命周期时间满足 V19 预检约束。历史注册产生的无公司账本必须先核实业务引用并显式修复或清理，迁移不会静默删除。
- 升级到 V20 前，已确认 `categories` 均有有效公司、用户及对应 `company_memberships`，同一用户在同一公司内不存在规范化后同类型同名分类；名称、图标、六位十六进制颜色、收支类型、状态和生命周期时间满足 V20 预检约束。无公司分类不会被静默猜测归属，应先按真实业务关系显式修复。
- 升级到 V21 前，已确认 `tax_items` 均归属有效公司，同一公司不存在规范化后同税种同期间重复事项；金额、税率、期间频率、申报/缴款状态、责任人、政策口径、日期和生命周期时间满足 V21 预检约束。孤立或非法税务事项不会被静默删除或猜测修复。
- 升级到 V22 前，已确认 `departments` 均归属有效公司，同一公司不存在忽略大小写后的同名部门，预算非负且最多两位小数，状态和生命周期时间有效；部门负责人、员工档案及公司成员的部门引用均在同一公司内。孤立或跨公司的组织关系不会被迁移静默修复。
- 升级到 V23 前，已确认 `employees` 均归属有效公司，账号、档案核验人、部门及直属经理引用有效且不存在跨公司直属经理；同一公司不存在规范化后重复邮箱、工号或账号绑定，薪酬与缴费数据非负且最多四位小数，人员状态、日期顺序和生命周期时间满足 V23 预检约束。非法员工数据不会被迁移静默删除或猜测归属。
- 升级到 V24 前，已确认 `employment_events` 均归属有效公司和同公司员工，操作者账号有效，事件类型仅为入职、离职或状态变更，备注非空且生效日期、创建时间可解析。孤立、跨公司或非法任职事件不会被迁移静默删除或改写业务归属。
- 升级到 V25 前，已确认 `companies` 的所有者账号有效，主体类型、名称、行业、纳税人类型、三位币种、地区、政策画像、财年月份和生命周期时间均合法，且规范化后的非空统一信用代码全局唯一。迁移会延续旧版本对空主体类型、中国地域默认值、深圳地域/政策画像和非法财年起始月的确定性补全；已在预生产核对补全结果。除此之外，孤立所有者、重复信用代码或非法主体资料不会被迁移静默删除或猜测修复。
- 升级到 V26 前，已确认 `entity_transfers` 的来源主体、目标主体和操作者均有效，来源与目标不同，金额为正且不超过四位小数，类型、三位币种、日期、状态和生命周期时间合法。孤立主体/操作者、自划转、未知类型或非法金额不会被静默改写。
- 升级到 V27 前，已确认 `receipt_vouchers.amount`、`tax_amount` 与 `tax_rate` 均为空值或合法数字，并已在备份副本核对一次性派生默认值回填结果；生产应用启动后不再执行票据全表兼容修复。
- `MAMOJI_REGISTRATION_MODE=invite`，生产注册只允许邀请链接。
- 已确认邀请原始 token 只在创建时展示一次，并使用受控渠道交付；邀请列表和数据库均不保存可直接使用的明文凭证。
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
- 如使用应用内结构化备份，已确认格式为 `2.2`，且公司成员关系、账本成员公司范围与预算占用账本均包含在导出数据集中；恢复旧 `2.1`/`2.0` 文件前已排除无公司账本、无公司分类、孤立或重复税务事项、无公司成员关系及跨公司部门引用。
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
