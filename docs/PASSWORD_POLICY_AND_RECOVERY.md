# 密码策略与账号找回方案

## 1. 设计决策（结论）

| 议题 | 决策 | 理由 |
| --- | --- | --- |
| 配置粒度 | **业务级默认 + 用户级覆盖** | 业务级定统一策略，用户级处理例外（管理员代重置、疑似泄露、VIP 豁免） |
| 强制力度 | **仅提示，不拦截** | 已确认。避免过度锁定存量用户，兼容性最好 |
| 忘记密码 | **自助短信重置 + 管理员代重置**两条路 | 商用系统标配：自助为主、人工兜底 |

强制力度保持"仅提示"意味着：登录接口继续返回 `mustChangePassword`，由客户端决定是否弹改密框，后端**不新增拦截器**。

## 2. 现状盘点

| 能力 | 现状 | 缺口 |
| --- | --- | --- |
| 业务级开关 | ✅ `pdk_business.force_initial_password_change`，业务管理页「首次登录必须改密」可改 | 无 |
| 用户级标记 | ⚠️ `pdk_user_credential.must_change_password` 字段存在，用户管理页**只读展示** | 管理员无法对单个用户切换 |
| 登录后改密 | ✅ `POST /client/auth/change-password` | **必须填旧密码**，忘密者用不了 |
| 自助找回 | ❌ 完全缺失 | 无重置接口 |
| 管理员代重置 | ❌ 缺失 | 只能在建号时设初始密码，无法重置存量用户 |
| 短信重置用途 | ⚠️ `SendSmsDTO.purpose` 已允许 `RESET_PASSWORD`，`SmsCodeService` 已按 `(bizId, phone, purpose)` 隔离与频控 | **没有任何接口使用它**（半建成设施） |

**真实的业务后果**：用户忘记密码 → 管理员也没有重置手段 → 只能删号重建，而删号会丢失套餐、次数、分配的小号与消费流水。这对商用系统是不可接受的。

好在基础设施是现成的：字段、短信用途、频控、审计日志都已具备，本次主要是**补齐缺失的接口与入口**，不需要新的 DDL。

## 3. 配置模型

```
pdk_business.force_initial_password_change   （业务级默认，业务管理页可配）
                 ↓ 作为默认值
pdk_user_credential.must_change_password     （用户级实际值，可覆盖）
                 ↓
         登录响应 mustChangePassword        （仅提示，不拦截）
```

赋值规则：

| 场景 | `must_change_password` | 说明 |
| --- | --- | --- |
| 管理员建号 | = 业务的 `forceInitialPasswordChange` | 现有逻辑，不变 |
| 自助注册 | 0 | 现有逻辑，不变 |
| **管理员代重置密码** | **强制 1** | 不受业务配置影响。管理员知道临时密码，必须强制改密 |
| **自助短信重置密码** | 0 | 用户自己设的新密码，无需再改 |
| **管理员手动切换** | 由管理员指定 | 新增能力，用于例外处理 |

## 4. 新增接口

### 4.1 管理员：切换单个用户的强制改密标记

```http
PUT /api/v1/admin/user/{id}/password-policy
{ "mustChangePassword": true }
```

- 权限：`user:password:reset`（敏感操作独立授权，与 `user:edit` 分离）
- 行为：更新 `pdk_user_credential.must_change_password`，写审计日志
- **开启强制时吊销该用户全部在线会话**（确保下次登录即触发改密，与代重置一致）；取消强制则不触动会话

### 4.2 管理员：代用户重置密码

```http
POST /api/v1/admin/user/{id}/reset-password
{ "newPassword": "Temp@12345" }
```

- 权限：**新增 `user:password:reset`**（敏感操作独立授权，不与 `user:edit` 混用）
- 行为：
  1. 校验新密码 8～64 位，且不能与旧密码相同
  2. 更新 `password_hash`
  3. **`must_change_password` 强制置 1**
  4. **吊销该用户全部登录会话**（`clientStpLogic.kickout(userId)`），确保旧密码持有者下线
  5. 写审计日志（只记操作，不记明文密码）
- 响应返回临时密码仅一次，不落库明文

### 4.3 用户：自助短信重置密码

```http
POST /api/v1/client/auth/reset-password
{ "appId": 1, "phone": "13800138000", "smsCode": "123456", "newPassword": "New@12345" }
```

