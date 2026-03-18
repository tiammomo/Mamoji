# 开发技能与工具

本文档用于说明本仓库常用开发工具、工作流和协作建议。

## 1. 后端开发技能

- Java 21 语言特性
- Spring Boot 3.5（Web/Security/Data JPA）
- Maven 构建与测试
- Javadoc 注释规范维护
- 使用 UTF-8 终端与 UTF-8 文件编码

常用命令:

```bash
cd backend
mvn -q -DskipTests compile
mvn test
```

Windows 如需切换本地 JDK/Maven:

```powershell
$env:JAVA_HOME="C:\Users\lenovo\.jdks\corretto-21.0.10"
$env:PATH="$env:JAVA_HOME\bin;D:\codes\apache-maven-3.9.9\bin;$env:PATH"
mvn -version
```

## 2. 前端开发技能

- Next.js App Router
- React + TypeScript
- React Query 数据缓存与失效
- TailwindCSS 组件样式组织

常用命令:

```bash
cd frontend
npm install
npm run lint
npm run test
npm run dev
```

## 3. 测试与质量

- 后端: JUnit + Mockito
- 前端: Vitest + Testing Library
- E2E: Playwright（可结合 `tools/e2e_mamoji_smoke.py`）

建议策略:

1. 业务变更至少补 1 条测试
2. API 变更同步文档
3. 风控与 AI 模块优先回归关键路径

## 4. 运维与发布

- Docker Compose 本地联调
- GitHub Actions CI/CD
- 备份脚本（`tools/backup`）

关键命令:

```bash
docker compose -f docker-compose.yml -f docker-compose.network-mamoji.override.yml up -d
docker compose ps
docker compose logs -f
```

## 5. 团队协作建议

1. 注释和文档改动尽量单独 commit
2. 高风险逻辑（交易、预算、风控）必须附带说明
3. 端口、环境变量变化必须更新以下文件:
   - `README.md`
   - `docs/DEPLOY.md`
   - `.env.example`
   - `docker-compose.env.example`
4. AI 助手能力变化需同步更新:
   - `docs/AI_ASSISTANT.md`
   - `docs/API.md`
   - 前端相关提示文案
