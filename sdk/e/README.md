# PDK 客户端 SDK —— 易语言 接入指南

本目录提供 **易语言** 接入 PDK 云控平台的方式。核心思路：

> **易语言 不直接做 HTTPS 通信与 AES 解密，而是调用 C++ 编译出来的 `pdk_capi.dll`。**
> 所有网络、加密、设备指纹、会话管理都在 C++ 侧完成；易语言 只负责「业务逻辑编排 + 界面展示 + 状态轮询」。

这样做的原因：
- 调度 Token 是 `AES-128-GCM + 时间窗 + 字节翻转` 加密，手撸 GCM 在 易语言 里极易出错；
- HTTPS + JSON 在 易语言 里依赖 精易模块，但拼包/解包繁琐；
- C++ DLL 一次编译，Windows 下即拖即用，**接入最简单、最稳**。

---

## 1. 准备 DLL

按 `../cplusplus/README.md` 编译出 `pdk_capi.dll`（需 curl + openssl + nlohmann-json，推荐 vcpkg）。
把 `pdk_capi.dll` 放到你的 易语言 程序同目录（或 `C:\Windows\System32`）。

> 该 DLL 同时被 C# / Delphi / Go 等调用，接口完全一致（见 `../cplusplus/include/pdk/pdk_capi.h`）。

---

## 2. 在 易语言 中声明 DLL 命令

打开 易语言 → 右键「程序」→「新 DLL 命令」，按 `DLL命令声明.e.txt` 的内容逐个添加。

**⚠️ 关键：字符串编码必须设为 UTF-8**

易语言 默认用 GBK(ANSI) 与 DLL 交换文本，而本 DLL 全部使用 **UTF-8**。
在每个文本型参数 / 返回值的「编码」处选择 **UTF-8**（易语言 5.6+ 支持；老版本请用「字节集」传参并在 C 侧按 UTF-8 处理）。
否则中文手机号、JSON 里的中文会乱码。

如果 IDE 版本不支持返回值 UTF-8，可把返回类型改为「字节集」，再在 易语言 里用
`编码_字节集到文本(返回字节集, #编码_UTF_8)`（精易模块）转成文本。

---

## 3. 调用流程（与 C++/Python 端完全一致）

```
pdk_create_ex(..., appId)          ' 创建实例，拿到句柄；PDD=1
  └─ pdk_business_info()            ' 登录前读取名称、注册策略、状态、支持动作
  └─ pdk_send_sms(手机, "REGISTER") ' 发验证码
  └─ pdk_register(手机, 密码, 验证码) ' 或 pdk_login
  └─ pdk_acquire_token(动作, 商品ID) ' 拿到加密 VO(JSON)
        └─ 从 JSON 取 data.encryptedPayload
        └─ pdk_decrypt_token(encryptedPayload) ' 得到明文 Token(JSON)
        └─ 用明文里的拼多多 Session 向官方发包
  └─ pdk_report_result(租约号, "SUCCESS") ' 上报：SUCCESS 扣 1 次
  └─ pdk_profile() / pdk_resource_status() ' 查配额/小号
pdk_destroy(句柄)                  ' 释放
```

旧程序继续调用 `pdk_create()` 时默认使用 `appId=1`，无需修改。新业务客户端应使用
`pdk_create_ex(..., appId)`，并把 appId 固定在各自构建中。DLL 会自动为所有 HTTP 请求添加
`X-PDK-App-ID`，注册、登录、短信、改密和卡密激活的请求体也会自动携带相同 appId。

---

## 4. 状态怎么“通过回调告诉开发者”？

易语言 对「C 函数指针回调」支持有限（无法方便地把 易语言 子程序地址传给 DLL），因此本 SDK 提供**两种**方式暴露状态：

### 方式 A：轮询（易语言 首选，最稳）

每次调用后，用下面两个函数读取「当前状态」：
- `pdk_get_last_state(h)` → 返回整数（见下表）
- `pdk_get_last_state_detail(h)` → 返回文本说明

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

典型用法：调用 `pdk_login` 后，立刻 `判断 (pdk_get_last_state(h) = 6)` 来刷新界面上的「已登录」标签。

### 方式 B：C 回调（C/C#/Delphi 用；易语言 进阶）

DLL 提供 `pdk_set_state_callback` / `pdk_set_log_callback`，可注册 `__stdcall` 函数指针。
易语言 若想用，需要借助一个「中转 DLL」把 Windows 消息(`PostMessage`) 发到你的窗口，再用 `窗口消息` 事件处理。
对大多数 易语言 开发者，方式 A 轮询已足够，推荐优先使用。

---

## 5. 返回值与错误码

- 所有业务函数返回 **JSON 文本**（形如 `{"code":200,"message":"...","data":{...},"httpStatus":200}`）。
- 用 精易模块 的 `类_json` 解析：取 `code`（`json.取属性("code").取数值()`），`code=200` 成功，`code=0` 网络失败。
- 典型业务码：`40103` 设备互踢、`40302` 配额耗尽、`50301` 槽位繁忙、`40010` 已领过体验。
- `pdk_decrypt_token` 失败返回空文本（`""` 或 NULL），需判空。

---

## 6. 文件清单

- `README.md` —— 本文件
- `DLL命令声明.e.txt` —— 可直接照抄到 易语言 的 DLL 命令声明
- `PDK调用示例.e.txt` —— 一个完整可参考的 易语言 子程序示例（注册→取 Token→解密→上报→查配额）

> 需要 JSON 解析时，请在 易语言 中引用 **精易模块**（其中的 `类_json` / `编码_字节集到文本`）。
