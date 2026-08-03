# REST_CONTRACT — 前端 ↔ Java REST 契约

sks-server 是服务提供方，本文件是跨仓契约真相。消费方 sks-web 的 axios 调用（`src/api/*.ts`）须与本文件一致。

字段全部从 `src/main/java/com/sks/**/*Controller.java` 与对应 service 的 record 抽取。**改端点时同步改本文件**。

---

## 1. 响应壳与 HTTP 状态码

```jsonc
{ "code": 0, "message": "ok", "data": { /* payload */ } }   // 成功
{ "code": 4001, "message": "余额不足", "data": null }        // 业务失败
```

**`code` 与 HTTP 状态码是两套东西，不要混。** 实际映射（`GlobalExceptionHandler` + `SecurityConfig`）：

| 情况 | HTTP | body |
|---|---|---|
| 成功 | **200** | `{code:0, message:"ok", data}` |
| 业务异常（`BizException`，即 ErrorCode 全表的所有码） | **200** | `{code:<ErrorCode>, message, data:null}` |
| `@Valid` 请求体校验失败 | **400** | `{code:4000, message:<首个字段错误>, data:null}` |
| `@RequestParam`/`@PathVariable` 校验失败 | **400** | `{code:4000, message:<violation>, data:null}` |
| 未认证 / token 无效 / audience 不匹配 | **401** | **无 body**（裸 401） |

三点容易写错：

- **业务错误是 HTTP 200**，不是 4xx/5xx。`AI_FAILED`（5001）也走 200。前端不能靠 HTTP 状态码判业务成败，只能读 body 的 `code`。
- **401 是裸响应，没有 `ApiResponse` body**（`HttpStatusEntryPoint`，不经 `GlobalExceptionHandler`）。前端拦截器**绝不能**在 401 分支尝试读 `data.code`。
- **`code:4000` 不在 `ErrorCode` 枚举里**，硬编码在 `GlobalExceptionHandler`。只 grep 枚举会漏掉它。

**例外端点**：`GET /api/health` 直接返回 `{"status":"UP"}`，**不裹 `ApiResponse`**（`HealthController` 返回裸 `Map`）。

**命名风格**：Java 无 Jackson `SNAKE_CASE` 配置，故**对前端输出 camelCase**（`whyHot` / `citedCardIds` / `reviewState`）。注意与 `AiClient` 对 Python 的 snake_case 区分开——那边靠逐个 `@JsonProperty` 标注，两个方向不同。

---

## 2. 鉴权与 401 行为

两条独立 `SecurityFilterChain`（`SecurityConfig`），token 互不通用：

| 链 | `@Order` | 匹配 | 免鉴权路径 | 过滤器 |
|---|---|---|---|---|
| admin | 1 | `/api/admin/**` | `/api/admin/auth/login` | `AdminJwtFilter`（校验 admin audience） |
| user | 2 | `/api/**` | `/api/health`、`/api/auth/**` | `UserJwtFilter`（校验 user audience） |

admin 链 `@Order` 更小 → 更具体路径先匹配。user token 访问 `/api/admin/**` 会走 admin 链被拒 401，反之亦然。两链均 STATELESS + CSRF disable，**无 session、无 cookie**。

### 前端侧（sks-web `src/api/client.ts`）

| | C 端 | 管理端 |
|---|---|---|
| axios 实例 | `userClient` | `adminClient` |
| baseURL | `/api` | `/api/admin` |
| localStorage token key | `sks_token` | `sks_admin_token` |
| localStorage 回跳 key | `sks_return_to` | `sks_admin_return_to` |
| 登录页 | `/login` | `/admin/login` |

**token 走 `Authorization: Bearer <token>` 请求头，不走 cookie。** 上表的 key 名是 localStorage 的键名，不是 header 名。

**响应拦截器**：`code === 0` 时**解包返回 `body.data`**（调用方直接拿 payload，形如 `client.get<T, T>(...)`）；`code !== 0` 抛 `BizError(code, message)`。

