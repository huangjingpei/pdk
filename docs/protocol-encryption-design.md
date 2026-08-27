# PDK 协议安全加密（全报文加密）设计方案

> 目标：在 HTTPS 之上再叠加一层**应用层报文加密**，使即便链路被抓包、被代理 MITM、被服务端明文日志留存，攻击者也无法直接读取或篡改业务报文。后端「协议安全加密」开关可动态切换策略。

---

## 1. 威胁模型（我们到底在防什么）

| 威胁 | HTTPS 是否已挡住 | 本方案额外防护 |
|---|---|---|
| 公网线路被动监听（无证书） | ✅ TLS 已挡 | 冗余防护 |
| 安装过根证书的中间人（Fiddler/Charles、企业 DLP、恶意软件） | ❌ TLS 被穿透 | ✅ 只有服务端私钥能解密 |
| 服务端把明文 body 写进日志/审计 | ❌ TLS 不管应用层 | ✅ 落库前即密文 |
| 重放已抓到的请求（重发拿结果） | ❌ | ✅ 时间戳+随机串防重放 |
| 客户端被逆向，密钥被提取 | ❌（设备已沦陷，超出范围） | 只能缓解，无法根除 |

**结论**：TLS 是主防线；本方案是**纵深防御**——解决「TLS 被绕过/被终结」和「明文落盘」两类真实风险。它不是用来对抗设备已 root/被注入木马的极端情况。

---

## 2. 现有方案的问题（为什么不够）

当前 `AesByteFlipUtils` 的做法：

```
密钥 = SHA256(ROOT_SALT + 时间窗) -> 取前16字节  (AES-128)
密文 = AES-128-GCM(明文) + 魔数 + 全字节逆序翻转 + Base64
```

问题：
1. **客户端能自己推导密钥**（ROOT_SALT 写死在客户端），任何拿到 App 的人都能算出密钥 → 这不是加密，是混淆。
2. **没有防重放**（无时间戳/随机串）。
3. **时间窗派生**意味着同一时刻所有客户端共享同一把密钥，无法区分来源。
4. 字节翻转只是反特征，对安全性无实质贡献。

本方案用**非对称加密**替代「客户端可推导的对称密钥」，从根本上解决第 1 点。

---

## 3. 总体方案：混合信封加密（Envelope Encryption）

采用业界标准的「非对称包装对称密钥」思路（等价于 JWE / ECIES 思想的轻量实现）：

```
┌─────────────────────────────────────────────────────────────┐
│ 客户端                                         服务端        │
│                                                            │
│ 1. 生成随机数据密钥 K (32字节, 仅本次请求)                  │
│ 2. AES-256-GCM(K, IV, 明文) ──► 密文 C                    │
│ 3. RSA-OAEP(ServerPub, K) ──► 包装密钥 W                  │
│ 4. 组装信封 JSON 发给服务端                                │
│                     ──────────── 信封 ───────────►         │
│                                               5. 用私钥解 W 得 K│
│                                               6. AES-256-GCM 解密 C │
│                                               7. 校验 ts/nonce 防重放│
│                                               8. 处理业务，用同一 K 加密响应│
│            ◄──────────── 响应信封(用 K 加密) ──────────── │
│ 9. 用本地 K 解密响应                                      │
└─────────────────────────────────────────────────────────────┘
```

### 3.1 信封格式（替代 HTTP body）

请求体与响应体都替换为如下 JSON（已落地实现，字段名与后端 `BodyCryptoService` 严格对齐）：

```json
{
  "kid": "v1",
  "enc": "Base64( RSA-OAEP(32字节 AES 密钥) )",
  "iv":  "Base64(12字节随机 IV)",
  "data":"Base64( AES-256-GCM 密文 + 16字节认证标签 )",
  "ts":  1730000000000,
  "rnd": "Base64(8字节随机串)"
}
```

字段说明（与实现一致）：

| 字段 | 请求信封（客户端→服务端） | 响应信封（服务端→客户端） |
|---|---|---|
| `kid` | 密钥版本，与服务端公钥一一对应 | 同请求 |
| `enc` | `RSA-OAEP(服务端公钥, 一次性 AES 密钥)` 的 Base64 | `Base64(原始 AES 会话密钥)`（服务端复用请求会话密钥，客户端无需再解 RSA） |
| `iv`  | 12 字节随机 GCM nonce | 12 字节随机 GCM nonce |
| `data`| `AES-256-GCM(K, iv, 明文) ‖ 16字节 Tag` 的 Base64 | 同左 |
| `ts`  | 毫秒时间戳，防重放 | 毫秒时间戳 |
| `rnd` | 8 字节随机串，防重放去重 | 随机串 |

