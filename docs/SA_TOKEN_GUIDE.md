# Sa-Token 技术文档与使用指南（结合 PDK / 拼多客项目）

> 本文档分为三大部分：
> 1. **Sa-Token 技术说明**（它是什么、核心概念、通用用法）
> 2. **使用方法与 API 速查**（怎么登录、校验、注销、拦截、鉴权）
> 3. **结合 PDK 项目的实战说明**（本项目怎么用、踩过哪些坑）
>
> 文档中所有「项目代码」均来自 `backend-springboot` 真实实现，可对照源码阅读。

---

## 一、Sa-Token 是什么

**Sa-Token** 是一个轻量级的 Java 权限认证框架（dromara 开源社区出品，Apache-2.0 协议），主打「**以登录为中心**」的极简 API，用来替代传统 Shiro / 手写 JWT 的繁琐。

它一次性解决了：

| 能力 | 说明 |
|---|---|
| 登录认证 | 一行 `login(id)` 即可登录，自动签发并管理 Token |
| 权限认证 | 角色 / 权限校验，支持注解与代码两种方式 |
| 踢人下线 | 主动让某个账号或某台设备会话失效 |
| 会话管理 | 每个账号一个 Session，可存任意业务数据 |
| 多账号体系 | 一套系统里「管理员 / 客户端用户」互不串号 |
| 单端 / 多端控制 | 控制同一账号能否在多个设备同时在线 |
| 注解鉴权 | `@SaCheckLogin`、`@SaCheckPermission`、`@SaCheckRole` |
| 路由拦截 | 基于 Spring 拦截器统一鉴权 |
| 分布式会话 | 接 Redis 后即可多实例共享会话 |

**为什么本项目选 Sa-Token 而非 JWT**：JWT 是无状态签名令牌，本身不记录「登录态」，踢人、续期、单端互踢都要自己造轮子；Sa-Token 原生提供会话、踢人、单设备控制，正好覆盖 PDK「账号绑定电脑、异地强制下线」的业务诉求。本项目**不使用 JWT**（详见 `PROJECT_MAP.md` 的「鉴权澄清」）。

---

## 二、核心概念

### 2.1 LoginId（登录标识）
登录时写入会话的**唯一标识**。它可以是 `Long`、字符串等任意对象。
- 本项目 client 端：`user.getId()`（数字，如 `123`）。
- 本项目 admin 端：带前缀的字符串 —— `"ADMIN:" + id` 或 `"USER:" + id`，用来在同一套 `adminStpLogic` 里区分「超级管理员」与「合伙人」。

### 2.2 Token（令牌）
- **token-name**：令牌在请求头 / Cookie 中的字段名。本项目统一为 `satoken`。
- **token-value**：令牌值，登录后由 `getTokenValue()` 返回给前端。
- **token-style**：令牌生成算法。本项目用 `uuid`（默认风格）。

> 前端拿到 `{ tokenName, tokenValue }` 后，后续每个请求把 `tokenValue` 放进名为 `tokenName` 的请求头即可。

### 2.3 Session（会话）
Sa-Token 为每个 LoginId 维护一个会话对象，可 `setAttribute / getAttribute` 存业务数据。默认**存进程内存**（见第六部分部署注意）。

### 2.4 多账号体系（loginType / StpLogic）——本项目最关键
默认工具类叫 `StpUtil`（等价于 `loginType = "login"`）。当系统存在**多套互不相干的账号**（如 admin 与 client），用 `new StpLogic("customType")` 创建**独立命名空间**的实例：
- 两套账号的 token、**互不可通用**；
- 各自的 login / checkLogin / getLoginId 互不干扰。

本项目正是用 `adminStpLogic` 与 `clientStpLogic` 把「后台管理员」和「客户端用户」彻底隔开。

### 2.5 权限 / 角色
Sa-Token 支持两种权限来源：
- **代码式**：`StpUtil.hasPermission("xxx")` / `checkPermission`。
- **注解式**：`@SaCheckPermission("xxx")` / `@SaCheckRole("xxx")`。

本项目未用 Sa-Token 内置的权限表，而是**自定义**了 `@RequirePermission` 注解 + `RolePermissions` 静态映射（见 5.5），更贴合业务。

---

## 三、通用使用方法（速查）

