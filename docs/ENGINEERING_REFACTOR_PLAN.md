# Mamoji 后端工程重构计划

## 目标

在保持企业 API 和交付稳定性的前提下，将现有横向大类逐步迁移为模块化单体。先固定旧行为，再切出单一业务用例，最后删除旧入口，避免同时改包结构、数据库和接口契约。

## 当前优先级

| 优先级 | 当前问题 | 目标 | 状态 |
| --- | --- | --- | --- |
| P0 | 默认能力范围过宽 | 默认开放经营、财务、证据和权限核心，其他能力显式启用 | 已落地 |
| P0 | `/admin/users` 依赖 `people-core` | 权限管理归属 `access-management` | 已落地 |
| P0 | 文档残留 SQLite | 统一为 PostgreSQL、MinIO 和 Docker Compose | 已落地 |
| P1 | `EnterpriseStore` 同时承担建表、种子、HR、税务、票据和查询 | 按数据所有权拆成模块仓储，正式 schema 只归 Flyway | 进行中：票据已迁入 `evidence`，税务事项已迁入 `tax`，部门已迁入 `people`；剩余公司、员工与任职事件兼容状态待拆分 |
| P1 | `InMemoryStore` 名称与 JDBC 事实不符且责任过多 | 拆为 identity、operations、finance、budget、recurring 仓储 | 进行中：用户账户、权限、流水、分类、账户、账本、预算和周期事项均已有模块仓储；上述核心业务对象的进程缓存、双写及兼容入口已移除，流水由 V17、资金账户由 V18、账本及成员由 V19、分类由 V20 强类型 schema 保护；旧类当前只保留首次用户引导和通用时间/金额工具，后续继续拆名与收口 |
| P1 | `AccountingService` 同时编排账户、流水、预算和报表 | 拆为独立应用用例，跨模块通过契约协作 | 已落地：流水和分类归属 `operations`，账户、账本、成员和对账归属 `finance`，旧服务已删除 |
| P1 | 预算仅按已入账流水事后汇总，并发写入可同时超额 | 引入占用、确认、释放账本，以预算行锁串行化容量检查 | 已落地 |
| P1 | 审批状态转换依赖应用服务内的字符串条件 | 使用纯领域状态机声明允许的转换、关联状态和意见要求 | 已落地 |
| P1 | `Models` 聚合多个无关可变模型 | 模型回到所属模块，优先使用不可变值对象 | 进行中：身份、权限、预算、账户、账本、账本成员、分类、流水、周期事项、税务事项和部门已迁回所属模块 |
| P1 | 多个接口使用 `Map<String, Object>` | 强类型 DTO、Bean Validation、统一 Problem Detail | 进行中：流水读写、分类、预算、权限、身份认证、审批、周期事项、税务事项和部门写命令已完成 |
| P2 | 启动期兼容建表与 Flyway 并存 | 完成迁移后删除生产兼容 DDL | 已落地：运行时 DDL 与兼容开关已删除，所有环境统一由 Flyway 管理 schema |
| P2 | 测试集中在少数大文件 | 按模块建立领域单测和 Testcontainers 集成测试 | 已落地：跨域套件已拆为身份访问、会计经营和企业工作流三组独立 PostgreSQL 测试 |
| P2 | 登录失败计数仅保存在单进程内存 | PostgreSQL 原子计数、重启保留、代理来源显式信任 | 已落地 |
| P2 | 本地会话读写混在 `InMemoryStore` 且保留明文兼容回退 | Platform Identity 专属仓储、摘要约束、过期清理和恢复撤销 | 已落地 |
| P2 | 注册邀请由 `InMemoryStore` 缓存且数据库/列表暴露可用明文凭证 | 专属邀请仓储、一次披露、摘要存储和并发单次消费 | 已落地 |
| P2 | 本地用户账户同时存在 PostgreSQL 与进程缓存两份权威状态 | Platform Identity 专属仓储、数据库直读、比较更新和完整性约束 | 已落地 |
| P3 | 本地 Outbox handler 与消息发布未形成显式适配层 | 增加可选 RocketMQ adapter | 进行中：消费租约已增加唯一令牌和终态 fencing，外部发布适配器待接入 |

## 目标包边界

```text
com.mamoji
  platform/
    identity/          登录身份解析
    tenant/            公司成员关系
    access/            权限判定与数据范围
    product/           产品能力开关
  accessmanagement/    成员和角色维护
  expense/             费用申请与状态机
  approval/            审批任务与轨迹
  budget/              预算及占用
  operations/          经营流水和分类
  recurring/           周期规则与执行游标
  finance/             账户、账本和对账
  evidence/            票据及对象存储
  tax/                 税务事项及申报/缴款状态
  people/              部门、员工及任职信息
  notification/        Outbox、通知和外部投递
  workspace/           跨模块只读投影
```

允许的模块内依赖方向：

```text
api -> application -> domain
          |
          +-> repository/port <- infrastructure
```

跨模块写操作由应用层契约或领域事件协调。模块不得直接修改其他模块拥有的表；`workspace` 可以读取投影，但不能成为写入口。

## 下一批变更

1. 继续缩减 `EnterpriseStore` 的公开兼容读模型，并把公司、员工和任职事件迁入职责明确的仓储；部门已由 People Core 专属仓储维护，税务事项及会计核心对象已完成数据库单一事实源改造；
2. 评估通知 Outbox 的外部消息适配层与失败重试边界；
3. 将 `InMemoryStore` 中剩余的首次用户引导与通用工具拆到职责明确的组件并完成旧类退场。

每批变更只处理一个业务边界，并保持可独立回滚。若必须同时修改三个以上业务模块，先补充架构决策记录、数据迁移方案和回滚路径。

## PostgreSQL 集成测试套件

- `IdentityAndAccessIntegrationTest`：登录、邀请注册、管理员保护及其并发约束、公司角色和部门数据范围；
- `AccountingOperationsIntegrationTest`：账户、分类、流水、退款、预算、对账及其数据库锁与并发完整性；
- `EnterpriseWorkflowIntegrationTest`：票据报销、审批、周期入账、税务事项、全局搜索、人力成本及其幂等并发约束。

三个套件均复用 `AbstractPostgresIntegrationTest` 中的 HTTP、数据库锁和数据构造夹具，但各自启动独立 PostgreSQL 容器。这样既可以按模块单独执行，又不会通过测试顺序共享数据库状态。

## 完成标准

- 旧 API 契约保持兼容，或提供清晰的版本迁移说明；
- 所有新增写路径具备权限、校验、幂等和审计边界；
- 领域规则有纯单元测试，持久化与并发行为有 PostgreSQL 集成测试；
- 数据库对象只归 Flyway 管理，应用启动过程不执行运行时 DDL；
- Docker Compose、CI、部署脚本和运维文档可复现验证；
- 性能结论包含数据规模、测试命令、原始结果和执行计划。