- **响应侧零非对称开销**：服务端直接复用请求带来的会话密钥 `K` 加密响应，不再做 RSA；客户端本地持有 `K`，直接对称解密。
- 算法固定为 `RSA-2048-OAEP(SHA-256 + MGF1-SHA256)` + `AES-256-GCM(128bit Tag)`，字段中不再冗余 `alg`（如需切换国密，未来通过 `kid` 版本号路由）。

### 3.2 加密边界详解（重点：HTTP 报文到底加密了哪一部分）

> 这一节回答最关键的问题：**"所有 HTTP/HTTPS 的发送和接收"具体加密的是 payload 的哪一部分？** 结论先行——**只加密 HTTP 报文体（message body），不加密请求行、URL、query、header**。

#### 3.2.1 加密对象 = HTTP message body（且仅 body）

本方案加密的是 **HTTP 报文的主体（body / payload）**，也就是 `Content-Type: application/json` 所承载的那段 JSON 业务数据。原始业务 JSON 作为**明文**，被整体加密成一个"信封 JSON"，**用信封 JSON 替换掉原来的 body** 发出去。

- **请求侧**：原始请求 body（业务 JSON）→ 加密 → 信封 JSON 替换 body 发出。
- **响应侧**：原始响应 body（`CommonResult` 序列化 JSON）→ 加密 → 信封 JSON 替换 body 返回。

也就是说：**明文 = 原本要放进 HTTP body 的那段 JSON 文本**；**密文 = 信封 JSON（`kid/enc/iv/data/ts/rnd`）**。抓包者在 body 里只能看到密文信封，看不到业务字段。

#### 3.2.2 明确不加密的部分（仍以明文出现在链路上）

| HTTP 组成部分 | 是否加密 | 说明 |
|---|---|---|
| 请求行（`POST /api/v1/client/auth/login HTTP/1.1`） | ❌ 不加密 | method、URL path 明文。这是 HTTP 协议层，加密会破坏路由。 |
| **Query string**（`?page=1&size=20`） | ❌ 不加密 | 在 URL 里，随请求行明文传输。**敏感参数不要放 query**。 |
| **HTTP headers** | ❌ 不加密 | 含 `satoken`、`X-PDK-Phone`、`X-PDK-Device-ID`、`Content-Type`、`Cookie` 等，全部明文。 |
| **请求 body** | ✅ **加密** | 整段业务 JSON 被信封替换。 |
| 响应状态行（`HTTP/1.1 200 OK`） | ❌ 不加密 | 状态码明文。 |
| **响应 headers** | ❌ 不加密 | 仅额外设置 `Content-Type: application/json`。 |
| **响应 body** | ✅ **加密**（仅当请求已加密时） | 整段 `CommonResult` JSON 被信封替换。 |

> ⚠️ 设计取舍：header/query 不加密是**有意为之**——加密 URL/header 会破坏 HTTP 路由、CDN、网关、负载均衡与中间件。真正承载业务机密的是 body，把 body 加密到位即可堵住"抓包看业务数据"的口子。若 header/query 里有敏感信息（如手机号），应放到 body 里传，而不是放 header。

#### 3.2.3 生效路径范围

只对以下前缀的接口生效（后端 `ClientCryptoAdvice.isProtectedPath` 判定）：

- `/api/v1/client/**`（客户端鉴权、账号、卡密等用户侧接口）
- `/api/v1/dispatch/**`（调度网关：acquire-token、report-result）

**不生效**（始终明文）：`/api/v1/admin/**`（后台管理）、`/api/v1/card/activate`（开放核销）、`/actuator/**`（监控）、`/api/v1/client/config/public`（公钥下发，本身必须明文，否则客户端无法引导）。

#### 3.2.4 何时触发加密 / 解密

**请求侧解密**（后端 `beforeBodyRead`）触发条件三选一满足即解密：

1. 接口方法带 `@RequestBody`（即有 body 的 POST/PUT，纯 GET 无 body 不触发）；
2. 命中受保护路径前缀；
3. body 是信封 JSON（含 `enc/data/iv/kid` 四字段）。

- `optional` 模式：收到信封→解密；收到明文→直接透传（灰度期新旧客户端并存）。
- `force` 模式：收到明文 body→直接拒绝（42900）。
- `off` 模式：即使收到信封也按明文透传（不解密）。

