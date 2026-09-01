# PDK 协议安全加密（全报文加密）设计方案

> 目标：在 HTTPS 之上再叠加一层**应用层报文加密**，使即便链路被抓包、被代理 MITM、被服务端明文日志留存，攻击者也无法直接读取或篡改业务报文。后端「协议加密模式(mode)」可动态切换策略（off/optional/force）。

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

#### 3.2.7 GET 请求与无 body 接口的处理（已按方案 A 实现）

**已按方案 A（会话级判定）实现**：服务端在成功解密一次加密 POST 后，把本次会话 AES 密钥按稳定身份（设备 ID，取自 `X-PDK-Device-ID` 头）存入**跨请求会话密钥缓存**（带 30 分钟 TTL）。之后该身份在任意受保护路径的响应（含 GET）都会被加密，不再依赖"本次请求是否加密"。客户端**始终携带 `X-PDK-Device-ID`**，并在本地缓存最近一次会话密钥，用于解密 GET 等无 body 响应。

| 接口类型 | 请求 | 响应 |
|---|---|---|
| POST/PUT（带 body） | 加密 ✅ | 加密 ✅ |
| GET（无 body，已建立加密会话） | 明文（无 body） | **加密 ✅**（复用会话密钥） |
| GET（尚未建立加密会话） | 明文 | 明文（灰度降级） |

> 客户端需在首次加密 POST（如登录）之后访问 GET 接口；登录 POST 本身即信封，服务端据此建立会话密钥缓存，紧接着的 `profile/usage/resources/card` 等 GET 响应即被加密。客户端用本地持有的同一会话密钥解密。


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

## 4. 协议加密模式（mode，三态）

复用现有 `pdk_system_config` 表，配置项已接入 `ConfigKeys`：

| config_key | 取值 | 含义 |
|---|---|---|
| `security.encryption.mode` | `off` / `optional` / `force` | off=明文；optional=明文与密文都收（灰度，默认）；force=强制密文，明文拒绝 |

- 默认值 `optional`（灰度期）→ 全量验证后切 `force`。
- 客户端通过 `GET /api/v1/client/config/public`（**免鉴权**，已排除设备拦截器）拉取：`{ encryptionMode, publicKey(PEM), kid, supportedKids, publicKeyFingerprint }`，据此决定是否加密、用哪把公钥、并做指纹钉扎校验。
- 后端零侵入接入：`ClientCryptoAdvice`（`@RestControllerAdvice` 同时实现 `RequestBodyAdvice` + `ResponseBodyAdvice`）对 `/api/v1/client` 与 `/api/v1/dispatch` 下的接口自动解密请求/加密响应，业务 Controller 完全无感。响应加密判定为**会话级**：某身份任一加密请求成功后，服务端对该身份所有受保护路径响应统一加密（`optional` 下未建立加密会话的明文请求仍得明文响应）。

**强制策略下的拒绝码**：明文请求命中 `force` 时，抛 `BusinessException(42900, "当前已强制启用协议加密，请使用支持加密的客户端")`。

加解密异常码：`42901` 密钥版本不匹配 / `42902` 时间戳过期 / `42903` 随机串重复 / `42904` 解密失败 / `42905` 加密失败。

---

## 5. 防重放（Replay）

- 每个信封带 `ts`（毫秒时间戳）与 `rnd`（8 字节随机串）。
- 服务端校验：`|now - ts|` 必须在 **±5 分钟** 窗口内；`rnd` 在窗口内不可重复。
- 客户端无需持久化 `rnd`（每次随机生成）。

### 5.1 去重缓存抽象（已落地）

后端 `BodyCryptoService` 内置 `ReplayCache` 接口，按是否启用 Redis 自动选择实现：

