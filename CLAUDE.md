# CLAUDE.md — sks-server 仓

本仓为 Java 服务（Spring Boot 3），唯一公网入口，负责鉴权/额度/CRUD/状态机/定时任务。

## 硬不变量（实现时不得违背）

- **信用事务边界**：扣额度用原子条件更新 `UPDATE credit_account SET balance = balance - :n WHERE user_id = :uid AND balance >= :n`，靠影响行数判成败，禁先查后写；退款幂等 via `credit_ledger` `(biz_id, biz_type, type)` 唯一约束。事务编排法非 `@Transactional`——长 HTTP 调用（调 Python 30-60s）不得持 DB 连接：先插 script 占位行拿 `script_id` → 短 `REQUIRES_NEW` 事务扣额度 → 调 Python → 成功回填/draft，失败设 failed + 幂等退款 ledger。Fail→refund 永不漏扣。
- **管理端隔离**：独立 `admin_user` + 独立 SecurityFilterChain（`/api/admin/**`）+ 不同 JWT secret/claim，两套 token 互不通用；无注册，Flyway 种子（密码哈希取自 `ADMIN_SEED_PASSWORD` env）。
- **Testcontainers pgvector:pg16 非 H2**：保持 SQL 方言一致。
- **复盘状态机无 AI 判态**：七状态全 Java 规则转换；`hot` 阈值=近30天均值×3（可调）；`rejected`=48h 未采纳（@Scheduled 扫）。
- **Java 唯一公网入口**；不用 Redis/MQ/微服务/K8s。
- **JWT secrets 守卫**：`JwtConfig.guardSecret` 拒绝空/占位符（`change_me...`）；Flyway 早于 bean 执行。

## 本仓构建/测试命令

- `./mvnw test`（全测）/ `./mvnw test -Dtest=CreditServiceTest`（单类）/ `./mvnw test -Dtest="AuthServiceTest,UserServiceTest"`（多类）
- `./mvnw spring-boot:run`（本地跑，配合 `application-local.yml` + local profile + `.env`）
- 镜像构建：`docker build -t sks-server .`（Dockerfile `-DskipTests`，测试在 CI 前置 gate 跑）

## 契约

- 前端↔Java REST 契约（ErrorCode 全表 + ApiResponse 形状 + `sks_token`/`sks_admin_token` 两套 token key + 401 行为）见 `docs/REST_CONTRACT.md`。
- Java↔Python 跨仓 HTTP+X-Service-Token 契约见 sks-ai 仓 `docs/API_CONTRACT.md`（本仓 `AiClient` record 须对齐）。
