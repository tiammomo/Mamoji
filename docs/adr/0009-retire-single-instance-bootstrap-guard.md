# ADR 0009：以原子 bootstrap 解除单实例启动保护

- 状态：Accepted
- 日期：2026-09-05

## 背景

生产空库原先先由 `InitialAdminDataInitializer` 创建管理员，再由另一个 `EnterpriseDataInitializer` 回调创建首家公司、工作区和人员档案。两个回调不在同一事务内；并发启动可能同时观察到空表，中途失败也可能留下只有管理员、没有公司的中间态。为隐藏该竞态，应用通过 PostgreSQL session advisory lock 在整个进程生命周期占用一个连接并拒绝第二个后端实例。

在线仓储和会话已经以 PostgreSQL 为事实源，Outbox 与 Webhook 通过行锁和 token fencing 竞争消费，通知提醒由 V30 持久化租约协调。继续保留进程生命周期锁只会阻止水平扩容，并额外永久占用每个部署的一条数据库连接。

## 决策

1. `InitialAdminDataInitializer` 只在 `demo` 模式注册。生产 `EnterpriseDataInitializer` 只负责把配置交给 `ProductionBootstrapCommand`，不再直接分阶段写数据。
2. `ProductionBootstrapCommand.execute` 是单一数据库事务。命令先取得固定键的 PostgreSQL transaction advisory lock，再重新检查是否已经存在公司；只有首个持锁者创建数据，事务提交或回滚时自动释放锁。
3. 首次管理员、公司主体、所有者成员、默认账本和分类、管理部门、创始人员工档案、入职事件及审计记录在同一事务边界内创建。任何一步失败都会撤销此前写入，后续等待实例可以从空库重试。
4. 对“已有用户但没有公司”的历史中间态，命令只查询一个首选管理员并补齐首家公司；已有公司时只做存在性检查，不扫描或改写业务表。
5. 删除 `SingleInstanceDatabaseGuard`、生产强制开关和部署环境变量。部署脚本通过 `MAMOJI_BACKEND_REPLICAS` 显式控制副本数，默认保持 1。

## 影响

- 多个生产后端可以同时启动，不会重复创建租户根或因生命周期锁互相拒绝。
- bootstrap 崩溃不会留下部分管理员/公司数据，数据库连接池也不再永久借出一条连接给 session lock。
- 扩容仍需按副本数合并核算 PostgreSQL 连接、CPU、内存和外部调用容量；删除进程锁不等于忽略容量验证。
- 多副本部署自动运行逐容器 readiness 与跨实例会话/注销验收；单副本停止后的入口接管和副本恢复只在预生产或批准维护窗口显式演练。
- 历史 ADR 0002、0003 和 0008 中关于继续保留单实例保护的阶段性限制由本决策取代。

## 验证

真实 PostgreSQL 集成测试先用非法公司配置触发管理员插入后的失败，确认用户和公司均回滚；随后让第一个 worker 在持锁事务内暂停密码摘要生成，同时启动第二个 worker，确认第二个调用不能提前完成。释放后只允许一个调用返回 `CREATED`，另一个返回 `ALREADY_INITIALIZED`，并校验管理员、公司、成员、账本、分类、部门、员工、任职事件和审计记录都只有一套。

运行级验收通过 `scripts/replica-smoke.sh` 枚举实际 Compose 容器，逐实例验证 readiness，并证明一个实例签发的会话可在其他实例读取、从另一实例注销后立即全局失效。显式故障切换模式还会优雅停止一个后端，验证 Caddy 入口继续处理健康检查和带会话请求，再拉起该实例并确认它恢复就绪且仍可读取共享会话。

## 回滚

本决策不修改 schema。应用镜像可回退；旧版本仍会使用其默认开启的单实例 guard。若已配置多个副本，回退旧镜像前先把 `MAMOJI_BACKEND_REPLICAS` 调回 1，避免其余副本按旧行为启动失败。