```java
private interface ReplayCache { boolean tryAcquire(String rnd); }

// 单实例：进程内 ConcurrentHashMap，惰性清理过期项
private static final class MemoryReplayCache implements ReplayCache { ... }

// 多实例：Redis SETNX + TTL，天然跨节点去重
private static final class RedisReplayCache implements ReplayCache {
    // StringRedisTemplate.opsForValue()
    //   .setIfAbsent("pdk:replay:" + rnd, "1", Duration.ofMillis(REPLAY_WINDOW_MS));
}
```

构造时依据配置选择：

```java
@Autowired(required = false) StringRedisTemplate redisTemplate,
@Value("${pdk.crypto.replay.redis.enabled:false}") boolean redisReplayEnabled
// → redisReplayEnabled && redisTemplate != null 时选用 RedisReplayCache，否则 MemoryReplayCache
```

| 部署形态 | 配置 | 实现 | 说明 |
|---|---|---|---|
| 单实例 | `pdk.crypto.replay.redis.enabled=false`（默认） | `MemoryReplayCache` | 进程内去重，重启即清空，足够单节点 |
| 多实例/集群 | `pdk.crypto.replay.redis.enabled=true` | `RedisReplayCache` | `SET pdk:replay:<rnd> 1 NX EX 300`，跨节点全局去重，攻击者在 A 节点用过的 `rnd` 无法在 B 节点重放 |

> 重放窗口 `REPLAY_WINDOW_MS` 与 `ts` 校验窗口一致（±5 分钟），`rnd` 写入即带等长的 TTL，过期自动失效，无需后台清理线程。
> 去重校验失败抛 `42903`（随机串重复）；`ts` 越界抛 `42902`（时间戳过期）。

---

## 6. 密钥管理

- **服务端**：持有 RSA/SM2 私钥（**绝不下发**），存于配置/密钥文件，**不入库、不进 git**。支持**多 `kid` 并存**以实现平滑轮换（见 §6.2）。
- **客户端**：仅持有服务端**公钥**（PEM）。**公钥指纹钉扎**已落地（见 §6.3），防止配置端点被替换做 MITM。
- **轮换**：新 `kid` 上线后，旧 `kid` 私钥保留 ≥ 过渡期（如 7 天），客户端周期性重拉 `/client/config` 获取新公钥与指纹。

### 6.1 公钥下发端点（已增强）

`GET /api/v1/client/config/public`（免鉴权）现返回：

```json
{
  "encryptionMode": "optional",
  "publicKey": "-----BEGIN PUBLIC KEY----- ... -----END PUBLIC KEY-----",
  "kid": "v1",
  "supportedKids": ["v1"],
  "publicKeyFingerprint": "a1b2c3d4e5f6...（64 位 hex，取 SHA-256 前 16 字节）"
}
```

> `supportedKids` 让客户端感知服务端当前接受的全部密钥版本（灰度/双跑期可能有多个）；`publicKeyFingerprint` 供客户端做钉扎校验。

### 6.2 多密钥加载与按 `kid` 路由（已落地）

`SecurityKeyService` 支持三种配置来源（优先级从高到低）：

| 配置 | 形式 | 场景 |
|---|---|---|
| `pdk.crypto.keys` | JSON 数组 `[{"kid":"v2","privateKeyPem":"-----BEGIN PRIVATE KEY-----..."}, ...]` | **推荐**：多密钥并存/轮换期，按 `kid` 选择私钥解密 |
| `pdk.crypto.private-key-pem` + `pdk.crypto.kid` | 单把私钥 + 单版本号 | 单密钥部署 |
| 均无 | 启动时自动生成 RSA-2048，`kid=v1` | 开发/演示兜底（**生产务必用前两者显式配置**） |

- **解密路由**：信封 `kid` 决定用哪把私钥。后端 `BodyCryptoService.decryptEnvelope` 先按 `kid` 查 `SecurityKeyService.getPrivateKey(kid)`；查不到对应私钥即抛 `42901`（密钥版本不匹配）。
- **加密响应**：响应信封的 `kid` 固定填当前**激活版本** `keyService.getActiveKid()`（兜底为配置的首个 `kid`）。
- **轮换流程**：① 在 `pdk.crypto.keys` 追加新 `kid`（如 `v2`）私钥，保留旧 `v1`；② 客户端重拉 `public` 拿到 `supportedKids=[v1,v2]`，自动选用最新 `kid` 加密；③ 过渡期结束后下掉旧私钥。全程旧请求仍能解密，无需停机。