**响应侧加密**（后端 `beforeBodyWrite`）触发条件**全部**满足才加密：

1. 命中受保护路径前缀；
2. ThreadLocal 记录的「本次请求以加密信封到达」= true；
3. 会话密钥存在。

→ 这意味着：**明文请求一定得到明文响应**；只有客户端主动加密了请求，响应才会加密。这正是 `optional` 灰度的关键——旧客户端零改动继续明文跑，新客户端自动密文跑。

#### 3.2.5 multipart 文件上传不受影响

`multipart/form-data` 走的是 `HttpMessageConverter` 的二进制流通道，不经过 `@RequestBody` 的文本 body 解析，因此**不在本方案加密范围内**。如需对上传内容保密，应由业务层先加密文件字节再上传。

#### 3.2.6 抓包前后对比（直观感受）

**加密前（明文，抓包可见业务字段）**：
```http
POST /api/v1/client/auth/login HTTP/1.1
satoken: xxx
Content-Type: application/json

{"phone":"13800138000","password":"Pdk12345678","deviceId":"CPP-AB12CD..."}
```

**加密后（抓包只能看到信封密文）**：
```http
POST /api/v1/client/auth/login HTTP/1.1
satoken: xxx
Content-Type: application/json

{"kid":"v1","enc":"j9k2...（RSA-OAEP包装的密钥）","iv":"8f3A...","data":"Hk7p...（AES-256-GCM密文+tag）","ts":1730000000000,"rnd":"Qz4x..."}
```

请求行 `POST /api/v1/client/auth/login`、header `satoken` 仍明文可见，但 body 里的手机号/密码/设备ID 已是密文——**攻击者能知道"你在登录"，但拿不到账号密码**。响应同理，`CommonResult` 里的 token、剩余次数等被加密。

#### 3.2.7 GET 请求与无 body 接口的处理（重要边界）

当前实现把"是否加密响应"绑定在「**本次请求是否以加密信封到达**」上。由于 `RequestBodyAdvice` 只对带 `@RequestBody` 的方法（即有 body 的 POST/PUT）生效，**纯 GET 接口（无 body）不会触发请求解密**，进而 `REQUEST_ENCRYPTED=false`，**响应也保持明文**。

受影响的 GET 接口（响应当前为明文）：

| 接口 | 说明 |
|---|---|
| `GET /api/v1/client/account/profile` | 返回剩余次数/到期时间/套餐名 |
| `GET /api/v1/client/account/usage?page=&size=` | 返回用量统计 |
| `GET /api/v1/client/resources/status` | 返回资源槽位状态 |
| `GET /api/v1/client/account/card` | 返回卡密列表 |

> ⚠️ 这意味着：**POST/PUT（带 body）的请求与响应都加密；GET 的响应当前是明文**。若这些 GET 响应里的数据（如剩余次数、套餐信息）也属敏感、需要防抓包，当前方案未覆盖。

**两种补救路径（择一，待确认）**：

- **A. 改造为按"会话级"判定**：客户端在任意一次加密 POST 后，把"已启用加密"记到会话/Token 维度；服务端对所有受保护路径的响应统一加密（不再依赖本次请求是否加密）。改动小，覆盖全。
- **B. 把 GET 也纳入**：客户端对 GET 也可发一个空明文 + 信封头，或改用 POST 携带加密参数；服务端放宽 `supports` 判定。改动较大。

> 默认实现采用"请求加密→响应才加密"的绑定模型（POST 全覆盖、GET 不覆盖），是性能与灰度安全性的折中。**是否需要补齐 GET 响应加密，请确认**——若确认，按方案 A 落地最快。


### 3.3 算法选型（可配置）

| 用途 | 默认 | 备选（国密合规） |
|---|---|---|
| 对称加密（body） | AES-256-GCM（AEAD，机密+完整） | SM4-GCM |
| 非对称（包装密钥） | RSA-2048 / RSA-4096-OAEP(SHA-256) | SM2 |
| 哈希/派生 | SHA-256 | SM3 |

> 之所以用 AES-256 而非沿用 AES-128：全量报文比单条 token 更敏感，256 位密钥代价极低、收益明显；若需与现有体系完全一致可保留 128。

### 3.4 为什么这样安全

- **机密性**：K 随机且一次性，攻击者没有服务端私钥无法解出 K，进而无法解 body。
- **完整性/防篡改**：AES-GCM 自带认证标签（GCM Tag），任何篡改都会导致解密失败。
- **前向隔离**：每次请求独立密钥，单点泄露不影响其他请求。

---