- 免鉴权（用户此时登不进去）
- 复用 `POST /client/auth/sms/send` 的 `purpose=RESET_PASSWORD`（DTO 已允许），`SmsCodeService` 已支持按业务隔离与频控
- 行为：
  1. 解析 `appId → BusinessContext`，校验业务可用
  2. `smsCodeService.verify(bizId, phone, "RESET_PASSWORD", code)`（验证码一次性消费）
  3. 校验新密码 8～64 位，且不能与旧密码相同
  4. 更新 `password_hash`，`must_change_password` 置 0
  5. 吊销该用户全部会话

## 5. 安全要点

- **会话吊销**：重置密码后必须 `clientStpLogic.kickout(userId)`。只改哈希不踢线，等于旧密码持有者仍在线。
- **防账号枚举**：`/sms/send` 对未注册手机号也返回统一成功文案（"若该手机号已注册，验证码已发送"），实际只在已注册时发送。`/reset-password` 失败时用统一文案，不区分"手机号不存在"与"验证码错误"。
- **验证码**：5 分钟有效、一次性消费、按 `(bizId, phone, purpose)` 频控——`SmsCodeService` 已实现，直接复用即可。
- **密码强度**：与注册一致，8～64 位；**新密码不能与旧密码相同**。
- **审计**：管理员代重置必须写 `pdk_admin_audit_log`，记录操作人、目标用户、时间，**禁止记录明文密码**。
- **不回显**：任何接口不得返回密码原文或哈希。

## 6. 权限变更

`RolePermissions` 新增：

```java
public static final String USER_PASSWORD_RESET = "user:password:reset";
```

- 加入 `ALL` 集合（SUPER_ADMIN 自动拥有）
- **PARTNER 默认不授予**（代客重置密码风险高，如需开放由超级管理员显式配置）

## 7. 前端改动

| 位置 | 改动 |
| --- | --- |
| 用户管理 `UserManager.vue` | 「首次改密」列保持展示；操作列新增「重置密码」「强制改密 / 取消强制」 |
| 业务管理 `BusinessManager.vue` | 无需改动（「首次登录必须改密」开关已存在） |
| 客户端登录页 | 新增「忘记密码」入口：填手机号 → 验证码 → 新密码（PyQt 与各 SDK 按自身 UI 实现） |
| 客户端登录后 | `mustChangePassword=true` 时提示改密（仅提示，不阻断） |

## 8. 建议错误码

| 错误码 | 含义 |
| --- | --- |
| `40330` | 验证码错误、已失效或已使用 |
| `40331` | 新密码不能与旧密码相同 |
| `40332` | 手机号未注册或验证码不匹配（统一文案，防枚举） |
| `40333` | 重置过于频繁，请稍后再试 |

## 9. 兼容与迁移

- **无需 DDL**：`must_change_password`、`force_initial_password_change`、`RESET_PASSWORD` 用途均已存在。
- 存量用户 `must_change_password` 默认 0，不受影响，不会被批量强制改密。
- 新增权限仅影响管理员侧，客户端无感知。

## 10. 验收清单

> 实现状态：后端（Java）、前端（UserManager.vue）、三端 SDK（Python / C++ / 易语言）均已落地并通过编译校验（javac EXIT=0，Python py_compile OK；C++/C ABI 签名一致，本机缺第三方头未做完整语法检查）。错误码复用既有 `40019`/`40100`/`40402`/`40011`/`40012`，未新占 `40330~40333`。

1. 业务管理页把某业务的「首次登录必须改密」关掉后，新建账号登录不再提示改密。
2. 反之开启后，新建账号登录返回 `mustChangePassword=true`。
3. 管理员在用户管理页对某用户点「强制改密」，该用户下次登录返回 `mustChangePassword=true`。
4. 管理员代重置密码后：能用临时密码登录、被要求改密、重置前已在线的会话被踢下线。
5. 用户忘记密码 → 短信验证码 → 重置成功 → 用新密码登录，不再提示改密，旧会话被踢下线。
6. 已注册手机号能收到 RESET_PASSWORD 验证码；未注册手机号调用发送接口不泄露账号是否存在。
7. 验证码用过一次即失效；错误验证码无法通过；5 分钟后失效。
8. 新密码与旧密码相同时被拒。
9. 管理员代重置写入审计日志，且日志中不含明文密码。
10. PARTNER 角色默认看不到/调不了「重置密码」。