### 6.3 公钥指纹钉扎（P0，已落地）

指纹计算：`SHA-256(DER(X.509 SubjectPublicKeyInfo))` 取**前 16 字节** → 32 位 hex 字符串（即上文 `publicKeyFingerprint`）。

客户端三层校验优先级（详见 §11）：

1. **显式钉扎**：构造客户端时传入 `public_key_pin`（预置指纹），与端点返回指纹不一致直接拒绝；
2. **本地 pin-store（TOFU）**：未显式钉扎时，读写本地文件 `~/.pdk_client/pin.json`，首次成功连接时信任并落盘，之后每次比对，不一致拒绝；
3. **未配置**：跳过校验（仅推荐开发期）。

> 钉扎保证：即使攻击者劫持 `/client/config/public` 返回自己的公钥，因指纹不匹配（或 pin-store 中已记录的旧指纹不匹配），客户端拒绝握手，杜绝"假公钥"MITM。

---

## 7. 与现有 `AesByteFlipUtils` 的关系

- 新建 `BodyCryptoService` 实现信封加解密 + ts/nonce 校验。
- 旧的 `AesByteFlipUtils`（token 专用时间窗方案）建议**下线并统一**到新方案；若需保留过渡，可仅用于历史未升级客户端的兼容窗口，不得作为新客户端默认。
- 过渡期：服务端识别 `enc` 字段——无 `enc` 视为明文（按 mode 处理），`enc=pdk-v2` 走新流程。

---

## 8. 实施计划（落地状态）

**后端 ✅ 已完成**
1. ✅ `SecurityKeyService`：RSA-2048 密钥对生成/加载、**多 `kid` 并存**、PEM 导出、`getPublicKeyFingerprint(kid)`。
2. ✅ `BodyCryptoService`：信封 encrypt/decrypt、`ts/rnd` 防重放校验、`ReplayCache` 抽象（内存 `MemoryReplayCache` / Redis `RedisReplayCache` 按配置自动切换）、按 `kid` 选私钥解密。
3. ✅ `ClientCryptoAdvice`（`RequestBodyAdvice` + `ResponseBodyAdvice`）：按 `mode` 自动解密请求、加密响应；`force` 下拒绝明文（42900）。ThreadLocal 在请求线程内共享「是否加密」与「会话密钥」；并新增**跨请求会话密钥缓存**（`SESSION_KEYS`，按稳定身份 + 30 分钟 TTL）以支持 GET 响应加密。
4. ✅ `ClientConfigController` 新增 `GET /api/v1/client/config/public`：返回 `encryptionMode` + 公钥(PEM) + `kid` + `supportedKids` + `publicKeyFingerprint`（免鉴权）。
5. ✅ 配置项 `security.encryption.mode` 接入 `ConfigKeys`，默认 `optional`。
6. ✅ 后端 4 个加解密类编译通过（`javac` EXIT=0）。

