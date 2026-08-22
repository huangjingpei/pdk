# PDK 客户端 SDK —— C++

面向桌面采集客户端 / 机器人脚本 / 第三方集成开发者的 **C++ 接入 SDK**。封装了注册、登录、短信、卡密核销、短效 Token 调度、配额查询等全部业务，并通过 **状态 / 事件 / 调试日志三类回调** 把"现在是什么状态"实时告诉开发者与客户。

- 传输：**libcurl**（原生支持 HTTPS）
- 加密解密：**OpenSSL**（AES-128-GCM + SHA-256，与后端 `AesByteFlipUtils` 完全对称）
- JSON：**nlohmann/json**（header-only）

> 本 SDK 与 `client-pyqt/pdk_client.py` 业务逻辑一致，可作为任意 C++ 客户端（Windows / Linux / macOS）的底层接入层。

---

## 1. 目录结构

```
cplusplus/
├── include/pdk/
│   ├── pdk.hpp         # C++ 公共接口（推荐 C++ 开发者使用）
│   └── pdk_capi.h      # C ABI 头（编译为 DLL，供 易语言 / C# / Delphi / Go 调用）
├── src/
│   ├── pdk.cpp         # 业务实现
│   └── pdk_capi.cpp    # C ABI 实现（导出 DLL 符号）
├── examples/
│   └── main.cpp        # 完整调用示例（含回调）
├── CMakeLists.txt
├── vcpkg.json
└── README.md
```

---

## 2. 编译

### 方式一：vcpkg（推荐，跨平台一致）

```bash
# 1. 安装依赖
vcpkg install curl openssl nlohmann-json

# 2. 配置并构建
cmake -B build -S . -DCMAKE_TOOLCHAIN_FILE=<vcpkg>/scripts/buildsystems/vcpkg.cmake
cmake --build build
```

产物：
- `libpdk.a`（静态库，C++ 开发者链接）
- `pdk_capi.dll / libpdk_capi.so`（动态库，供 易语言 等调用）
- `pdk_example`（示例可执行文件）

### 方式二：系统包管理器

- **Ubuntu/Debian**：`sudo apt install libcurl4-openssl-dev libssl-dev nlohmann-json3-dev`
- **macOS**：`brew install curl openssl nlohmann-json`
- **Windows**：用 vcpkg 或预编译的 curl/openssl 开发包，并在 CMake 中指定 `CURL_DIR` / `OPENSSL_ROOT_DIR`。

> 注意：编译 DLL 时 `pdk_capi.cpp` 会自动定义 `PDK_CAPI_EXPORTS`，无需手动加宏。

---

## 3. 快速开始（C++）

```cpp
#include "pdk/pdk.hpp"
using namespace pdk;

Config cfg;
cfg.baseUrl = "http://localhost:8080";   // 生产替换为 https 域名
cfg.enableDebugLog = true;

Client client(cfg);

// 状态回调：界面据此刷新“未登录 / 登录中 / 已登录 / 被踢”等
client.setStateCallback([](State s, const std::string& d){
    std::cout << Client::describeState(s) << " | " << d << "\n";
});
// 事件回调：解密成功 / 配额耗尽等更细粒度事件
client.setEventCallback([](Event e, const std::string& m){ /* ... */ });
// 调试日志：每条 HTTP 的 [请求] / [响应] / [期待]（请求什么 / 返回什么 / 期待什么）
client.setLogCallback([](const std::string& line){ std::cout << line << "\n"; });

// 1) 注册
auto r = client.registerAccount("13800138000", "Pdk12345678", "123456");
if (!r.ok()) { /* 40010 已领过体验 -> 改 login */ }

// 2) 申请并解密短效 Token（核心调度）
std::string plain;
r = client.acquireTokenDecrypted("GOODS_COLLECT", "881920391204", plain);
if (r.ok()) {
    std::string traceId = r.dataString("leaseTraceId");
    // 用 plain 里的拼多多 Session 向官方发包 ...
    client.reportResult(traceId, "SUCCESS", 1200, "");  // SUCCESS 扣 1 次
}
```

完整可运行示例见 `examples/main.cpp`。

---

## 4. API 速查

| 方法 | 对应后端接口 | 说明 |
| :-- | :-- | :-- |
| `sendSms(phone, purpose)` | `POST /client/auth/sms/send` | 发送验证码（默认 REGISTER） |
| `registerAccount(phone, pwd, sms, invite?)` | `POST /client/auth/register` | 注册并登录（试用 1 天） |
| `login(phone, pwd)` | `POST /client/auth/login` | 登录（含设备校验） |
| `logout()` | `POST /client/auth/logout` | 注销 |
| `unbindDevice()` | `POST /client/auth/unbind-device` | 解绑会话，保留账号 deviceId |
| `changePassword(phone, old, new)` | `POST /client/auth/change-password` | 改密 |
| `activateCard(key, phone, channel?, amount?)` | `POST /card/activate` | 卡密核销（开放接口） |
| `acquireToken(action, goodsId)` | `POST /dispatch/acquire-token` | 申请加密 Token（返回 VO） |
| `decryptToken(encryptedPayload)` | — | 解密 Token 明文（AES-128-GCM） |
| `acquireTokenDecrypted(...)` | — | 申请 + 解密一步到位 |
| `reportResult(traceId, status, ms?, err?)` | `POST /dispatch/report-result` | 上报结果（SUCCESS 扣 1 次） |
| `profile()` / `usage()` / `resourceStatus()` / `cardList()` | `GET /client/account/*` | 账号/配额/小号查询 |

