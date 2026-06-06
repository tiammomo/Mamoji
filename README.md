# Mamoji

Mamoji 后续定位为面向初创公司和小团队的企业经营记账与人员经营管理系统。它不再是个人或家庭记账软件，而是帮助公司统一管理员工入职离职、人员信息、企业收入、成本支出、税费、预算、现金流和经营风险。

当前项目采用前后端分离结构：后端提供 Spring Boot API，前端使用 Next.js 构建桌面/移动自适应体验，数据落在本地 SQLite 文件中，适合演示、比赛答辩和产品能力体验。

## 功能概览

- 公司经营台账：围绕公司主体管理资金账户、经营流水、成本类型和收入类型。
- 人员管理：面向初创团队的员工档案、部门、入职、离职、人力成本和权限管理。
- 经营管理：记录客户回款、项目收入、主营收入、经营成本、周期事项和经营预算。
- 财务管理：管理资金账户、票据凭证、应收应付、收付款、报销和现金流。
- 税务管理：规划增值税、企业所得税、个税、附加税等税费记录、估算、申报和风险提醒。
- 预算与经营控制：支持公司、部门、项目或成本类型预算，自动计算使用率和风险等级。
- 经营报表：展示收入、成本、利润、现金余额、burn rate、runway、税费和预算风险。
- 周期事项：支持工资、房租、SaaS 订阅、税费申报等周期性经营事项。
- 备份与票据：提供备份状态、导出校验和票据上传入口。

企业版完整产品蓝图见 [docs/ENTERPRISE_PRODUCT_POSITIONING.md](docs/ENTERPRISE_PRODUCT_POSITIONING.md)，模块架构见 [docs/ENTERPRISE_MODULE_ARCHITECTURE.md](docs/ENTERPRISE_MODULE_ARCHITECTURE.md)，权限矩阵见 [docs/ENTERPRISE_PERMISSION_MATRIX.md](docs/ENTERPRISE_PERMISSION_MATRIX.md)，多公司与地区政策规划见 [docs/ENTERPRISE_MULTI_COMPANY_POLICY.md](docs/ENTERPRISE_MULTI_COMPANY_POLICY.md)。

## 项目结构

```text
Mamoji/
  backend/   Spring Boot API, SQLite 数据库与业务逻辑
  frontend/  Next.js 前端应用
  docs/      业务理解与补充文档
```

## 演示数据

当前工作区已经在实际 SQLite 文件中生成了一批体验数据。现阶段数据库仍沿用过渡模型，使用账本、分类、交易、预算等已有结构承载企业经营场景；后续会逐步升级为公司、员工、客户、供应商、税费和审批等企业领域模型。

- 数据库路径：`backend/data/mamoji.db`
- 演示账号：2 个
- 公司主体：深圳市示例电商科技有限公司
- 部门：4 个
- 员工档案：6 个
- 入职/离职事件：7 条
- 税费事项：4 条
- 公司资金账户：7 个
- 经营分类：19 个
- 经营预算：6 个
- 经营流水：68 笔，覆盖 2026 年 1 月到 6 月的趋势数据
- 周期经营事项：4 个

演示账号：

| 角色 | 邮箱 | 密码 | 说明 |
| --- | --- | --- | --- |
| 公司管理员 | `test@mamoji.com` | `123456` | 可体验完整功能、经营报表和人员管理 |
| 团队成员 | `family@mamoji.com` | `123456` | 可用于查看成员账号和权限边界 |

建议优先使用公司管理员账号登录体验。预算页中可以看到高风险、正常和低风险预算；交易页中可以看到收入、成本支出和退款链路；报表页中可以看到近 6 个月趋势和分类占比。

## 环境要求

- JDK 21
- Maven 3.9+
- Node.js 20+ 或 24+
- npm 10+

本机已验证的运行环境：

- Java 21
- Maven 3.9.16
- Node.js 24.14.1
- npm 11.11.0

## 启动后端

在项目根目录执行：

```bash
mvn -f backend/pom.xml spring-boot:run
```

后端默认地址：

- API Base URL: `http://localhost:38080/api/v1`
- 健康检查：`http://localhost:38080/actuator/health`

后端默认读取 SQLite：

```yaml
spring:
  datasource:
    url: jdbc:sqlite:${MAMOJI_SQLITE_PATH:data/mamoji.db}
```

如需指定其他数据库文件：

```bash
MAMOJI_SQLITE_PATH=/absolute/path/to/mamoji.db mvn -f backend/pom.xml spring-boot:run
```

## 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端默认地址：

- `http://localhost:33000`

前端 API 地址配置在 `frontend/.env.local`：

```env
NEXT_PUBLIC_API_BASE_URL=http://localhost:38080/api/v1
```

## 快速验证

后端启动后，可以用下面的命令验证登录和数据接口：

```bash
curl -s http://localhost:38080/actuator/health
```

```bash
curl -s -X POST http://localhost:38080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"test@mamoji.com","password":"123456"}'
```

登录成功后，将返回的 `token` 写入请求头：

```bash
curl -s http://localhost:38080/api/v1/stats/overview \
  -H "Authorization: Bearer <token>"
```

## 体验路径

1. 打开 `http://localhost:33000`。
2. 使用 `test@mamoji.com / 123456` 登录。
3. 在首页查看本月收入、成本、结余和预算风险。
4. 进入经营流水，查看客户回款、成本支出、税费和退款记录。
5. 进入经营预算，查看公司经营预算和成本类型预算的风险状态。
6. 进入经营报表，查看趋势图、成本占比、年度报表和资产负债。
7. 进入周期事项，查看工资、房租、SaaS 订阅和税费申报等周期事项。
8. 进入人员管理，按后续企业版规划承载人员档案、入职离职和权限管理。

## 企业 API

企业版第一阶段已新增以下接口：

- `GET /api/v1/enterprise/summary`：公司人员、部门、人力成本和待处理税费摘要。
- `GET /api/v1/enterprise/permission-matrix`：企业角色、数据范围、权限点和角色权限矩阵。
- `GET /api/v1/enterprise/companies`：当前用户可访问的公司主体列表。
- `POST /api/v1/enterprise/companies`：新增公司主体，并维护注册地、经营地和政策画像字段。
- `GET /api/v1/enterprise/company`：公司主体信息。
- `GET /api/v1/enterprise/departments`：部门与成本中心。
- `GET /api/v1/enterprise/employees`：员工档案列表，支持关键词、状态和部门筛选。
- `POST /api/v1/enterprise/employees`：新增员工档案。
- `PUT /api/v1/enterprise/employees/{id}`：更新员工档案，状态变化会记录人员事件。
- `GET /api/v1/enterprise/employment-events`：入职、离职和状态变更事件。
- `GET /api/v1/enterprise/tax-items`：税费事项和申报缴纳状态。

## 常见问题

### 前端提示接口不可用

确认后端已启动，并且 `frontend/.env.local` 中的 `NEXT_PUBLIC_API_BASE_URL` 指向 `http://localhost:38080/api/v1`。

### 端口被占用

后端端口在 `backend/src/main/resources/application.yml` 中配置，默认 `38080`。前端可以使用 Next.js 的 `--port` 参数：

```bash
npm run dev -- --port 3001
```

### 想重新生成最小默认数据

停止后端后删除 SQLite 文件，再启动后端即可触发后端默认初始化：

```bash
rm backend/data/mamoji.db
mvn -f backend/pom.xml spring-boot:run
```

注意：这会删除当前演示数据和本地操作记录。