### 3.1 引入依赖（Maven）
```xml
<dependency>
    <groupId>cn.dev33</groupId>
    <artifactId>sa-token-spring-boot3-starter</artifactId>
    <version>1.38.0</version>
</dependency>
<!-- 多实例部署时再加（本项目暂未用）： -->
<!-- <dependency>
    <groupId>cn.dev33</groupId>
    <artifactId>sa-token-redis-jackson</artifactId>
    <version>1.38.0</version>
</dependency> -->
```

### 3.2 基础配置（application.yml）
```yaml
sa-token:
  token-name: satoken          # 令牌请求头名称
  timeout: 2592000             # Token 有效期（秒），30 天
  active-timeout: -1           # 活跃超时，-1 表示不自动续期/不按活跃失效
  is-concurrent: false         # 是否允许同账号并发登录（false = 互斥，后登录踢前者）
  is-share: false              # 是否同端共享 Token
  token-style: uuid            # 令牌风格
```

### 3.3 最常调用的 API（以默认 `StpUtil` 为例）
```java
// 登录（写入 LoginId 并签发 Token）
StpUtil.login(10001);

// 校验登录态（未登录直接抛 NotLoginException）
StpUtil.checkLogin();

// 获取当前登录标识
Object id = StpUtil.getLoginId();
long  idL = StpUtil.getLoginIdAsLong();
String idS = StpUtil.getLoginIdAsString();

// 获取令牌（返回给前端）
String name  = StpUtil.getTokenName();    // "satoken"
String value = StpUtil.getTokenValue();   // 令牌值

// 注销（当前会话失效）
StpUtil.logout();

// 踢人（让某个账号所有会话失效）
StpUtil.kickout(10001);

// 权限校验
StpUtil.checkPermission("card:renew");
boolean ok = StpUtil.hasPermission("card:renew");
```

> **本项目不用 `StpUtil`，而用 `adminStpLogic` / `clientStpLogic`**——它们是 `new StpLogic("admin")` / `new StpLogic("client")` 的 Spring Bean，方法名与上面完全一样，只是作用域不同。

### 3.4 路由拦截（鉴权统一收口）
```java
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/**")
                .excludePathPatterns("/user/login", "/user/register");
    }
}
```

### 3.5 注解鉴权
```java
@SaCheckLogin              // 必须登录
@SaCheckRole("admin")      // 必须是 admin 角色
@SaCheckPermission("user:delete")  // 必须有该权限
@PostMapping("/delete")
public SaResult delete() { ... }
```

### 3.6 异常处理（未登录 → 401）
```java
@RestControllerAdvice
public class GlobalException {
    @ExceptionHandler(NotLoginException.class)
    public SaResult handle(NotLoginException e) {
        return SaResult.error(401, "登录失效，请重新登录");
    }
}
```

---

## 四、进阶能力（本项目用到的）

### 4.1 多账号体系（两套 StpLogic）
关键代码（本项目真实）：
```java
@Configuration
public class SaTokenLogicConfig {
    @Bean("adminStpLogic") @Primary
    public StpLogic adminStpLogic() { return new StpLogic("admin"); }

    @Bean("clientStpLogic")
    public StpLogic clientStpLogic() { return new StpLogic("client"); }
}
```
注入时使用 `@Qualifier` 指定：
```java
@Qualifier("clientStpLogic") private final StpLogic clientStpLogic;
```

### 4.2 单端 / 单设备控制
- `is-concurrent: false`：同账号同端互斥（后登录者踢掉前者）。
- 本项目还在 `DeviceSecurityInterceptor` 里**按设备**二次校验 `X-PDK-Device-ID`，实现「账号绑定电脑、异地强制下线」。

### 4.3 分布式会话（多实例）
默认会话在 JVM 内存，**多副本部署会丢失**。接 Redis 后自动共享：
```xml
<dependency>
    <groupId>cn.dev33</groupId>
    <artifactId>sa-token-redis-jackson</artifactId>
    <version>1.38.0</version>
</dependency>
```
> 本项目当前为单实例（见第六部分）。

---

## 五、PDK 项目实战（结合代码）

### 5.1 依赖与版本
`backend-springboot/pom.xml`：
```xml
<sa-token.version>1.38.0</sa-token.version>
<!-- 权限控制 Sa-Token -->
<dependency>
    <groupId>cn.dev33</groupId>
    <artifactId>sa-token-spring-boot3-starter</artifactId>
    <version>${sa-token.version}</version>
</dependency>
```

### 5.2 配置 `application.yml`
```yaml
sa-token:
  token-name: satoken
  timeout: 2592000        # 30 天
  active-timeout: -1
  is-concurrent: false
  is-share: false
  token-style: uuid
```

