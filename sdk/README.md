# PDK 客户端多语言 SDK

为桌面采集客户端 / 机器人脚本 / 第三方集成开发者提供**统一、简单、状态可见**的接入 SDK。
当前优先完成 **C++** 与 **易语言**，其余语言按同一份契约逐步补齐。

---

## 1. 设计原则

1. **接入简单**：每种语言只暴露一个客户端对象 + 一组业务方法，开发者无需关心 HTTPS、加密、会话细节。
2. **状态可见**：通过「状态回调 / 事件回调 / 调试日志」把"现在是什么状态"实时通知开发者与客户。
   - C++ / Python：原生回调（lambda / 函数）。
   - 易语言：因 C 回调支持有限，提供**轮询接口** `pdk_get_last_state` / `pdk_get_last_state_detail`。
3. **安全合规**：HTTPS 传输 + `AES-128-GCM + 动态时间窗 + 字节翻转` 加密调度 Token，与后端 `AesByteFlipUtils` 完全对称。
4. **契约统一**：所有语言共享同一份后端契约（见第 3 节），行为一致、可互相验证。

---

## 2. 目录结构

```
sdk/
├── README.md              # 本文件（总览 + 统一契约）
├── cplusplus/             # ✅ C++ SDK（libcurl + OpenSSL + nlohmann/json）
│   ├── include/pdk/       #   公共头 pdk.hpp + C ABI 头 pdk_capi.h
│   ├── src/               #   实现 pdk.cpp + C ABI 实现 pdk_capi.cpp
│   ├── examples/          #   完整示例 main.cpp
│   ├── CMakeLists.txt     #   构建（静态库 + DLL + 示例）
│   ├── vcpkg.json
│   └── README.md
├── e/                     # ✅ 易语言 接入（调用 C++ 编译的 pdk_capi.dll）
│   ├── README.md          #   接入指南（含 UTF-8 编码注意事项）
│   ├── DLL命令声明.e.txt  #   DLL 命令声明（照抄到 易语言）
│   └── PDK调用示例.e.txt  #   完整调用示例
├── python/                # ✅ Python SDK（pip 可装，含回调与解密）
│   ├── pdk/               #   包：client.py / callbacks.py / crypto.py
│   ├── examples/demo.py
│   └── README.md
├── js/                    # 🚧 规划中（TypeScript / Node）
├── flutter/               # 🚧 规划中（Dart）
└── object-c/              # 🚧 规划中（iOS / macOS）
```

> **语言支持状态**：C++ / 易语言 / Python 已可用；JS / Flutter / Objective-C 为后续计划，接口将复用本文档契约与 `pdk_capi` 思路。

---

## 3. 统一后端契约（所有语言必须遵守）

### 3.1 基础地址
- 开发：`http://localhost:8080`
- 生产：`https://你的域名`（务必 HTTPS）

### 3.2 响应信封
所有接口返回统一结构，SDK 已封装为 `code / message / data / httpStatus`：
```json
{ "code": 200, "message": "操作成功", "data": { }, "timestamp": 1771120019283 }
```
- `code == 200`：业务成功；`code == 0`：网络层失败（`httpStatus == 0`）。
- `data` 可能是对象、数组或 `null`。

### 3.3 鉴权头
登录 / 注册成功后，响应 `data` 含 `tokenName` / `tokenValue` / `phone` / `deviceId`。
此后所有需登录的接口，SDK 自动附加：
```
tokenName: tokenValue          # 例如 satoken: xxxxx
X-PDK-Phone: 13800138000
X-PDK-Device-ID: CPP-xxxxxxxx  # 设备标识（方案A）
X-PDK-App-ID: 1                # 公开业务标识；PDD=1
```

### 3.4 接口清单

| 方法 | 接口 | 说明 |
| :-- | :-- | :-- |
| GET  | `/api/v1/client/business/by-app/{appId}` | 登录前业务名称、注册策略、状态和支持动作 |
| POST | `/api/v1/client/auth/sms/send` | 发短信验证码 |
| POST | `/api/v1/client/auth/register` | 注册（试用 1 天） |
| POST | `/api/v1/client/auth/login` | 登录（含设备校验） |
| POST | `/api/v1/client/auth/logout` | 注销 |
| POST | `/api/v1/client/auth/unbind-device` | 解绑电脑（清空服务端 deviceId，可换新电脑） |
| POST | `/api/v1/client/auth/change-password` | 改密 |
| POST | `/api/v1/card/activate` | 卡密核销（开放） |
| POST | `/api/v1/dispatch/acquire-token` | 申请加密短效 Token |
| POST | `/api/v1/dispatch/report-result` | 上报结果（SUCCESS 扣 1 次） |
| GET  | `/api/v1/client/account/profile` | 账号信息 |
| GET  | `/api/v1/client/account/usage` | 使用统计 |
| GET  | `/api/v1/client/resources/status` | 小号资源状态 |
| GET  | `/api/v1/client/account/card` | 卡密 / 资源 |

