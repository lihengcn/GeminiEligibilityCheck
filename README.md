# GeminiEligibilityCheck

轻量 Spring Boot 服务，用于"分发账号 -> 外部检测 -> 回调写入结果"流程。

## 快速启动

### 本机运行（Java 17 + Maven）

```bash
mvn -DskipTests package
java -jar target/GeminiEligibilityCheck-0.0.1-SNAPSHOT.jar
```

### Docker 部署

```bash
# 构建镜像
docker build -t gemini-check .

# 运行（数据持久化到 /path/to/data）
docker run -d -p 8080:8080 -v /path/to/data:/data gemini-check

# 使用 PostgreSQL
docker run -d -p 8080:8080 \
  -e gemini.pg.url=jdbc:postgresql://host:5432/gem \
  -e gemini.pg.user=postgres \
  -e gemini.pg.password=secret \
  gemini-check
```

默认监听 `http://localhost:8080`

## 存储配置

默认使用本地 JSON 文件（`/data/accounts-state.json`），可通过 `gemini.storage.path` 修改路径。

### PostgreSQL（可选）

| 配置项 | 说明 |
|--------|------|
| `gemini.pg.url` | 数据库连接，如 `jdbc:postgresql://127.0.0.1:5432/gem` |
| `gemini.pg.user` | 用户名 |
| `gemini.pg.password` | 密码 |
| `gemini.pg.migrateFromFile` | 设为 `true` 时，启动时将本地 JSON 迁移到数据库（仅数据库为空时执行） |

## API

### 账号分发

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/poll` | 获取一个待测账号（状态置为 CHECKING） |
| POST | `/api/callback` | 回传检测结果 `{"email":"...","result":"QUALIFIED\|INVALID"}` |
| POST | `/api/reset-checking` | 将所有 CHECKING 重置为 IDLE |

### 账号管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/accounts` | 获取所有账号及状态统计 |
| POST | `/api/import` | 批量导入账号 `{"content":"email1\nemail2","mode":"APPEND\|OVERWRITE"}` |
| POST | `/api/status` | 手动更新状态 `{"email":"...","status":"IDLE\|CHECKING\|QUALIFIED\|INVALID"}` |
| POST | `/api/sold` | 标记已售 `{"email":"...","sold":true}` |
| POST | `/api/finished` | 标记已完成 `{"email":"...","finished":true}` |
| POST | `/api/delete` | 删除账号 `{"email":"..."}` |
| POST | `/api/restore-statuses` | 批量恢复状态 `{"items":[{"email":"...","status":"..."}]}` |

### 系统信息

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/info` | 获取存储类型（file/postgres） |
