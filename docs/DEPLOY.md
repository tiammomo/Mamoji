# 部署指南

本文档覆盖 Mamoji 在本地开发、Docker Compose 和生产环境的部署方式。

## 1. 前置条件

- Docker Desktop 4.x+
- JDK 21
- Maven 3.9+
- Node.js 20+

## 2. 本地开发部署（推荐）

### 2.1 启动基础依赖

```bash
docker compose -f docker-compose.yml -f docker-compose.network-mamoji.override.yml up -d mysql redis
```

默认端口映射:

- MySQL: `localhost:33306 -> 3306`
- Redis: `localhost:36379 -> 6379`

### 2.2 Windows 环境变量示例

如本机默认 `java` 仍指向旧版本，建议在当前终端显式切换到 Java 21 与 Maven 3.9.9:

```powershell
$env:JAVA_HOME="C:\Users\lenovo\.jdks\corretto-21.0.10"
$env:PATH="$env:JAVA_HOME\bin;D:\codes\apache-maven-3.9.9\bin;$env:PATH"
java -version
mvn -version
```

期望输出:

- `Java version: 21.0.10`
- `Maven home: D:\codes\apache-maven-3.9.9`

### 2.3 启动后端

```bash
cd backend
mvn spring-boot:run
```

开发配置默认连接:

- `jdbc:mysql://localhost:33306/mamoji`
- `redis://localhost:36379`

### 2.4 启动前端

```bash
cp .env.front.example frontend/.env.local
cd frontend
npm install
npm run dev
```

访问地址:

- 前端: `http://localhost:33000`
- 后端: `http://localhost:38080`

## 3. Docker Compose 一体部署

### 3.1 准备环境变量

```bash
cp docker-compose.env.example .env
```

至少检查以下变量:

- `MYSQL_ROOT_PASSWORD`
- `DB_PASSWORD`
- `REDIS_PASSWORD`
- `JWT_SECRET`

### 3.2 启动全量服务

```bash
docker compose -f docker-compose.yml -f docker-compose.network-mamoji.override.yml up -d --build
```

### 3.3 运维命令

```bash
# 查看状态
docker compose -f docker-compose.yml -f docker-compose.network-mamoji.override.yml ps

# 查看日志
docker compose -f docker-compose.yml -f docker-compose.network-mamoji.override.yml logs -f

# 停止服务
docker compose -f docker-compose.yml -f docker-compose.network-mamoji.override.yml down
```

## 4. 生产部署（docker-compose.prod.yml）

### 4.1 启动

```bash
cp docker-compose.env.example .env
docker compose -f docker-compose.prod.yml up -d --build
```

说明:

- `docker-compose.prod.yml` 默认将端口绑定到 `127.0.0.1`，用于降低暴露面。
- 建议由 Nginx/Caddy 在公网层反向代理并处理 HTTPS。

### 4.2 必改配置

- `JWT_SECRET`（必须强随机）
- `MYSQL_ROOT_PASSWORD`
- `DB_PASSWORD`
- `REDIS_PASSWORD`
- `ANTHROPIC_AUTH_TOKEN`（若启用 AI）

## 5. 健康检查

### 5.1 后端

```bash
curl http://localhost:38080/actuator/health
```

### 5.2 容器状态

```bash
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

## 6. 备份与恢复

建议使用仓库脚本:

- [backup.bat](../tools/backup/backup.bat)
- [backup.sh](../tools/backup/backup.sh)

如需手动备份:

```bash
docker exec mamoji-mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" mamoji > mamoji.sql
```

如需手动恢复:

```bash
cat mamoji.sql | docker exec -i mamoji-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" mamoji
```

## 7. 常见问题

### 7.1 后端无法连接 MySQL

1. 确认容器运行: `docker ps | grep mamoji-mysql`
2. 确认端口映射为 `33306`
3. 检查 `backend/src/main/resources/application-dev.yml` 中的数据源地址

### 7.2 后端无法连接 Redis

1. 确认容器运行: `docker ps | grep mamoji-redis`
2. 确认端口映射为 `36379`
3. 确认 `REDIS_PASSWORD` 与配置一致

### 7.3 Maven 编译报 Java 语法错误

如看到 `switch ->`、`text block`、`record` 等语法报错，优先检查 Maven 是否实际运行在 Java 21：

```powershell
java -version
mvn -version
```

如果 `mvn -version` 中显示的 Java 版本仍是 1.8 或 11，需先按上文切换 `JAVA_HOME` 后再编译。

### 7.4 前端请求命中相对路径 `/api/v1`

请确认 `frontend/.env.local` 存在并包含:

```env
NEXT_PUBLIC_API_BASE=http://localhost:38080/api/v1
```
