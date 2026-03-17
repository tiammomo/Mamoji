# Mamoji

Mamoji 是一个面向家庭记账、预算管理、收支分析和 AI 辅助问答的全栈项目。

- 后端：Spring Boot 3.5 + Java 21
- 前端：Next.js 16 + React 19
- 默认本地端口：
- 前端 `33000`
- 后端 `38080`
- MySQL `33306`
- Redis `36379`

## 目录结构

```text
mamoji/
├─ backend/                    # Spring Boot 后端
├─ frontend/                   # Next.js 前端
├─ docs/                       # 项目文档
├─ docker/                     # Docker 初始化资源
├─ tools/                      # 备份、文档、测试等辅助脚本
├─ docker-compose.yml
├─ docker-compose.prod.yml
└─ start.sh
```

## 快速开始

### 环境要求

- JDK 21
- Maven 3.9+
- Node.js 20+
- npm
- Docker Desktop

### 启动基础依赖

```bash
docker compose -f docker-compose.yml -f docker-compose.network-mamoji.override.yml up -d mysql redis
```

启动后默认映射：

- MySQL：`localhost:33306`
- Redis：`localhost:36379`

### 启动后端

```bash
cd backend
mvn spring-boot:run
```

默认开发配置已对齐为：

- MySQL：`jdbc:mysql://localhost:33306/mamoji`
- Redis：`localhost:36379`

常用地址：

- API 根路径：[http://localhost:38080/api/v1](http://localhost:38080/api/v1)
- 健康检查：[http://localhost:38080/actuator/health](http://localhost:38080/actuator/health)

### 启动前端

```bash
cp .env.front.example frontend/.env.local
cd frontend
npm install
npm run dev
```

前端地址：

- [http://localhost:33000](http://localhost:33000)

最小环境变量示例：

```env
NEXT_PUBLIC_API_BASE=http://localhost:38080/api/v1
```

## Docker 一体化启动

```bash
cp docker-compose.env.example .env
docker compose -f docker-compose.yml -f docker-compose.network-mamoji.override.yml up -d --build
```

默认服务地址：

- 前端：`http://localhost:33000`
- 后端：`http://localhost:38080`
- MySQL：`localhost:33306`
- Redis：`localhost:36379`

## 常用环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MYSQL_PORT` | `33306` | 本地 MySQL 映射端口 |
| `REDIS_PORT` | `36379` | 本地 Redis 映射端口 |
| `BACKEND_PORT` | `38080` | 后端服务端口 |
| `FRONTEND_PORT` | `33000` | 前端服务端口 |
| `JWT_SECRET` | 示例值 | JWT 密钥，生产环境必须替换 |
| `ANTHROPIC_AUTH_TOKEN` | 空 | AI 模型服务令牌 |

## 默认测试账号

开发环境会通过初始化逻辑注入测试数据，详见：

- [DataInitializer.java](/D:/projects/shuai/mamoji/backend/src/main/java/com/mamoji/config/DataInitializer.java)

## 文档索引

- [接口文档](/D:/projects/shuai/mamoji/docs/API.md)
- [架构说明](/D:/projects/shuai/mamoji/docs/ARCHITECTURE.md)
- [数据库说明](/D:/projects/shuai/mamoji/docs/DB.md)
- [部署指南](/D:/projects/shuai/mamoji/docs/DEPLOY.md)
- [产品说明](/D:/projects/shuai/mamoji/docs/PRD.md)
- [风控规则](/D:/projects/shuai/mamoji/docs/RISK_CONTROL.md)
- [AI 助手说明](/D:/projects/shuai/mamoji/docs/AI_ASSISTANT.md)
- [注释规范](/D:/projects/shuai/mamoji/docs/COMMENTING.md)

## 编码说明

仓库中的文本文件统一使用 UTF-8。

如果 Windows 终端出现乱码，请先切换编码：

```powershell
chcp 65001
```

或执行仓库脚本：

```powershell
. .\tools\enable-utf8.ps1
```

## License

MIT
