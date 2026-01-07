# GeminiEligibilityCheck (部署指南)

这是一个用于“分发账号 -> 外部检测 -> 回调写入结果”的轻量 Spring Boot 服务。

## 快速启动（本机 Java 17 + Maven）

1. 安装 Java 17
2. 安装 Maven（`mvn`）
3. 在项目根目录执行：

```bash
mvn -DskipTests package
java -jar target/GeminiEligibilityCheck-0.0.1-SNAPSHOT.jar
```

默认监听 `http://localhost:8080`。

## PostgreSQL 存储（可选）

默认使用本地 JSON 文件持久化账号（`accounts-state.json`，可用 `gemini.storage.path` 修改）。

启用 PostgreSQL：设置以下配置（Spring Boot 支持环境变量/启动参数注入）

- `gemini.pg.url`（例如：`jdbc:postgresql://127.0.0.1:5432/gem`）
- `gemini.pg.user`
- `gemini.pg.password`

可选迁移（仅在数据库为空时执行，把本地 JSON 导入数据库）：

- `gemini.pg.migrateFromFile=true`

## API

- `GET /api/poll`：获取一个待测账号（并置为 CHECKING）
- `POST /api/callback`：回传检测结果，body：`{"email":"...","result":"QUALIFIED|INVALID"}`
- `POST /api/reset-checking`：将所有 CHECKING 重置为 IDLE
- `POST /api/reload`：从 accounts.txt 重新加载账号
