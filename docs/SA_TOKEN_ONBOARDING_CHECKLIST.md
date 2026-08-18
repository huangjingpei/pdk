# PDK Sa-Token 接入 Checklist（后端新人版）

> **适用对象**：刚接手 `backend-springboot` 的后端同学、需要联调登录的前端 / 客户端同学。
> **配套文档**：`docs/SA_TOKEN_GUIDE.md`（完整技术说明）、`docs/PROJECT_MAP.md`（项目全景）。
> **核心结论**：本项目 100% 用 **Sa-Token** 鉴权，**没有 JWT**。admin 与 client 是**两套独立 `StpLogic` 命名空间**，token 互不可通用。

---

## 一、后端新人接入 Checklist

下面按「环境 → 配置 → 登录 → 鉴权 → 异常 → 联调 → 上线」七个阶段列出。**每完成一项打勾 ✅**，全部打勾即代表你已掌握本项目的鉴权接入。

### 阶段 0 · 环境与依赖（先确认再动手）
- [ ] JDK 17 + Spring Boot 3 已就绪
- [ ] `pom.xml` 已引入 `sa-token-spring-boot3-starter`（版本由 `${sa-token.version}` 管理，当前 `1.38.0`）
- [ ] **不要**引入任何 `jjwt` / `java-jwt` / `jsonwebtoken` 依赖——本项目不用 JWT

### 阶段 1 · 基础配置（`application.yml`）
- [ ] 确认 `sa-token` 配置块存在且如下：
```yaml
sa-token:
  token-name: satoken        # 前后端硬约定，改了要前后端同步
  timeout: 2592000           # 30 天
  active-timeout: -1         # 不按活跃自动失效
  is-concurrent: false       # 同账号互斥（后登录踢前者）
  is-share: false
  token-style: uuid
```
- [ ] 记住 `token-name: satoken` 是**前后端契约**，前端 `api.ts` 用 `session.tokenName || 'satoken'` 兜底

### 阶段 2 · 双 `StpLogic` 定义（`SaTokenLogicConfig`）
- [ ] 确认已定义两个 Bean，且注入时**必须用 `@Qualifier` 精确指定**：
```java
@Configuration
public class SaTokenLogicConfig {
    @Bean("adminStpLogic") @Primary
    public StpLogic adminStpLogic() { return new StpLogic("admin"); }

    @Bean("clientStpLogic")
    public StpLogic clientStpLogic() { return new StpLogic("client"); }
}
```
- [ ] 业务代码里**绝不混用**：用 `clientStpLogic` 登录的账号，拿 `adminStpLogic.checkLogin()` 校验必然抛 `NotLoginException`

### 阶段 3 · 登录与登出（对照真实 Controller）
- [ ] **客户端登录**（`ClientAuthController`，用 `clientStpLogic`）:
  - 注册 / 登录成功后调用 `clientStpLogic.login(user.getId())`（LoginId = 数字 userId）
  - 返回前端 `{ tokenName: "satoken", tokenValue: <令牌值>, ... }`
  - 登出：`clientStpLogic.logout()`
- [ ] **管理后台登录**（`AdminAuthController`，用 `adminStpLogic`）:
  - 超级管理员：`adminStpLogic.login("ADMIN:" + admin.getId())`
  - 合伙人：`adminStpLogic.login("USER:" + user.getId())`
  - LoginId 带 `ADMIN:` / `USER:` 前缀，拦截器靠 `startsWith` 解析，**新增后台角色必须同步改前缀解析**
  - 返回前端：`{ tokenName, tokenValue, id, username, displayName, role, permissions[, invitationCode] }`
  - 登出：`adminStpLogic.logout()`

### 阶段 4 · 鉴权拦截（`WebMvcConfig` + 两个拦截器）
- [ ] `DeviceSecurityInterceptor` 守卫 `/api/v1/client/**`、`/api/v1/dispatch/**`
- [ ] `AdminAuthInterceptor` 守卫 `/api/v1/admin/**`
- [ ] **白名单必须放行登录/注册类接口**，否则拿不到第一个 token：
  - client 放行：`client/auth/login`、`client/auth/register`、`client/auth/sms/send`、`client/auth/change-password`
  - admin 放行：`admin/auth/login`