### 5.3 双 StpLogic 定义
见 4.1：`adminStpLogic`（`@Primary`）+ `clientStpLogic`。

### 5.4 登录实现（client 与 admin 对比）

**客户端登录**（`ClientAuthController`，用 `clientStpLogic`）：
```java
// 注册成功后直接登录
clientStpLogic.login(user.getId());          // LoginId = 数字 userId

// 普通登录
clientStpLogic.login(user.getId());

// 返回前端 { tokenName, tokenValue, ... }
data.put("tokenName",  clientStpLogic.getTokenName());   // "satoken"
data.put("tokenValue", clientStpLogic.getTokenValue());  // 令牌值
```
登出：
```java
clientStpLogic.logout();   // /api/v1/client/auth/logout、/unbind-device
```

**管理后台登录**（`AdminAuthController`，用 `adminStpLogic`）：
```java
// 超级管理员
adminStpLogic.login("ADMIN:" + admin.getId());
// 合伙人（普通客户中的 PARTNER 角色）
adminStpLogic.login("USER:" + user.getId());
```
> 注意 LoginId 带了 `ADMIN:` / `USER:` 前缀，拦截器靠它区分两类后台操作者。

### 5.5 拦截器鉴权

**`DeviceSecurityInterceptor`（守卫 client / dispatch 接口）**：
```java
clientStpLogic.checkLogin();                              // ① Sa-Token 校验登录态
User user = userMapper.selectById(clientStpLogic.getLoginIdAsLong());

String userPhone = request.getHeader("X-PDK-Phone");
String currentDeviceId = request.getHeader("X-PDK-Device-ID");
if (userPhone == null || currentDeviceId == null)
    throw new BusinessException(40101, "缺少安全鉴权请求头");   // ② 业务层设备校验
if (!user.getPhone().equals(userPhone))
    throw new BusinessException(40102, "会话与请求手机号不一致");
// ③ 单设备互踢：比对 activeDeviceId 与 incomingDeviceId
if (activeDeviceId == null || !activeDeviceId.equals(currentDeviceId))
    throw new BusinessException(40103, "ERR_DEVICE_KICK_OUT: 账号已在其他电脑登录");

request.setAttribute("pdkClientUser", user);             // 供 Controller 直接用
```
> 这套拦截器在 Sa-Token 之上**叠加了设备绑定业务校验**，是 PDK「一账号一机」安全模型的核心。

**`AdminAuthInterceptor`（守卫 admin 接口）**：
```java
adminStpLogic.checkLogin();
String loginId = adminStpLogic.getLoginIdAsString();
AdminPrincipal principal = resolve(loginId);              // 解析 ADMIN:/USER: 前缀
// 方法/类上的 @RequirePermission 注解校验
RequirePermission req = ...;
if (req != null && !RolePermissions.has(principal.roleCode(), req.value()))
    throw new BusinessException(40310, "当前角色无权执行该管理任务");
request.setAttribute("pdkAdminPrincipal", principal);
```
`resolve` 逻辑：
```java
if (loginId.startsWith("ADMIN:")) { /* 查 AdminUser，必须 SUPER_ADMIN */ }
if (loginId.startsWith("USER:"))   { /* 查 User + Credential，必须 PARTNER */ }
```

### 5.6 统一异常处理
`GlobalExceptionHandler` 捕获 Sa-Token 的 `NotLoginException`：
```java
@ExceptionHandler(NotLoginException.class)
public CommonResult<Void> handleNotLogin(NotLoginException e) {
    return CommonResult.failed(40100, "登录状态无效或已过期，请重新登录");
}
```
前端据此 code `40100 / 40110` 清空本地会话、跳转登录。

### 5.7 前端如何携带 Token

**管理后台 `admin-vue3/src/api.ts`**（axios 拦截器自动注入）：
```ts
api.interceptors.request.use((config) => {
  const session = authState.session;
  if (session) {
    config.headers[session.tokenName || 'satoken'] = session.tokenValue;
  }
  return config;
});
```
响应拦截器遇到 `code === 40100 || 40110` 自动 `clearSession()`。

**客户端 / SDK**：除 `satoken` 头外，**还必须带** `X-PDK-Phone` 与 `X-PDK-Device-ID`（见 `DeviceSecurityInterceptor`），否则返回 `40101`。

### 5.8 路由守卫矩阵（WebMvcConfig）