## 4. 后端「协议安全加密」开关（三态）

复用现有 `pdk_system_config` 表，配置项已接入 `ConfigKeys`：

| config_key | 取值 | 含义 |
|---|---|---|
| `security.encryption.mode` | `off` / `optional` / `force` | off=明文；optional=明文与密文都收（灰度，默认）；force=强制密文，明文拒绝 |

- 默认值 `optional`（灰度期）→ 全量验证后切 `force`。
- 客户端通过 `GET /api/v1/client/config/public`（**免鉴权**，已排除设备拦截器）拉取：`{ encryptionMode, publicKey(PEM), kid }`，据此决定是否加密、用哪把公钥。
- 后端零侵入接入：`ClientCryptoAdvice`（`@RestControllerAdvice` 同时实现 `RequestBodyAdvice` + `ResponseBodyAdvice`）对 `/api/v1/client` 与 `/api/v1/dispatch` 下的接口自动解密请求/加密响应，业务 Controller 完全无感。仅当「请求以加密信封到达」时响应才加密（`optional` 下明文请求→明文响应）。

**强制策略下的拒绝码**：明文请求命中 `force` 时，抛 `BusinessException(42900, "当前已强制启用协议加密，请使用支持加密的客户端")`。

加解密异常码：`42901` 密钥版本不匹配 / `42902` 时间戳过期 / `42903` 随机串重复 / `42904` 解密失败 / `42905` 加密失败。

---

## 5. 防重放（Replay）

- 每个信封带 `ts`（毫秒时间戳）与 `rnd`（8 字节随机串）。
- 服务端校验：`|now - ts|` 必须在 **±5 分钟** 窗口内；`rnd` 在窗口内不可重复（`ConcurrentHashMap` SETNX + 过期清理）。
- 多实例部署应将 `rnd` 去重替换为 Redis（`SET rnd NX EX 300`）。
- 客户端无需持久化 `rnd`（每次随机生成）。

---

## 6. 密钥管理

- **服务端**：持有 RSA/SM2 私钥（**绝不下发**），存于配置/密钥文件，**不入库、不进 git**。启动时加载，支持多个 `kid` 并存（旧私钥保留过渡期）。
- **客户端**：仅持有服务端**公钥**（PEM）。建议**公钥指纹钉扎**（客户端内置公钥指纹，若 `/client/config` 返回的公钥指纹不匹配则拒绝——防止有人替换配置端点做 MITM）。轮换时通过预先内置多指纹或管理端协同完成。
- **轮换**：新 `kid` 上线后，旧 `kid` 私钥保留 ≥ 过渡期（如 7 天），客户端周期性重拉 `/client/config` 获取新公钥。

---

## 7. 与现有 `AesByteFlipUtils` 的关系

- 新建 `BodyCryptoService` 实现信封加解密 + ts/nonce 校验。
- 旧的 `AesByteFlipUtils`（token 专用时间窗方案）建议**下线并统一**到新方案；若需保留过渡，可仅用于历史未升级客户端的兼容窗口，不得作为新客户端默认。
- 过渡期：服务端识别 `enc` 字段——无 `enc` 视为明文（按 mode 处理），`enc=pdk-v2` 走新流程。

---

## 8. 实施计划（落地状态）

**后端 ✅ 已完成**
1. ✅ `SecurityKeyService`：RSA-2048 密钥对生成/加载、`kid`、PEM 导出。
2. ✅ `BodyCryptoService`：信封 encrypt/decrypt、`ts/rnd` 防重放校验、内存去重缓存。
3. ✅ `ClientCryptoAdvice`（`RequestBodyAdvice` + `ResponseBodyAdvice`）：按 `mode` 自动解密请求、加密响应；`force` 下拒绝明文（42900）。ThreadLocal 在请求线程内共享「是否加密」与「会话密钥」。
4. ✅ `ClientConfigController` 新增 `GET /api/v1/client/config/public`：返回 `encryptionMode` + 公钥(PEM) + `kid`（免鉴权）。
5. ✅ 配置项 `security.encryption.mode` 接入 `ConfigKeys`，默认 `optional`。
6. ✅ 后端 4 个加解密类编译通过（`javac` EXIT=0）。