- [ ] `DeviceSecurityInterceptor` 在 Sa-Token 之上**叠加设备绑定**：
  - 校验 `X-PDK-Phone`、`X-PDK-Device-ID` 请求头（缺 → `40101`）
  - 手机号与账号不符 → `40102`
  - 设备号与库里 `activeDeviceId` 不符 → `40103 ERR_DEVICE_KICK_OUT`（单设备互踢核心）
  - 每次请求查库，账号被冻结 / 不存在会即时 `logout()` 并抛 `40100`

### 阶段 5 · 统一异常处理（`GlobalExceptionHandler`）
- [ ] `NotLoginException` → 返回 `CommonResult.failed(40100, "登录状态无效或已过期，请重新登录")`
- [ ] 前端据此 `code === 40100 || 40110` 清空本地会话并跳登录
- [ ] 设备类错误用 `BusinessException(40101/40102/40103, ...)`

### 阶段 6 · 本地联调自测（curl）
- [ ] 调通「注册 → 拿 token → 带 token 查 profile」整条链路：
```bash
# 1. 发短信验证码
curl -s -X POST http://localhost:8080/api/v1/client/auth/sms/send \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13800138000"}'

# 2. 注册（前置需先发短信拿到 smsCode）
curl -s -X POST http://localhost:8080/api/v1/client/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13800138000","smsCode":"889921","deviceId":"MAC-00-1B-44-11-3A-B7","clientVer":"1.0.0"}'
# ↑ 响应 data 里有 tokenName / tokenValue，记下来

# 3. 用拿到的 token 查配额（注意三个头都要带）
curl -s http://localhost:8080/api/v1/client/account/profile \
  -H 'satoken: <这里填 tokenValue>' \
  -H 'X-PDK-Phone: 13800138000' \
  -H 'X-PDK-Device-ID: MAC-00-1B-44-11-3A-B7'
```
- [ ] 故意不带 `X-PDK-Device-ID` 调第 3 步，确认返回 `40101`（验证设备校验生效）

### 阶段 7 · 上线前红线（务必记住）
- [ ] **当前会话存 JVM 内存，只能跑单实例**；多副本前必须先接 `sa-token-redis-jackson` + Redis（业务代码无需改）
- [ ] **不要引入 JWT**
- [ ] 新增「需要管理员权限」的接口：Controller 上加 `@RequirePermission("xxx")`，并在 `RolePermissions` 给对应角色配权限，`AdminAuthInterceptor` 自动拦截
- [ ] 新增认证类接口时，记得在 `WebMvcConfig.excludePathPatterns` 同步放行

---

## 二、前端（admin-vue3 管理后台）怎么调登录接口

管理后台的 token 注入是**自动**的：登录后把会话存入 `localStorage`，axios 请求拦截器每次自动把 `satoken` 头带上。

### 2.1 登录流程（真实代码路径）
1. 调 `POST /api/v1/admin/auth/login`，请求体 `{ username, password }`
2. 响应 `data`：`{ tokenName, tokenValue, id, username, displayName, role, permissions }`
3. 调 `setSession(...)`（来自 `src/auth.ts`）存入 `localStorage` 与响应式状态
4. 之后每个请求，`src/api.ts` 的**请求拦截器**自动注入：
```ts
api.interceptors.request.use((config) => {
  const session = authState.session;
  if (session) {
    config.headers[session.tokenName || 'satoken'] = session.tokenValue;  // ← 自动带 token
  }
  return config;
});
```
5. **响应拦截器**遇到 `code === 40100 || 40110` 自动 `clearSession()`（清空登录态，跳登录页）

### 2.2 可复制的登录页示例（Vue 3 + axios）
```vue
<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '@/api';
import { setSession } from '@/auth';

const router = useRouter();
const username = ref('');
const password = ref('');
const loading = ref(false);
const err = ref('');

async function doLogin() {
  loading.value = true; err.value = '';
  try {
    const { data } = await api.post('/api/v1/admin/auth/login', {
      username: username.value,
      password: password.value,
    });
    // data: { tokenName, tokenValue, id, username, displayName, role, permissions, ... }
    setSession({
      tokenName: data.tokenName,
      tokenValue: data.tokenValue,
      id: data.id,
      username: data.username,
      displayName: data.displayName,
      role: data.role,
      permissions: data.permissions,
    });
    router.push('/dashboard');           // 此时 token 已落库，后续请求自动带头
  } catch (e: any) {
    err.value = e.message || '登录失败';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <form @submit.prevent="doLogin">
    <input v-model="username" placeholder="用户名 / 手机号" />
    <input v-model="password" type="password" placeholder="密码" />
    <button :disabled="loading" type="submit">登录</button>
    <p v-if="err" style="color:red">{{ err }}</p>
  </form>
</template>
```
> 登录成功后**什么头都不用再手动加**——`api.ts` 的拦截器会从 `authState.session` 自动补 `satoken`。