| 拦截器 | 守卫路径 | 放行（白名单） |
|---|---|---|
| `DeviceSecurityInterceptor` | `/api/v1/client/**`、`/api/v1/dispatch/**` | `client/auth/login`、`client/auth/register`、`client/auth/sms/send`、`client/auth/change-password` |
| `AdminAuthInterceptor` | `/api/v1/admin/**` | `admin/auth/login` |

> 登录 / 注册 / 发短信接口必须放行，否则「还没拿到 token 就被拦」。

---

## 六、本项目踩过的坑 / 经验总结

1. **admin 与 client 的 StpLogic 绝不能混用**
   用 `clientStpLogic` 登录的账号，拿 `adminStpLogic.checkLogin()` 校验必然抛 `NotLoginException`。两套是独立命名空间。注入时务必用 `@Qualifier` 精确指定。

2. **LoginId 前缀约定要前后一致**
   admin 端用 `"ADMIN:" + id` / `"USER:" + id`，`AdminAuthInterceptor.resolve()` 靠 `startsWith` 解析。若以后新增后台角色，必须同步改前缀解析逻辑。

3. **放行白名单必须包含登录/注册**
   当初若把 `client/auth/login`、`register`、`sms/send` 也拦了，用户永远拿不到第一个 token。新增认证类接口时记得在 `WebMvcConfig.excludePathPatterns` 同步放行。

4. **单实例内存会话 = 部署红线**
   当前 `sa-token` 会话存 JVM 内存（未接 Redis）。**只能跑一个后端实例**；多副本前必须先接入 `sa-token-redis-jackson`（见 4.3）。`BUILD_AND_DEPLOY.md` / `BACKEND_AUDIT_REPORT.md` 也记录了这一点。

5. **token-name 是前后端硬约定**
   配置里 `token-name: satoken`，前端 `api.ts` 里 `session.tokenName || 'satoken'` 兜底。改这个名字要前后端一起改，否则所有请求 401。

6. **本项目不用 JWT**
   仓库内曾出现的 `jwt` / `eyJ` 字样已全部清除（死依赖 `@google/genai` 已移除）。鉴权 100% 由 Sa-Token 负责，不要再引入 JWT。详见 `PROJECT_MAP.md`「鉴权澄清」与 `ISSUES_AND_INCONSISTENCIES.md` #10。

---

## 七、常见问题 FAQ

**Q1：前端收到 40100 但刚登录过？**
- 检查请求头有没有带 `satoken`（值=登录返回的 `tokenValue`）；admin 后台还要确认 axios 拦截器已注入。
- 客户端接口还要带 `X-PDK-Phone` + `X-PDK-Device-ID`，缺一个返回 `40101`。

**Q2：同一账号在两台电脑登录，为什么一台被踢？**
- `is-concurrent: false` + `DeviceSecurityInterceptor` 的设备校验共同作用，实现单设备在线。属预期行为（`40103 ERR_DEVICE_KICK_OUT`）。

**Q3：改了密码 / 冻结账号，旧 token 还有效吗？**
- `DeviceSecurityInterceptor` 每次请求都会查库校验账号是否存在 / 是否 `FROZEN`，命中即 `logout()` 并抛 `40100`。所以冻结会立即生效。

**Q4：要支持多后端实例怎么办？**
- 引入 `sa-token-redis-jackson` + `spring-boot-starter-data-redis`，配置 Redis 连接即可，业务代码无需改动。

**Q5：如何新增一个「需要管理员权限」的接口？**
- 在 Controller 方法或类上加 `@RequirePermission("xxx")`，并在 `RolePermissions` 里给对应角色配置该权限；`AdminAuthInterceptor` 会自动拦截校验。

---

## 八、参考
- Sa-Token 官方文档：https://sa-token.cc
- 项目源码：
  - `backend-springboot/src/main/java/com/pdk/config/SaTokenLogicConfig.java`
  - `backend-springboot/src/main/java/com/pdk/config/WebMvcConfig.java`
  - `backend-springboot/src/main/java/com/pdk/interceptor/DeviceSecurityInterceptor.java`
  - `backend-springboot/src/main/java/com/pdk/interceptor/AdminAuthInterceptor.java`
  - `backend-springboot/src/main/java/com/pdk/controller/ClientAuthController.java`
  - `backend-springboot/src/main/java/com/pdk/controller/AdminAuthController.java`
  - `backend-springboot/src/main/java/com/pdk/common/exception/GlobalExceptionHandler.java`
  - `backend-springboot/src/main/resources/application.yml`
  - `admin-vue3/src/api.ts`