**401 行为**：清 token → 存当前路径到回跳 key → `window.location.href` 跳登录页 → reject `BizError(401, '登录已过期，请重新登录')`。若当前已在登录页则不存不跳（防循环）。

`router.tsx` 的守卫另外检查 localStorage 有无 token，无则 `<Navigate>` 到登录页——router 守卫拦前端导航，axios 拦截器拦后端 401，双保险。

> 当前实现**只存回跳路径，不存表单内容**。PRD §11.6 的「表单存 localStorage」是既有 gap，不要把契约写成「401 保内容」。

前端 `readableBizMessage` 对四个码做了本地文案覆盖，其余直接回显后端 `message`：`4002` → 发送过于频繁；`4003` → 验证码错误；`4004` → 验证码错误次数过多，请 10 分钟后再试；`4011` → 账号或密码错误。

---

## 3. ErrorCode 全表

来源 `com.sks.common.ErrorCode`（全部经 `BizException` 抛出，**HTTP 一律 200**），外加 `GlobalExceptionHandler` 的 4000。

| code | 常量 | 默认 message | 说明 |
|---|---|---|---|
| 0 | —（`ApiResponse.ok`） | `ok` | 成功 |
| 4000 | —（handler 硬编码） | 首个字段校验错误 | 参数校验失败，**HTTP 400** |
| 4001 | `INSUFFICIENT_BALANCE` | 余额不足 | 扣额度原子更新影响行数为 0 |
| 4002 | `SMS_RATE_LIMIT` | 短信发送过于频繁，请稍后再试 | 三级频控 |
| 4003 | `SMS_CODE_INVALID` | 验证码错误 | 含跨 scene 校验失败 |
| 4004 | `SMS_CODE_LOCKED` | 验证码错误次数过多，已锁定 10 分钟 | 锁定期发新码同样被拒 |
| 4005 | `PARAM_INVALID` | 参数不合法 | 业务级参数问题（区别于 4000 的框架校验） |
| 4006 | `CARD_IN_USE` | 卡片已被稿件引用，无法直接删除 | 删卡需 `?force=true` |
| 4007 | `PHONE_ALREADY_BOUND` | 该手机号已被其他账号绑定 | 换绑 |
| 4008 | `PHONE_CHANGE_TOKEN_INVALID` | 换绑凭证无效或已过期，请重新发起 | 换绑 step token |
| 4010 | `UNAUTHORIZED` | 未登录或登录已过期 | service 层兜底（安全层已拦，理论罕见） |
| 4011 | `ADMIN_UNAUTHORIZED` | 管理员未登录或无权限 | 管理端登录失败统一用此码，**不区分「无此用户」与「密码错」** |
| 5001 | `AI_FAILED` | AI 服务异常，请稍后再试 | Python 非 2xx / 超时；**已退款** |
| 5002 | `CONTENT_BLOCKED` | 内容不符合安全规范，已被拦截 | UGC 或 LLM 产出命中安全 |
| 5003 | `SMS_SEND_FAILED` | 短信发送失败，请稍后再试 | 阿里云 DYPNS 调用失败 |

> `message` 可被覆盖：`BizException(ErrorCode, String)` 变体会透传动态文案（如「有 3 篇稿件引用此卡」），`GlobalExceptionHandler` 用 `ex.getMessage()` 而非 `errorCode.msg()`。前端不要 hardcode 匹配 message 文本。

范围约定：`40xx` 业务异常（客户端可见），`50xx` 外部服务/内容安全异常，`4010`/`4011` C 端/管理端未授权。

---

## 4. C 端端点（`userClient`，baseURL `/api`）

路径列的是**完整后端路径**；前端调用时去掉 `/api` 前缀。

### 健康检查 / 鉴权

