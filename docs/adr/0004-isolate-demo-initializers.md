# ADR 0004：从生产 Spring 上下文隔离 demo 初始化器

- 状态：Accepted
- 日期：2026-09-04

## 背景

生产配置已经使用 `MAMOJI_BOOTSTRAP_MODE=bootstrap`，旧实现也会在大部分 demo 初始化器入口判断该模式后返回。但这些初始化器仍会进入生产 Spring Bean 图，构造依赖并参与启动顺序；企业和分类初始化器还把生产首次数据、通用默认值与 demo 夹具放在同一个类中，票据初始化器同时承担历史兼容修复和样例票据写入。

这种“注册后跳过”的隔离难以从容器结构上证明生产不会加载 demo 能力，也让后续拆除启动写路径时无法区分生产必要动作与本地样例数据。

## 决策

1. 使用 `mamoji.bootstrap.mode` 条件装配 demo 初始化器。只有值为 `demo` 时，企业样例、账户、流水、预算、周期事项、税务事项和票据样例 Bean 才会注册；`bootstrap` 上下文中不存在这些 Bean。
2. 将企业初始化拆成两个互斥实现，并继续使用稳定 Bean 名 `enterpriseDataInitializer` 维持后续初始化顺序：
   - `EnterpriseDataInitializer` 只在 `bootstrap` 模式注册，创建首次管理员所属公司、管理部门、管理员员工档案及成员授权；
   - `DemoEnterpriseDataInitializer` 只在 `demo` 模式注册，拥有示例公司、员工、家庭主体、薪酬样例和主体划转夹具。
3. 将分类初始化拆成两个互斥实现，并继续使用稳定 Bean 名 `categoryDataInitializer`：生产实现只创建每个公司的通用收支分类，demo 实现先创建详细样例分类，再确保通用默认值。
4. 将票据历史修复保留在不含样例数据的 `ReceiptVoucherDataInitializer`；样例票据和样例审计移入条件化的 `DemoReceiptVoucherDataInitializer`，并显式等待企业与票据修复初始化完成。
5. 通过轻量 Spring 上下文测试验证两种模式的 Bean 图，通过真实 PostgreSQL 启动测试验证 `bootstrap` 空库只产生最小企业数据且所有 demo 业务表保持为空。

## 数据迁移

本决策不改变表结构、数据格式或 HTTP 契约，不新增 Flyway migration。现有 demo 数据不会被删除，已有生产数据也不会被重写；变化只影响后续应用启动时注册和执行哪些初始化 Bean。

生产首次初始化的数据契约保持不变：管理员、公司、管理部门、管理员员工档案、公司成员关系、默认账本和通用收支分类仍会创建。在线创建公司与员工的业务用例继续显式维护账本、分类和成员关系。

## 影响

- 生产容器不再构造或运行账户、流水、预算、周期事项、税务事项及样例票据初始化器。
- demo 夹具的源码所有权和启动条件可直接从类边界识别，不再与生产首次数据或票据兼容修复混合。
- 本地默认 `demo` 行为和样例数据内容保持兼容；生产 Compose 继续显式设置 `bootstrap`。
- 票据历史兼容修复仍在启动期执行，后续应迁入一次性 Flyway migration，完成后再删除该生产初始化器。

## 回滚

没有 schema 迁移，可以直接回滚应用镜像。回滚后生产 `bootstrap` 模式仍会在各 demo 初始化器方法入口返回，不会补种 demo 数据，但这些 Bean 会重新进入生产上下文；票据兼容修复与首次企业数据格式保持兼容。

## 后续变化

V27 已将第 4 项保留的票据历史修复迁为一次性 Flyway 回填，并删除 `ReceiptVoucherDataInitializer`；详情见 [ADR 0005：以 Flyway 接管票据兼容回填](0005-migrate-receipt-compatibility-repair.md)。

V28 已将第 3 项中的生产通用分类补种和共享账本扫描迁出启动期；生产首次公司与线上新公司统一通过业务事务创建工作区，原账本初始化器仅作为 demo 条件 Bean 保留。详情见 [ADR 0006：将公司工作区创建收口到业务事务](0006-provision-company-workspaces-in-write-path.md)。