所有方法返回 `ApiResponse{ code, message, dataJson, httpStatus }`：
- `code == 200` 表示业务成功；
- `code == 0` 表示网络层失败（`httpStatus == 0`，`message` 含原因）；
- 用 `r.dataString("key")` / `r.dataLong("key")` / `r.dataAt("a/b/c")` 读取 `data` 字段。

---

## 5. 状态 / 事件回调（关键）

要求"事件以及状态能够通过回调方式告诉开发者"，SDK 提供三类回调：

### 5.1 State（连接状态，整数枚举）

`pdk::State` 的底层整数值（C API / 易语言 轮询时直接对比）：

| 值 | 状态 | 含义 |
| :-- | :-- | :-- |
| 0 | Uninitialized | 未初始化 |
| 1 | Ready | 就绪（未登录） |
| 2 | SmsSent | 验证码已发送 |
| 3 | Registering | 注册中 |
| 4 | Registered | 注册成功（已登录） |
| 5 | LoggingIn | 登录中 |
| 6 | LoggedIn | 登录成功 |
| 7 | LoggingOut | 注销中 |
| 8 | LoggedOut | 已注销 |
| 9 | DeviceUnbound | 设备已解绑 |
| 10 | TokenAcquiring | 申请 Token 中 |
| 11 | TokenAcquired | 已取得并解密 Token |
| 12 | TokenFailed | 申请 Token 失败 |
| 13 | ResultReporting | 上报结果中 |
| 14 | Kicked | 被其他设备踢下线（40103） |
| 15 | Error | 通用错误 |

### 5.2 Event（细粒度事件）

`RequestSent / ResponseReceived / DecryptSucceeded / DecryptFailed / QuotaLow / SubscriptionExpired / QuotaExhausted / NoAvailableToken`。

### 5.3 调试日志（LogCallback）

每条 HTTP 都会回调一行，格式：`[请求] ...` / `[响应] ...` / `[期待] ...`，便于客户端调试（对应 `client-pyqt` 的接口调试面板）。

> **控制台中文乱码**：Windows 控制台默认代码页是 GBK(936)，直接 `std::cout` UTF-8 字符串会乱码。请在 `main` 入口调用一次 `pdk::enable_utf8_console();`（内部只做 `SetConsoleOutputCP(CP_UTF8)` + `SetConsoleCP(CP_UTF8)`，不改变 `stdout` 模式，因此 `std::cout` 仍可安全输出 UTF-8 字节）。示例 `examples/main.cpp` 已默认调用。Linux/macOS 下该函数为空操作。

---

## 6. 设备 ID（方案A：本地缓存 + 服务端权威）

与 `client-pyqt` 完全一致：
1. 优先读取环境变量 `PDK_DEVICE_ID`；
2. 其次读取本地缓存 `~/.pdk_client/device_id.json`（Windows：`%APPDATA%/.pdk_client/`）；
3. 都没有则按"本机指纹（MachineGuid / machine-id）"SHA-256 生成 `CPP-xxxxxxxx`，并落盘持久化；
4. **服务端权威**：注册/登录成功后，用服务端返回的 `deviceId` 覆盖本地缓存，保证同设备稳定、跨设备不一致。

---

## 7. 加密说明

`acquire-token` 返回的 `data.encryptedPayload` 是密文，需 `decryptToken` 解密后才能拿到拼多多 Session。算法与后端 `AesByteFlipUtils` 对称：

```
Key  = SHA256( RootSalt + "_" + (epochSeconds/60/10) )[:16]   // 128-bit
IV   = raw[2:14]                                              // 12 字节
CT   = raw[14:-16]  +  Tag(raw[-16:])                         // AES-128-GCM
明文 = AESGCM_Decrypt(Key, IV, CT, Tag)
其中 raw = reverse( Base64Decode(encryptedPayload) )，并校验魔数 0x50 0x44('PD')
```

解密自动容忍 ±1 个 10 分钟时间窗（应对时钟偏差）。

---

## 8. 错误处理

- 网络不可达 / 非 JSON：返回 `code=0`，`message` 说明原因；状态切到 `Error`。
- 业务错误按 `ResultCode` 区分（见 `pdk.hpp`）：如 `40103` 设备互踢（状态切 `Kicked`，并清空本地登录态）、`40302` 配额耗尽、`50301` 槽位繁忙。
- 调用方务必检查 `r.ok()` 或 `r.code`。

---

## 9. 线程安全

`Client` 内部未加锁。建议：**单线程顺序调用**，或每个线程各自持有一个 `Client` 实例。不要从多个线程并发调用同一个 `Client`。

---

## 10. 给 易语言 / 其他语言

`pdk_capi` 动态库导出了与上面方法一一对应的 C 函数（返回 JSON 字符串，调用方用各自 JSON 库解析）。详见 `../e/` 目录下的 易语言 接入说明与示例。