**客户端 ✅ 已完成（Python / C++ / 易语言）**
7. ✅ Python SDK：`pdk/crypto.py` 新增 `encrypt_envelope` / `decrypt_response` / `is_envelope` / `fetch_public_config`；`PdkApiClient(auto_envelope=True)` 自动拉公钥、加密请求、解密响应。已用 mock HTTP 服务端做真实往返验证通过。
8. ✅ C++ SDK：`pdk.hpp`/`pdk.cpp` 新增 `Client::enableEnvelope` / `isEnvelopeEnabled` / `refreshCryptoConfig`，`request()` 内自动加密请求体、保存会话密钥、解密响应信封；OpenSSL 实现 RSA-OAEP + AES-256-GCM。`g++ -fsyntax-only` 校验通过。
9. ✅ C ABI（易语言/C#/Delphi）：`pdk_capi.h`/`pdk_capi.cpp` 新增 `pdk_enable_envelope` / `pdk_is_envelope_enabled` / `pdk_refresh_crypto_config`。`g++ -fsyntax-only` 校验通过。
10. ✅ 易语言：`DLL命令声明.e.txt` 增补三条命令声明；`PDK调用示例.e.txt` 增补「创建实例后调用 `pdk_refresh_crypto_config` 一键启用」示例。

**测试 ⏳ 进行中**
11. ⏳ Python 端已用 mock 服务端验证往返；待联调真实后端（force/optional/off 三态、篡改检测、重放拒绝、密钥轮换）。

---

## 11. 客户端 SDK 落地（已实现）

### 11.1 Python

```python
from pdk.client import PdkApiClient

# auto_envelope=True：构造时自动 GET /api/v1/client/config/public 拉公钥并启用
client = PdkApiClient(base_url="https://api.example.com", auto_envelope=True)

# 之后所有带 body 的请求自动加密、响应自动解密，业务代码无感
r = client.request("POST", "/api/v1/client/auth/login",
                   json_body={"phone": "13800138000", "password": "xxx", "deviceId": "..."})
```

也可手动控制：`client.refresh_crypto_config()` 拉取并启用；`encrypt_envelope(plain, pub_pem, kid)` / `decrypt_response(env, session_key)` 见 `pdk/crypto.py`。

### 11.2 C++

```cpp
#include "pdk/pdk.hpp"
pdk::Client client(pdk::Config{ .baseUrl = "https://api.example.com" });

// 一键拉公钥并按服务端 mode 自动启用（mode=off 时不启用，保持明文）
client.refreshCryptoConfig();
// 或手动：client.enableEnvelope(pemPublicKey, "v1");

auto r = client.login("13800138000", "xxx");  // 请求自动加密、响应自动解密
```

### 11.3 易语言（调用 C DLL）

```e
h ＝ pdk_create ("https://api.example.com", "", "")
' 一行启用协议加密（拉公钥 + 按 mode 自动开关）
返回JSON ＝ pdk_refresh_crypto_config (h)
' 之后 pdk_login / pdk_acquire_token 等带 body 的请求自动加密，业务代码不变
返回JSON ＝ pdk_login (h, 手机, 密码)
```

### 11.4 启用后的效果

- 所有 `/api/v1/client/**` 与 `/api/v1/dispatch/**` 下带 body 的请求，body 被替换为信封 JSON；抓包只能看到 `enc/iv/data` 等密文。
- 响应在「请求已加密」时同样以信封返回，客户端用本次会话密钥自动解密。
- `optional` 模式下，未启用加密的旧客户端仍可明文访问，便于灰度；`force` 模式下旧客户端收到 42900 提示升级。

## 9. 业界对标

- **JWE**（RFC 7516，JSON Web Encryption）—— 信封加密的标准形态，本方案是其轻量变体。
- **ECIES**（SEC 1 v2）—— 椭圆曲线信封加密，可替代 RSA-OAEP（更短、更快）。
- **NIST SP 800-38D**（GCM）、**SP 800-56B**（密钥包装）。
- **国密**：GB/T 32918（SM2）、GB/T 32907（SM4）、GB/T 32905（SM3）。

---

## 10. 风险与权衡

- **公钥钉扎**会抬高密钥轮换复杂度，需过渡期 + 多指纹，已在第 6 节说明。
- **设备已沦陷**（木马/root）不在此方案防护范围，需在设备安全层面另行处理。
- **性能**：RSA-OAEP 仅加密 32 字节密钥，AES-GCM 对 body 对称加密，开销可忽略；建议服务端用连接级线程池。
- **运维**：私钥丢失 = 历史密文不可解密，需备份并隔离存储。

---

**落地状态**：后端 + Python/C++/易语言 三端客户端均已实现；Python 端已通过 mock 服务端往返验证，C++/C ABI 通过语法校验。待真实后端联调与三态/重放/轮换测试后切 `force` 全量启用。
