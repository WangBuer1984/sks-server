# REST_CONTRACT — 前端 ↔ Java REST 契约

sks-server 是服务提供方，本文件为跨仓契约真相。sks-web 的 axios 调用须与本文件一致。

## ApiResponse 形状

所有 C 端 `/api/**` 与管理端 `/api/admin/**` 返回统一壳：
```json
{ "code": 0, "message": "...", "data": { ... } }
```
`code=0` 成功；非 0 见 ErrorCode 全表。

## Token key 约定（两套隔离）

- C 端：axios `userClient` baseURL `/api`，注入 cookie/header `sks_token`。
- 管理端：axios `adminClient` baseURL `/api/admin`，注入 `sks_admin_token`。
- 两套 JWT 不同 secret/claim，互不通用。

## 401 行为

401 → 清 token + 存回跳路径 `returnKey` + 跳对应登录页（C 端 `/login`、管理端 `/admin/login`）。router 守卫拦前端导航，axios 拦截器拦后端 401，双保险。
> 注：当前实现只存回跳路径、未存表单内容（PRD §11.6 表单存 localStorage 是既有 gap）。

## ErrorCode 全表

| code | 常量 | HTTP | 含义 |
|---|---|---|---|
| 0 | OK | 200 | 成功 |
| 5001 | AI_FAILED | 500 | AI 生成失败（已退款） |
| ... | ... | ... | ... |

> 实现期补全：`grep -rn "ErrorCode" src/main/java | grep -oE '[A-Z_]+ *= *[0-9]+' | sort -u` 抽全表填入。
