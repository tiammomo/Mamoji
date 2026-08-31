# Mamoji 后端工程重构计划

## 目标

在保持企业 API 和交付稳定性的前提下，将现有横向大类逐步迁移为模块化单体。先固定旧行为，再切出单一业务用例，最后删除旧入口，避免同时改包结构、数据库和接口契约。

## 当前优先级

| 优先级 | 当前问题 | 目标 | 状态 |
| --- | --- | --- | --- |
| P0 | 默认能力范围过宽 | 默认开放经营、财务、证据和权限核心，其他能力显式启用 | 已落地 |
| P0 | `/admin/users` 依赖 `people-core` | 权限管理归属 `access-management` | 已落地 |
| P0 | 文档残留 SQLite | 统一为 PostgreSQL、MinIO 和 Docker Compose | 已落地 |
| P1 | `EnterpriseStore` 同时承担建表、种子、HR、税务、票据和查询 | 按数据所有权拆成模块仓储，正式 schema 只归 Flyway | 进行中：票据仓储、规则、演示种子及 DDL 已迁入 `evidence` |
| P1 | `InMemoryStore` 名称与 JDBC 事实不符且责任过多 | 拆为 identity、operations、finance 仓储 | 待处理 |
| P1 | `AccountingService` 同时编排账户、流水、预算和报表 | 拆为独立应用用例，跨模块通过契约协作 | 进行中：创建流水已完整迁入 `operations` |
| P1 | `Models` 聚合多个无关可变模型 | 模型回到所属模块，优先使用不可变值对象 | 待处理 |
| P1 | 多个写接口使用 `Map<String, Object>` | 强类型 DTO、Bean Validation、统一 Problem Detail | 进行中：创建流水和预算已完成 |
| P2 | 启动期兼容建表与 Flyway 并存 | 完成迁移后删除生产兼容 DDL | 待处理 |
| P2 | 测试集中在少数大文件 | 按模块建立领域单测和 Testcontainers 集成测试 | 待处理 |
| P3 | 本地 Outbox handler 与消息发布未形成显式适配层 | 增加可选 RocketMQ adapter | 待处理 |

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
  finance/             账户、账本和对账
  evidence/            票据及对象存储
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

1. 为审批状态转换建立纯领域对象和表驱动测试；
2. 为预算增加占用、确认和释放模型及并发集成测试；
3. 将权限管理的用户模型与全局 `Models` 解耦；
5. 继续将流水更新、冲正和账户调整迁出 `AccountingService`。

每批变更只处理一个业务边界，并保持可独立回滚。若必须同时修改三个以上业务模块，先补充架构决策记录、数据迁移方案和回滚路径。

## 完成标准

- 旧 API 契约保持兼容，或提供清晰的版本迁移说明；
- 所有新增写路径具备权限、校验、幂等和审计边界；
- 领域规则有纯单元测试，持久化与并发行为有 PostgreSQL 集成测试；
- 数据库对象归 Flyway 管理，生产关闭 schema compatibility 后仍能启动；
- Docker Compose、CI、部署脚本和运维文档可复现验证；
- 性能结论包含数据规模、测试命令、原始结果和执行计划。
