# Mamoji 企业内部模块产品定位

> 状态：已采用。Mamoji 默认以 `internal-module` 模式运行，不再把自己定义成一套独立、全栈、覆盖所有职能的 ERP。

## 一句话定位

Mamoji 是嵌入企业门户的经营协同与成本控制模块，把经营流水、预算、账户、票据、审批、组织人员和人力成本连接成一条可追踪的内部控制链路。

## 目标使用方式

Mamoji 默认由企业已有门户、统一身份平台或内部应用中心承载入口。它可以独立部署，但产品边界仍按内部模块设计：

- 宿主平台负责员工主身份、组织主数据、统一登录、应用授权和消息入口。
- Mamoji 负责公司上下文、组织人员的经营投影、经营与财务权限、业务单据、预算控制、人力成本、审批状态和审计证据。
- 身份通过 `ActorIdentityProvider` 接入；当前本地 Token 只是一个适配器，后续可替换为 OIDC、SAML 网关或企业会话。
- 页面通过统一的 `AccessContext` 获得当前人员、公司、角色、数据范围、权限和已启用模块。

## 默认核心范围

| 模块 | 解决的问题 | 主要入口 | 核心权限 |
| --- | --- | --- | --- |
| 统一工作台 | 今天有哪些风险、待办和异常需要处理 | `/dashboard`、`GET /api/v1/workspace` | 按下游权限自动裁剪 |
| 审批协同 | 报销、付款和业务申请由谁处理、当前到哪一步 | `/approvals` | `approval.manage` |
| 经营流水 | 收入、成本、退款和经营说明是否完整 | `/transactions` | `operations.read/write` |
| 预算控制 | 公司或成本分类预算是否接近或超过上限 | `/budgets` | `budget.manage` |
| 经营分析 | 收入、成本、净额和异常结构如何变化 | `/operations`、`/reports` | `operations.read`、`reports.read` |
| 周期事项 | 房租、订阅、固定付款等事项是否按期执行 | `/recurring` | `operations.read/write` |
| 资金账户 | 可用资金、冻结资金和对账状态是否可靠 | `/finance`、`/accounts` | `finance.read/write` |
| 票据证据 | 流水、票据、附件、审批和入账是否闭环 | `/receipts` | `finance.read/write` |
| 组织人员 | 部门、岗位、员工状态和部门预算是否清楚 | `/hr/organization` | `people.read/write` |
| 薪酬月结 | 本月员工薪酬口径是否已生成并锁定 | `/admin/compensation` | `workforce.cost.manage` |
| 人力成本 | 公司及部门的人力成本、趋势和预算偏差如何 | `/hr/workforce-cost`、`GET /api/v1/workforce-cost` | `workforce.cost.read` |
| 平台设置 | 公司切换、个人偏好和能力上下文 | `/settings` | `company.switch` 等 |

默认核心范围刻意保持短链路：

```text
经营事项 -> 流水/单据 -> 预算与审批 -> 资金与票据 -> 工作台与报表
组织人员 -> 薪酬月结 -> 人力成本 -> 部门预算与经营决策
```

## 产品能力包

人员能力按“经营必需”和“人才扩展”拆分，避免一个 HR 总开关同时暴露所有页面：

| 能力包 | 模块键 | 默认 | 开关 |
| --- | --- | --- | --- |
| 组织人员核心 | `people-core` | 开启 | `MAMOJI_MODULE_PEOPLE_CORE_ENABLED` |
| 人力成本与薪酬月结 | `workforce-cost` | 开启 | `MAMOJI_MODULE_WORKFORCE_COST_ENABLED` |
| 人才扩展（福利、绩效） | `talent-suite` | 关闭 | `MAMOJI_MODULE_TALENT_SUITE_ENABLED` |
| 家庭主体 | `household` | 关闭 | `MAMOJI_MODULE_HOUSEHOLD_ENABLED` |
| 税务工作台 | `tax` | 关闭 | `MAMOJI_MODULE_TAX_WORKSPACE_ENABLED` |
| 政策中心 | `policy` | 关闭 | `MAMOJI_MODULE_POLICY_CENTER_ENABLED` |
| 备份恢复 UI | `backup` | 关闭 | `MAMOJI_MODULE_BACKUP_UI_ENABLED` |

