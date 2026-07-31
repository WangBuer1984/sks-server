# sks-server

Java 服务（Spring Boot 3 + MyBatis-Plus + Spring Security JWT），唯一公网入口。

## 本地跑

`application.yml` 默认激活 `local` profile（`spring.profiles.active: ${SPRING_PROFILES_ACTIVE:local}`），故本地跑无需在 Run Config 设 profile——默认即加载 `application-local.yml` + 经其 `spring.config.import` 引入 `.env`。`application-local.yml` 是 **gitignored 的本地文件**（含本地口令，不进 git），本地跑前先创建（模板见下）：

```bash
./mvnw spring-boot:run     # local 是默认 profile，无需 -Dspring-boot.run.profiles=local
```

本地跑前若要验证 prod profile（连容器内网的 postgres/sks-ai，不读本地 .env）：

```bash
SPRING_PROFILES_ACTIVE=prod ./mvnw spring-boot:run   # 或 -Dspring-boot.run.profiles=prod
```

`application-local.yml` 模板（本地创建，**勿提交**——已被 `.gitignore` 的 `**/application-local.yml` 挡）：
```yaml
spring:
  config:
    import: "optional:file:.env[.properties]"   # 相对路径，工作目录=仓库根；[.properties] 强制按 properties 读 .env
  datasource:
    # 三项全从 .env 的 POSTGRES_* 派生，与 compose 的 environment 块同口径，只是 host 换 localhost。
    # 不硬编码口令：.env 是唯一真相，改库口令只改一处。
    url: jdbc:postgresql://localhost:5432/${POSTGRES_DB}
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASSWORD}
sks:
  ai:
    base-url: http://localhost:8000   # 本地 Python；compose 默认 http://sks-ai:8000 对本地错
```

> 用 `POSTGRES_*` 而不是 `SPRING_DATASOURCE_*`：后者是 compose 从前者派生出来注入容器的，`.env` 里并不存在。写成 `${SPRING_DATASOURCE_PASSWORD:change_me}` 会静默 fallback 到 `change_me`，然后报 `password authentication failed`。

> **`.env` 缺键时的报错长这样**（Spring Boot 的 Binder 会把解析不了的占位符原样透传，不会点名报缺哪个变量）：缺 `POSTGRES_PASSWORD` → `FATAL: password authentication failed for user "sks"`；缺 `POSTGRES_DB` → `database "${POSTGRES_DB}" does not exist`。见到这两类报错先查 `.env` 的键是否齐，别先怀疑口令值。
`.env`（gitignored）填真值；`JWT_SECRET_USER`/`JWT_SECRET_ADMIN` 必须换 ≥32 字节真值（否则 `JwtConfig.guardSecret` 启动即抛）。见记忆 `local-idea-run-java-env`。

## 镜像构建

```bash
docker build -t ghcr.io/wangbuer1984/sks-server:dev .
```
CI 在 git tag `v*` 时 build+push 到 GHCR。镜像只保证 `linux/amd64`。
