# ADR 0002：删除 InMemoryStore 兼容层

- 状态：Accepted
- 日期：2026-09-04

## 背景

所有在线业务对象都已经迁入 PostgreSQL 专属仓储，`InMemoryStore` 不再保存内存集合。旧类只剩首次管理员初始化，以及被若干历史服务调用的静态时间、金额和反射打戳工具。继续保留该名称会错误暗示系统存在第二数据源，也让身份初始化和无状态工具形成不必要的横向耦合。

## 决策

1. 删除 `InMemoryStore`，不保留兼容别名。
2. 首次本地管理员由 `InitialAdminDataInitializer` 创建，并继续依赖 `SingleInstanceDatabaseGuard`。已有用户时它不得哈希密码或写数据库。
3. `EnterpriseDataInitializer` 显式依赖 `InitialAdminDataInitializer`，保证企业主体初始化前已经存在可作为所有者的管理员。
4. 历史服务的时间戳调用改用其既有的 `OffsetDateTime` 表达；只供 demo/bootstrap 使用的金额转换和模型打戳收回 `EnterpriseDataInitializer`，并以强类型字段赋值取代反射。
5. 边界测试必须确认两个旧 Store 类均不存在；管理员初始化需覆盖已有数据无操作、demo 默认创建、生产弱凭证拒绝和生产强凭证创建。

## 数据迁移

本决策不改变表结构、数据格式或 HTTP 契约，不新增 Flyway migration。首次管理员仍写入 `users`，字段、默认角色、权限、头像和密码哈希方式保持不变；时间戳仍使用可解析的 ISO-8601 offset datetime。

## 影响

- 代码不再存在名为 Store 的进程内兼容边界，PostgreSQL 单一事实源从实现和命名上保持一致。
- 首次管理员与企业数据的启动顺序由显式 Bean 依赖保证，不再依赖组件扫描或构造副作用。
- 应用启动期仍有兼容补全和 demo 夹具逻辑；解除单实例保护前，需要继续迁往一次性 migration 或独立 bootstrap job。

## 回滚

若初始化顺序或管理员创建异常，回滚到合并前的应用镜像或提交即可。由于没有 schema 和数据迁移，不需要数据库降级。若新版本已经成功创建首个管理员，回滚后的旧版本会因 `users` 非空而跳过重复创建。