| 方法 | 路径 | 鉴权 | 请求 | `data` |
|---|---|---|---|---|
| GET | `/api/health` | 免 | — | **裸** `{"status":"UP"}`，不裹壳 |
| POST | `/api/auth/send-code` | 免 | `{phone}` | `null` |
| POST | `/api/auth/login` | 免 | `{phone, code}` | `{token, userId, isNew}` |

**登录即注册**：手机号不存在则插入 `app_user` 并返回 `isNew=true`，同时触发注册钩子（建账 → 建 `status='trial'` 免费体验单 → 送 `sks.trial-credit` 条体验额度，来自 `TRIAL_CREDIT` env，默认 3）。钩子与 `app_user` 插入同事务，失败一并回滚，重试登录仍为 `isNew=true`。

### 用户

| 方法 | 路径 | 请求 | `data` |
|---|---|---|---|
| GET | `/api/user/me` | — | `MeResponse` |
| PUT | `/api/user/me` | `MeResponse` 的可写子集 | `MeResponse` |

`MeResponse`：`{userId, phone, nickname, gender, age, city, industry, identity, style, weeklyGoal, defaultPlatform, completeness, balance}`。

### 换绑手机号（四步）

| 方法 | 路径 | 请求 | `data` |
|---|---|---|---|
| POST | `/api/user/phone/change/send-old-code` | — | `null` |
| POST | `/api/user/phone/change/verify-old` | `{code}` | `{token}` |
| POST | `/api/user/phone/change/send-new-code` | `{newPhone, token}` | `null` |
| POST | `/api/user/phone/change/verify-new` | `{newPhone, code, token}` | `null` |

`token` 是换绑凭证，失效返回 4008。新号已被占用返回 4007。

### 知识库

| 方法 | 路径 | 请求 | `data` |
|---|---|---|---|
| POST | `/api/kb/cards` | `{layer, cardType, title, content}` | `Long`（新卡 id） |
| PUT | `/api/kb/cards/{id}` | `{title, content}` | `null` |
| DELETE | `/api/kb/cards/{id}?force=false` | — | `null` |
| GET | `/api/kb/cards?layer=` | — | `List<CardSummary>` |

`CardSummary`：`{id, layer, cardType, title, content, updatedAt}`。`content` 是 JSON **文本**（JSONB 列的字符串形式），不是对象。

删卡时若被稿件引用返回 4006，需 `?force=true` 强删。B 层卡新建/编辑会同步调 Python `/ai/embed` 写 `embedding`。

### 补卡

| 方法 | 路径 | 请求 | `data` |
|---|---|---|---|
| POST | `/api/kb/supplement` | `{rawText, layer}` | `SupplementResult` |
| POST | `/api/kb/supplement/confirm` | `{layer, cards, conflicts, overwriteCardIds}` | `ConfirmResult` |

- `SupplementResult`：`{createdIds, cards, gaps, conflicts}`。`conflicts` 非空表示前端需展示冲突并调 confirm；为空则 `createdIds` 已落库。
- `cards` 元素 `{cardType, title, content}`，`conflicts` 元素 `{cardId, cardIndex, reason}`（`cardIndex` 指向 `cards` 数组下标）。
- confirm 请求体是**原样回传** supplement 的 `cards` + `conflicts`，外加用户选中要覆盖的 `overwriteCardIds`。
- `ConfirmResult`：`{createdIds, overwrittenIds}`。

### 定位校准

| 方法 | 路径 | 请求 | `data` |
|---|---|---|---|
| GET | `/api/profile` | — | `ActiveProfileView` |
| POST | `/api/profile/interview` | `{sessionId, reply, materials}` | `InterviewStepView` |
| POST | `/api/profile/voice` | `multipart`，字段名 **`audio`** | `String`（转写文本） |
| POST | `/api/profile/confirm` | `{sessionId}` | `null` |
| POST | `/api/profile/sample-opening` | `{sessionId, topic}` | `SampleOpeningResponse` |