本次能力拆分是断代升级：旧 `MAMOJI_MODULE_HR_SUITE_ENABLED` 配置和 `organization/people/compensation/benefits/performance` 模块键均不再识别，也不会出现在访问上下文中。

可选模块关闭时，系统会同时执行三层收敛：

1. 不下发对应导航和搜索结果；
2. 前端直接访问旧路由时返回工作台；
3. 标有 `@RequiresProductModule` 的后端 API 返回 404。

这避免模块开关只停留在“菜单隐藏”。

## 明确不做什么

默认模式不承担以下系统的主数据职责：

- 不替代企业 IAM、通讯录和组织架构主系统；
- 不替代完整 HRIS、考勤、绩效或电子合同系统；组织人员只保存经营管理所需的投影；
- 不替代总账、法定会计核算、银行或正式税务申报系统；
- 不在一个首页堆叠所有历史功能；
- 不让家庭资产、演示政策或地区税务口径进入默认企业导航。

需要这些能力时，应作为独立能力包启用，并通过明确契约集成，而不是继续扩大核心模块。

## 人员、公司和数据范围

企业内部模块的授权边界是：

```text
Actor（登录人员） + Company（当前公司） + Role（角色）
+ Scope（数据范围） + Permission（操作能力） + Enabled Module（产品能力）
```

`company_memberships` 是账号与公司授权关系的权威表；员工档案只是可选业务资料，不再承担登录授权的唯一来源。

支持的数据范围包括：

- `group`：多公司集团；
- `company`：当前公司；
- `company_set`：指定公司集合；
- `department`：当前部门；
- `self`：本人数据；
- `readonly`：公司级只读观察。

工作台与全局搜索已经把数据范围落实到查询条件，不仅在页面上显示范围名称。

## 产品体验原则

- 一个工作台：默认首页只回答“现在怎样、哪里有风险、下一步做什么”。
- 一次上下文加载：身份、公司、角色、权限和模块通过一个接口获取。
- 一条证据链：业务流水、预算、审批、账户、票据和审计记录可相互追踪。
- 一种错误契约：校验、冲突和并发错误使用统一 Problem Detail 响应，并带 `X-Request-Id`。
- 一套重试语义：关键创建命令使用 `Idempotency-Key`，防止网络重试产生重复数据。
- 一致的公司隔离：所有核心读取必须显式带公司边界，聚合查询同时应用数据范围。
- 一个稳定导航：公司默认只有五个一级模块；可选能力归入相应业务组，不新增平级信息孤岛。

## 成功指标

内部模块是否有效，不以页面数量衡量，优先看：

- 工作台一次加载完成率和 P95 响应时间；
- 待审批、待对账、缺票据和预算预警的平均处理时长；
- 重复创建、跨公司读取和并发覆盖事件数；
- 核心业务动作的审计覆盖率；
- 可选模块关闭后核心用户完成日常任务所需的页面跳转数。

## 当前过渡说明

- 预算已按 `api/application/domain/infrastructure` 完成首个模块化试点。
- 工作台已改为后端聚合读模型，前端不再并发拼装大量接口。
- 人力成本已拆为独立后端聚合读模型，优先使用薪酬月结快照，无批次时明确标记为员工档案估算。
- 旧 `InMemoryStore`、`EnterpriseStore` 中的 Map 仅保留给演示初始化、恢复兼容和渐进迁移；在线业务服务已经不直接读取这些集合。
- 数据库仍保留单实例保护开关。等启动期兼容初始化完全迁出运行进程后，再把多实例作为默认生产拓扑。

代码边界和后续拆分规则见 [ENTERPRISE_MODULE_ARCHITECTURE.md](ENTERPRISE_MODULE_ARCHITECTURE.md)。
