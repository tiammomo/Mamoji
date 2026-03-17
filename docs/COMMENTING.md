# 注释规范

本文档用于统一 Mamoji 项目的代码注释与说明文档风格，减少“代码能运行，但后续难以维护”的风险。

## 目标

- 让新同学能在短时间内理解模块职责、边界和关键流程。
- 让复杂逻辑的设计意图保留在代码附近，而不是只存在于口头沟通里。
- 让配置、脚本、接口和风控规则在变更时同步留下可追溯说明。

## 总原则

1. 注释解释“为什么这样做”，而不是重复“代码正在做什么”。
2. 注释需要和代码一起维护，修改逻辑时同步更新说明。
3. 对外接口、复杂分支、风控规则、权限判断、并发与事务边界必须有注释。
4. 简单赋值、直观条件判断、纯样板代码不写噪音注释。

## 后端规范

### Javadoc 覆盖范围

- 每个 `public class` 必须有类级 Javadoc，说明职责、上下游关系和主要边界。
- 每个 `public` 方法必须有方法级 Javadoc，至少说明用途、关键输入和返回语义。
- 关键的 `private` 方法在以下场景建议补充注释：
- 风控判断
- 聚合查询
- DTO 装配
- 快照同步
- 回退与降级逻辑

### 推荐写法

```java
/**
 * 查询指定月份的预算执行概览。
 *
 * <p>返回结果会包含预算金额、已用金额、剩余额度和风险等级，
 * 供首页卡片和 AI 助手共用。
 */
public BudgetOverview queryBudgetOverview(Long userId, YearMonth month) {
    ...
}
```

### 重点说明场景

- 事务边界：为什么这里要加 `@Transactional`
- 权限校验：为什么当前角色可以或不可以执行操作
- 风控阈值：阈值来源、适用范围、是否可配置
- AI 路由：为什么选择某个模型、工具或兜底方案
- 数据修正：为什么这里做重算而不是增量更新

## 前端规范

### JSDoc 覆盖范围

- 导出的组件、hook、工具函数需要有 JSDoc。
- `api.client.ts`、`websocket.ts`、`provider.tsx` 这类基础设施文件需要说明状态流和错误语义。
- 表单、筛选器、复杂仪表盘组件需要解释关键状态切换和交互约束。

### 推荐写法

```ts
/**
 * 查询交易列表，并将筛选条件纳入缓存键。
 *
 * 当 `enabled=false` 时暂停请求，适合依赖登录态的页面。
 */
export function useTransactions(params?: QueryParams) {
  ...
}
```

### 重点说明场景

- SSR / CSR 差异处理
- 乐观更新与回滚
- 图表数据转换
- 表单默认值回填
- 查询条件与缓存键的绑定关系

## 配置与脚本规范

- `.env.example`、`docker-compose.env.example`
- 写明变量用途、默认值、是否可公开、生产风险
- `start.sh`、`.bat`、运维脚本
- 写明执行顺序、依赖前提、失败时会停在哪里
- Docker 与网络编排说明
- 写清楚容器、端口、网络名称以及默认映射关系

## 文档同步清单

以下内容发生变化时，需要同步更新文档：

- 端口、环境变量、启动命令
- Docker 依赖与网络拓扑
- 鉴权、权限、风控规则
- AI 模式、工具路由、兜底策略
- 报表口径、预算口径、交易口径

建议优先检查这些文件：

- `README.md`
- `docs/DEPLOY.md`
- `docs/ARCHITECTURE.md`
- `docs/API.md`
- `docs/RISK_CONTROL.md`
- `docs/AI_ASSISTANT.md`

## 提交建议

- 注释与文档变更尽量单独提交，方便审阅和回滚。
- 推荐提交信息：
- `docs: refine project documentation`
- `docs(backend): add service and controller javadocs`
- `docs(frontend): clarify hooks and shared components`