- 首轮带 `materials`、`reply=null`；后续轮反之。
- `InterviewStepView`：`{stage, question, profileDraft, done, blocked, banner}`。`profileDraft` 是 JSON **对象**（`JsonNode`），`done=true` 时前端展示档案草稿 + 确认按钮。
- `voice` 音频为空返回 4005；ASR 失败返回 5001，前端提示改用文字输入，**不阻断访谈**。
- 校准全程**免费**，无额度扣减。`confirm` 时访谈未完成返回 4005。
- `ActiveProfileView`：`{calibrated, version, calibratedAt, content}`。无 active 行返 `calibrated=false`（非 404）。
- `sample-opening`：调用前需先走完访谈（checkpoint 有 profile）；未完成返 4005。`topic` 省略时默认「报价为什么差一倍」。返回 `{found, topic, without, with}` 两版开场钩子。

### 选题

| 方法 | 路径 | 请求 | `data` |
|---|---|---|---|
| GET | `/api/topics?source=` | — | `List<Topic>` |
| GET | `/api/topics/{id}` | — | `Topic` |
| POST | `/api/topics` | `{title, rationale, source}` | `Long`（新选题 id） |

### 稿件

| 方法 | 路径 | 请求 | `data` |
|---|---|---|---|
| POST | `/api/scripts/generate` | `{topicId, platform}` | `ScriptDetail` |
| GET | `/api/scripts?state=` | — | `List<ScriptSummary>` |
| GET | `/api/scripts/{id}` | — | `ScriptDetail` |
| PUT | `/api/scripts/{id}/sentence` | `{section, idx, text}` | `null` |
| POST | `/api/scripts/{id}/rewrite-sentence` | `{section, idx}` | `{preview}` |

- `ScriptDetail`：`{id, topicId, hook, body, cta, platform, reviewState, citedCardIds, createdAt, updatedAt, dedupWarnScriptId}`。`hook/body/cta` 是 JSON **文本**（形如 `{"sentences":[{"idx":0,"text":"..."}]}`）。
- `dedupWarnScriptId` **仅 `POST /generate` 可能非空**（命中近复稿告警），其余端点恒为 `null`。
- `ScriptSummary`：`{id, topicId, platform, reviewState, createdAt, updatedAt}`，不含正文三段。
- `generate` 扣额度：余额不足 4001；AI 失败 5001（**已退款**）；命中安全 5002（**已退款**）。
- `rewrite-sentence` 只返回预览文本**不落库**，免费；命中安全直接 5002（无退款编排，原句保留）。

### 复盘

| 方法 | 路径 | 请求 | `data` |
|---|---|---|---|
| POST | `/api/review/{scriptId}/adopt` | — | `null` |
| POST | `/api/review/{scriptId}/track` | `{url}` | `null` |
| POST | `/api/review/{scriptId}/play` | `{count}` | `{reviewState}` |
| POST | `/api/review/{scriptId}/attribute` | — | `{diagnosis, suggestions}` |
| POST | `/api/review/{scriptId}/feedback` | `{reason}` | `null` |
| GET | `/api/review/weekly?week=` | — | `JsonNode`（周报 JSON 对象） |

`attribute` **免费**且**不改复盘态**；命中安全返回 5002。

### 拆解

| 方法 | 路径 | 请求 | `data` |
|---|---|---|---|
| POST | `/api/analyze/video/text` | `{transcript}` | `VideoTextResponse` |
| POST | `/api/analyze/video/link` | `{url}` | `{taskId}` |
| POST | `/api/analyze/account` | `{url}` | `{taskId}` |
| GET | `/api/analyze/tasks/{id}` | — | `TaskDetail` |