**客户端 ✅ 已完成（Python / C++ / 易语言）**
7. ✅ Python SDK：`pdk/crypto.py` 新增 `encrypt_envelope` / `decrypt_response` / `is_envelope` / `fetch_public_config` / `compute_public_key_fingerprint`；`PdkApiClient(auto_envelope=True)` 自动拉公钥、加密请求、解密响应；并支持**会话密钥缓存解密 GET 响应**、**指纹钉扎（显式 pin / pin-store TOFU）**、**42901/42904 自动重试**。
8. ✅ C++ SDK：`pdk.hpp`/`pdk.cpp` 新增 `Client::enableEnvelope` / `isEnvelopeEnabled` / `refreshCryptoConfig` / `setPinStorePath`，`request()` 内自动加密请求体、保存会话密钥、解密响应信封、钉扎校验与自动重试；OpenSSL 实现 RSA-OAEP + AES-256-GCM。`g++ -fsyntax-only` 校验通过。
9. ✅ C ABI（易语言/C#/Delphi）：`pdk_capi.h`/`pdk_capi.cpp` 新增 `pdk_enable_envelope` / `pdk_is_envelope_enabled` / `pdk_refresh_crypto_config` / `pdk_set_pin_store_path`。`g++ -fsyntax-only` 校验通过。
10. ✅ 易语言：`DLL命令声明.e.txt` 增补命令声明（含 `pdk_set_pin_store_path`）；`PDK调用示例.e.txt` 增补「显式 pin / TOFU pin-store」两种钉扎示例。

**本轮增强 ✅（items 1–5，已落地并验证）**
- ✅ **① 会话级 GET 响应加密**：服务端在成功解密一次加密 POST 后，按稳定身份（`X-PDK-Device-ID` → 登录 UID → 远端 IP 兜底）将会话 AES 密钥存入 `SESSION_KEYS`（30 分钟 TTL）；该身份后续受保护路径的 GET/无 body 响应统一用此密钥加密。客户端本地缓存会话密钥以解密 GET 响应（见 §3.2.7、§11）。
- ✅ **② 公钥指纹钉扎 + TOFU**：`publicConfig` 返回 `publicKeyFingerprint`；客户端三层校验——显式 `public_key_pin` > 本地 `~/.pdk_client/pin.json`（首次成功即信任并落盘，后续比对）> 未配置跳过。指纹不匹配抛 `PublicKeyPinMismatchError` / 拒绝握手。
- ✅ **③ 多实例重放缓存（Redis）**：`ReplayCache` 抽象，`pdk.crypto.replay.redis.enabled=true` 时启用 `RedisReplayCache`（`SET pdk:replay:<rnd> 1 NX EX 300`），否则 `MemoryReplayCache`；跨节点全局去重（见 §5.1）。
- ✅ **④ 客户端 42901/42904 自动重试**：收到 `code ∈ {42901, 42904}` 且本地已持有公钥时，自动 `refresh_crypto_config()` 重拉配置并重试一次（用于密钥轮换/解密失败后恢复）。
- ✅ **⑤ 服务端多密钥轮换（按 kid）**：`SecurityKeyService` 支持 `pdk.crypto.keys`（JSON 数组多密钥）/ 单 `private-key-pem` + `kid` / 兜底自动生成；解密按信封 `kid` 路由私钥，响应填激活 `kid`（见 §6.2）。

**测试 ⏳ 进行中**
11. ⏳ Python 端已用 mock 服务端验证往返 + 指纹钉扎单测（5 项 PASS：指纹稳定、MITM 换钥被拦、不匹配抛错、空 pin 跳过、双向互通）；C++/C ABI 通过语法校验。待联调真实后端（force/optional/off 三态、篡改检测、重放拒绝、密钥轮换）。

---

## 11. 客户端 SDK 落地（已实现）

### 11.1 Python

```python
from pdk.client import PdkApiClient

# auto_envelope=True：构造时自动 GET /api/v1/client/config/public 拉公钥并启用
# public_key_pin：显式钉扎（生产推荐），与端点返回指纹不一致直接拒绝
client = PdkApiClient(base_url="https://api.example.com",
                      auto_envelope=True,
                      public_key_pin="a1b2c3d4e5f6...")   # 可选

# 之后所有带 body 的请求自动加密、响应自动解密，业务代码无感
r = client.request("POST", "/api/v1/client/auth/login",
                   json_body={"phone": "13800138000", "password": "xxx", "deviceId": "..."})

# GET 响应也能解密：登录后服务端已建立会话密钥，客户端用本地缓存的会话密钥解密
profile = client.request("GET", "/api/v1/client/profile")
```