---

## 三、客户端 SDK 怎么调登录接口

客户端登录走 **client 命名空间**，除了 `satoken` 头，**每条受保护请求还必须带 `X-PDK-Phone` + `X-PDK-Device-ID`**，否则后端返回 `40101`。

### 3.1 标准时序
```
1. POST /api/v1/client/auth/sms/send   { phone }                → 拿到短信验证码
2. POST /api/v1/client/auth/register   { phone, smsCode, deviceId, clientVer }
                                                  ↓ 响应 data
        { tokenName:"satoken", tokenValue:"<令牌>", status, expireTime, quota }
3. 本地只把 tokenName / tokenValue / phone / deviceId 存内存（建议别落盘）
4. 后续每条请求带三个头：
        satoken: <tokenValue>
        X-PDK-Phone: <phone>
        X-PDK-Device-ID: <deviceId>
5. GET /api/v1/client/account/profile  → 校验登录态 + 取剩余配额
        （401/403 说明过期或被顶号，需引导用户重新卡密核销）
```

### 3.2 C# (.NET) 示例
```csharp
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

// 1. 注册并拿 token
async Task<JsonElement> RegisterAsync(string phone, string smsCode, string deviceId)
{
    using var http = new HttpClient { BaseAddress = new Uri("http://localhost:8080") };
    var body = JsonSerializer.Serialize(new {
        phone, smsCode, deviceId, clientVer = "1.0.0"
    });
    var resp = await http.PostAsync("/api/v1/client/auth/register",
        new StringContent(body, Encoding.UTF8, "application/json"));
    var json = JsonDocument.Parse(await resp.Content.ReadAsStringAsync());
    return json.RootElement.GetProperty("data");   // { tokenName, tokenValue, ... }
}

// 2. 带三个头查配额
async Task<string> GetProfileAsync(string tokenValue, string phone, string deviceId)
{
    using var http = new HttpClient { BaseAddress = new Uri("http://localhost:8080") };
    http.DefaultRequestHeaders.Add("satoken", tokenValue);       // Sa-Token 会话令牌
    http.DefaultRequestHeaders.Add("X-PDK-Phone", phone);        // 拦截器强制校验
    http.DefaultRequestHeaders.Add("X-PDK-Device-ID", deviceId); // 拦截器强制校验
    var resp = await http.GetAsync("/api/v1/client/account/profile");
    return await resp.Content.ReadAsStringAsync();
}
```

### 3.3 Python 示例
```python
import requests

BASE = "http://localhost:8080"

def register(phone: str, sms_code: str, device_id: str) -> dict:
    r = requests.post(f"{BASE}/api/v1/client/auth/register", json={
        "phone": phone, "smsCode": sms_code,
        "deviceId": device_id, "clientVer": "1.0.0",
    })
    return r.json()["data"]   # { 'tokenName', 'tokenValue', 'status', 'quota', ... }

def get_profile(token_value: str, phone: str, device_id: str) -> dict:
    r = requests.get(f"{BASE}/api/v1/client/account/profile", headers={
        "satoken": token_value,           # Sa-Token 会话令牌
        "X-PDK-Phone": phone,             # 拦截器强制校验
        "X-PDK-Device-ID": device_id,     # 拦截器强制校验
    })
    return r.json()
```

### 3.4 Electron / Node.js 示例
```ts
const BASE = 'http://localhost:8080';

async function register(phone: string, smsCode: string, deviceId: string) {
  const res = await fetch(`${BASE}/api/v1/client/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone, smsCode, deviceId, clientVer: '1.0.0' }),
  });
  const json = await res.json();
  return json.data;   // { tokenName, tokenValue, status, quota, ... }
}

