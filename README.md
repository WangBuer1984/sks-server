# sks-server

Java 服务（Spring Boot 3 + MyBatis-Plus + Spring Security JWT），唯一公网入口。

## 本地跑

`application-local.yml` 是 **gitignored 的本地文件**（含本地口令，不进 git）——本地跑前先创建（模板见下）。激活 `local` profile 才会加载它 + `.env`：

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

`application-local.yml` 模板（本地创建，**勿提交**——已被 `.gitignore` 的 `**/application-local.yml` 挡）：
```yaml
spring:
  config:
    import: "optional:file:.env[.properties]"   # 相对路径，工作目录=仓库根；[.properties] 强制按 properties 读 .env
  datasource:
    url: jdbc:postgresql://localhost:5432/sks
    username: sks
    password: ${SPRING_DATASOURCE_PASSWORD:change_me}   # 读 .env，不硬编码
sks:
  ai:
    base-url: http://localhost:8000   # 本地 Python；compose 默认 http://sks-ai:8000 对本地错
```
`.env`（gitignored）填真值；`JWT_SECRET_USER`/`JWT_SECRET_ADMIN` 必须换 ≥32 字节真值（否则 `JwtConfig.guardSecret` 启动即抛）。见记忆 `local-idea-run-java-env`。

## 镜像构建

```bash
docker build -t ghcr.io/wangbuer1984/sks-server:dev .
```
CI 在 git tag `v*` 时 build+push 到 GHCR。镜像只保证 `linux/amd64`。