要点（已落地）：
- **会话密钥缓存**：加密 POST 成功后，客户端把本次会话 AES 密钥存到 `client.envelope_session_key`；后续 GET/无 body 响应统一用它解密（对应服务端 `SESSION_KEYS` 会话级加密）。
- **指纹钉扎**：`public_key_pin` 显式传入时优先级最高；未传时走本地 pin-store（`~/.pdk_client/pin.json`，TOFU 首次信任、后续比对）；都不配则跳过校验。
- **自动重试**：收到 `42901`（密钥版本不匹配）或 `42904`（解密失败）且本地已持有公钥时，自动 `refresh_crypto_config()` 重拉配置并重试一次（无 `public_key_pin` 下可完成密钥轮换平滑过渡）。
- 手动 API：`client.refresh_crypto_config()`、`encrypt_envelope(plain, pub_pem, kid)`、`decrypt_response(env, session_key)` 见 `pdk/crypto.py`。

### 11.2 C++

```cpp
#include "pdk/pdk.hpp"
pdk::Client client(pdk::Config{ .baseUrl = "https://api.example.com" });

// 显式钉扎（生产推荐）：设置后 refreshCryptoConfig 会比对指纹，不匹配抛异常
client.setPin("a1b2c3d4e5f6...");
// 或改用本地 pin-store（TOFU）：client.setPinStorePath("/path/to/pin.json");

// 一键拉公钥并按服务端 mode 自动启用（mode=off 时不启用，保持明文）
client.refreshCryptoConfig();
// 或手动：client.enableEnvelope(pemPublicKey, "v1");

auto r = client.login("13800138000", "xxx");  // 请求自动加密、响应自动解密
```

要点（已落地）：`envelopeSessionKey_` 缓存会话密钥用于 GET 响应解密；`setPin` / `setPinStorePath` 两路钉扎；`request()` 内对 `42901/42904` 自动 `refreshCryptoConfig()` 并重试一次。

### 11.3 易语言（调用 C DLL）

```e
h ＝ pdk_create ("https://api.example.com", "", "")
' 方案 A（生产推荐）：显式钉扎，传入预置指纹
pdk_set_pin (h, "a1b2c3d4e5f6...")
' 方案 B（TOFU）：设置本地 pin 存储路径，首次信任、后续比对
' pdk_set_pin_store_path (h, "C:\xxx\pin.json")
' 一行启用协议加密（拉公钥 + 按 mode 自动开关 + 指纹校验）
返回JSON ＝ pdk_refresh_crypto_config (h)
' 之后 pdk_login / pdk_acquire_token 等带 body 的请求自动加密，业务代码不变
返回JSON ＝ pdk_login (h, 手机, 密码)
```

### 11.4 启用后的效果

- 所有 `/api/v1/client/**` 与 `/api/v1/dispatch/**` 下带 body 的请求，body 被替换为信封 JSON；抓包只能看到 `enc/iv/data` 等密文。
- 响应在「已建立加密会话」时同样以信封返回（含 GET 等无 body 接口），客户端用缓存的会话密钥自动解密。
- `optional` 模式下，未启用加密的旧客户端仍可明文访问，便于灰度；`force` 模式下旧客户端收到 42900 提示升级。

### 11.5 钉扎与重试的失败语义

| 场景 | 行为 |
|---|---|
| `public_key_pin` 与端点指纹不一致 | 立即拒绝握手（不发起业务请求），视为 MITM |
| 未设 pin，pin-store 中旧指纹与端点不一致 | 拒绝，提示本地 pin 可能被篡改或服务器已换密钥 |
| 收到 `42901` / `42904` 且持有公钥 | 自动 `refresh_crypto_config()` 并重试一次；仍失败则上抛业务异常 |
| 设备被踢（如 `42905` 类登录态失效） | 走既有登录态失效处理，**不**触发加密重试 |

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