- `VideoTextResponse`：`{structure, whyHot, framework, diffHint}`（**camelCase**——Python 侧是 `why_hot`/`diff_hint`，`AiClient` 已做转换）。
- `TaskDetail`：`{id, taskType, status, progress, charged, result, error, updatedAt, createdAt, videos}`。`result` 是 JSON **文本**。
- `videos` 元素 `BenchmarkVideoView`：`{id, title, playCount, favCount, transcript, structure, createdAt}`。
- `link` / `account` 是**异步**：返回 `taskId` 后前端轮询 `GET /api/analyze/tasks/{id}`。
- `status` 取值 `queued` / `running` / `partial` / `done` / `failed`。
- **`progress` 语义**：已完成条数 / 总条数 × 100（整数），**不是**阶段进度。按比例退款依赖此口径。

---

## 5. 管理端端点（`adminClient`，baseURL `/api/admin`）

| 方法 | 路径 | 鉴权 | 请求 | `data` |
|---|---|---|---|---|
| POST | `/api/admin/auth/login` | 免（admin 链内唯一 permitAll） | `{username, password}` | `{token, adminId, name}` |
| GET | `/api/admin/users?phoneTail=` | admin JWT | — | `List<Map>` |
| GET | `/api/admin/orders?status=` | admin JWT | — | `List<Map>` |
| POST | `/api/admin/orders/open` | admin JWT | `{userId, pkg}` | `Integer`（影响行数） |
| POST | `/api/admin/compensate` | admin JWT | `{userId, n, memo}` | `Integer`（影响行数） |

- 登录失败（用户不存在 / 密码错 / 哈希异常）统一 4011，**不区分原因**。
- 管理端**无注册入口**，账号由 Flyway `V2__seed_admin.sql` 种子写入，密码哈希取自 `ADMIN_SEED_PASSWORD` 环境变量。
- `phoneTail` 必填非空（缺失 → 4000）；`compensate` 的 `n` 须 ≥ 1。
- `users` / `orders` 返回的是 `Map` 列表（未定义严格 record），字段随 SQL 变化——前端改动前先看 `AdminOrderController` 对应 mapper。

---

## 6. 复盘状态机取值

`reviewState` 出现在 `ScriptDetail` / `ScriptSummary` / `PlayResponse`。七个终值（`ReviewStateMachine`）：

| 值 | 含义 | 入口 |
|---|---|---|
| `draft` | 生成后未采用 | 生成成功 |
| `pending` | 已采用，待登记发布链接 | `draft + adopt` |
| `tracking` | 已登记链接，待填播放量 | `pending + track` |
| `hot` | 爆款 | `tracking + play`，`play >= avg × 3` |
| `plain` | 平平（带内） | `tracking + play`，两阈值之间；**无 baseline 时首条稿也判 plain** |
| `flop` | 扑街 | `tracking + play`，`play < avg × 0.5` |
| `rejected` | 48h 未采用的 draft | `RejectSweeper` 定时扫（**只扫 draft，不扫 pending**） |

`avg` = 近 30 天已复盘稿（`hot`/`plain`/`flop` 且播放量非空）的播放量均值。阈值可配：`sks.review.hot-threshold`（默认 3.0）/ `sks.review.flop-threshold`（默认 0.5）。

`hot`/`plain`/`flop`/`rejected` 是终态，再收事件抛 `IllegalStateException`。另有 `generating` / `failed` 两个生成期前置态，由 `ScriptService` 管，**不在状态机内**。

**无 AI 判态**：七态全由 Java 规则转换。`hot` 的副作用（补 C 层卡 + 续集选题）与 `flop` 的归因诊断都发生在态已定之后，不影响状态。

---

## 7. 改契约时的同步清单

1. Controller 的 record + service 返回类型（真相源）
2. 本文件对应小节
3. sks-web `src/api/*.ts` 的类型声明与调用路径
4. 新增 ErrorCode 时：`ErrorCode` 枚举 + 本文件 §3 表 + 前端 `readableBizMessage`（若需本地文案覆盖）

跨仓另一侧：Java ↔ Python 的 HTTP 契约见 sks-ai 仓 `docs/API_CONTRACT.md`（`AiClient` 须与之对齐）。