### 3.5 调度 Token 加密（acquire-token）
`acquire-token` 返回的 `data.encryptedPayload` 是密文，必须解密后才含拼多多 Session：
```
Key  = SHA256( RootSalt + "_" + (epochSeconds/60/10) )[:16]   // 128-bit
raw  = reverse( Base64Decode(encryptedPayload) )              // 全报文逆序
校验  raw[0:2] == 0x50 0x44 ('PD')
IV   = raw[2:14]                                             // 12 字节
CT   = raw[14:-16] ; Tag = raw[-16:]                         // AES-128-GCM
明文 = AESGCM_Decrypt(Key, IV, CT, Tag)                      // 容忍 ±1 时间窗
```
各语言 SDK 均已内置 `decrypt`，开发者无需自己实现。

### 3.6 设备 ID（方案A：本地缓存 + 服务端权威）
- 优先环境变量 `PDK_DEVICE_ID` → 本地缓存 `~/.pdk_client/device_id.json` → 本机指纹（MachineGuid / machine-id）SHA-256 生成并落盘；
- 注册 / 登录成功后，用服务端返回的 `deviceId` 覆盖本地缓存，保证同设备稳定、跨设备不一致。

### 3.7 错误码

| code | 含义 | 客户端建议 |
| :-- | :-- | :-- |
| 200 | 成功 | — |
| 40001 | 卡密不存在 | 提示卡密错误 |
| 40002 | 卡密已使用 | 提示换卡 |
| 40004 | 并发冲突 | 重试 |
| 40010 | 已领过体验 | 改走 login |
| 40101 | 缺鉴权头 | 重新登录 |
| 40103 | 设备互踢 | 清空会话，提示"其他设备登录" |
| 40301 | 订阅到期 | 引导核销新卡 |
| 40302 | 配额耗尽 | 提示充值 |
| 50301 | 槽位繁忙 | 稍后重试 |
| 0    | 网络失败 | 检查网络 / 服务地址 |

### 3.8 跨语言状态 / 事件枚举（整数值固定，便于 易语言 轮询）

| 值 | State | 值 | Event |
| :-- | :-- | :-- | :-- |
| 0 | Uninitialized | — | — |
| 1 | Ready | 100 | RequestSent |
| 2 | SmsSent | 101 | ResponseReceived |
| 3 | Registering | 102 | DecryptSucceeded |
| 4 | Registered | 103 | DecryptFailed |
| 5 | LoggingIn | 104 | QuotaLow |
| 6 | LoggedIn | 105 | SubscriptionExpired |
| 7 | LoggingOut | 106 | QuotaExhausted |
| 8 | LoggedOut | 107 | NoAvailableToken |
| 9 | DeviceUnbound | | |
| 10 | TokenAcquiring | | |
| 11 | TokenAcquired | | |
| 12 | TokenFailed | | |
| 13 | ResultReporting | | |
| 14 | Kicked | | |
| 15 | Error | | |

---

## 4. 如何编译共享 DLL（供 易语言 等调用）

```bash
cd sdk/cplusplus
vcpkg install curl openssl nlohmann-json
cmake -B build -S . -DCMAKE_TOOLCHAIN_FILE=<vcpkg>/scripts/buildsystems/vcpkg.cmake
cmake --build build
# 产物：build/pdk_capi.dll（拷贝到 易语言 exe 同目录）
```
详细见 `cplusplus/README.md`。

---

## 5. 推荐接入顺序

1. 选语言目录，读其子目录 README；
2. 跑通「注册 / 登录 → 申请 Token → 解密 → 上报 → 查配额」最小闭环；
3. 接入状态回调（或 易语言 轮询）刷新界面；
4. 处理 `40103` 设备互踢与 `40302` 配额耗尽等典型错误。