async function getProfile(tokenValue: string, phone: string, deviceId: string) {
  const res = await fetch(`${BASE}/api/v1/client/account/profile`, {
    headers: {
      satoken: tokenValue,        // Sa-Token 会话令牌
      'X-PDK-Phone': phone,       // 拦截器强制校验
      'X-PDK-Device-ID': deviceId,// 拦截器强制校验
    },
  });
  return res.json();
}
```

### 3.5 Java / Android 示例
```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;

String BASE = "http://localhost:8080";
HttpClient http = HttpClient.newHttpClient();

// 1. 注册
String regBody = """
    {"phone":"13800138000","smsCode":"889921",
     "deviceId":"MAC-00-1B-44-11-3A-B7","clientVer":"1.0.0"}""";
HttpRequest reg = HttpRequest.newBuilder()
    .uri(URI.create(BASE + "/api/v1/client/auth/register"))
    .header("Content-Type", "application/json")
    .POST(BodyPublishers.ofString(regBody)).build();
HttpResponse<String> regResp = http.send(reg, HttpResponse.BodyHandlers.ofString());
// 解析 regResp.body() 里的 data.tokenName / data.tokenValue

// 2. 带三个头查配额（伪代码，tokenValue/phone/deviceId 来自上面解析）
HttpRequest prof = HttpRequest.newBuilder()
    .uri(URI.create(BASE + "/api/v1/client/account/profile"))
    .header("satoken", tokenValue)          // Sa-Token 会话令牌
    .header("X-PDK-Phone", phone)           // 拦截器强制校验
    .header("X-PDK-Device-ID", deviceId)    // 拦截器强制校验
    .GET().build();
HttpResponse<String> profResp = http.send(prof, HttpResponse.BodyHandlers.ofString());
```

> **客户端铁律**：解密出的拼多多明文 Token（`pdd_tkn_...`）只在发起请求时的临时内存变量中存在，严禁写入 `Config.ini` / 注册表 / 数据库。

---

## 四、常见翻车点（精简版）

| 现象 | 原因 | 解决 |
|---|---|---|
| 所有请求 401 | 没带 `satoken` 头，或头名不是 `satoken` | 前端靠 `api.ts` 拦截器自动注入；客户端手动加 `satoken: tokenValue` |
| 客户端接口 40101 | 缺 `X-PDK-Phone` 或 `X-PDK-Device-ID` | 每条受保护请求必须三个头齐备 |
| 同账号两台电脑一台被踢 | `is-concurrent:false` + 设备校验，单设备在线 | 预期行为（`40103 ERR_DEVICE_KICK_OUT`） |
| admin token 在 client 接口报未登录 | 两套 `StpLogic` 互不通用 | 用哪套登录就用哪套校验，注入时 `@Qualifier` 别写错 |
| 多实例部署会话丢失 | 会话存 JVM 内存 | 接 `sa-token-redis-jackson` + Redis |
| 改了 `token-name` 全 401 | 前后端硬契约 | 前后端同步改，前端兜底 `'satoken'` 也要改 |

---

## 五、参考源码（建议对照阅读）
- `backend-springboot/src/main/java/com/pdk/config/SaTokenLogicConfig.java` — 双 `StpLogic`
- `backend-springboot/src/main/java/com/pdk/config/WebMvcConfig.java` — 路由守卫 + 白名单
- `backend-springboot/src/main/java/com/pdk/interceptor/DeviceSecurityInterceptor.java` — client 设备绑定
- `backend-springboot/src/main/java/com/pdk/interceptor/AdminAuthInterceptor.java` — admin 权限校验
- `backend-springboot/src/main/java/com/pdk/controller/ClientAuthController.java` — 客户端登录/注册
- `backend-springboot/src/main/java/com/pdk/controller/AdminAuthController.java` — 后台登录（含 `AdminLoginDTO`）
- `backend-springboot/src/main/java/com/pdk/common/exception/GlobalExceptionHandler.java` — 401 处理
- `admin-vue3/src/api.ts` / `admin-vue3/src/auth.ts` — 前端 token 注入与存储
- `src/components/ClientSdkIntegrationDoc.tsx` — 客户端接口 + 4 语言解密 SDK（原型文档）
- 技术说明全文：`docs/SA_TOKEN_GUIDE.md`
