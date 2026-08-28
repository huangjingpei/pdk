/*
 * PDK 客户端 SDK —— C++ 头文件（公共接口）
 *
 * 适用场景：桌面采集客户端 / 机器人脚本 / 第三方集成（Windows / Linux / macOS）。
 *
 * 设计目标：
 *   1. 接入简单：一个 Pdk::Client 对象即可完成注册、登录、核销、调度、查询；
 *   2. 状态可见：通过「状态回调 / 事件回调 / 调试日志回调」实时把当前状态通知开发者与客户；
 *   3. 安全合规：HTTPS 传输 + AES-128-GCM 动态时间窗加密调度 Token（与后端 AesByteFlipUtils 完全对称）。
 *
 * 依赖（见 CMakeLists.txt / vcpkg.json）：
 *   - libcurl        ：HTTP / HTTPS 传输（任选 TLS 后端）
 *   - OpenSSL        ：AES-128-GCM 解密 + SHA-256 密钥派生
 *   - nlohmann/json  ：JSON 解析（header-only）
 *
 * 与后端契约保持一致，参考：
 *   - backend-springboot/.../AesByteFlipUtils.java
 *   - backend-springboot/.../controller/{ClientAuthController,CardKeyActivationController,
 *                                        DispatchGatewayController,ClientAccountController}.java
 *   - docs/CLIENT_INTEGRATION_GUIDE.md
 */
#pragma once

#include <functional>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>

namespace pdk {

/* ============================================================================
 * 1. 业务返回码（与后端 CommonResult.code 一致，详见 docs/CLIENT_INTEGRATION_GUIDE.md §6）
 *    注意：网络层失败（curl 不可达 / 非 JSON 响应）统一用 NETWORK_ERROR(0)。
 * ========================================================================== */
struct ResultCode {
    static constexpr int SUCCESS               = 200;   // 成功
    static constexpr int CARD_NOT_FOUND        = 40001; // 卡密不存在或格式错误
    static constexpr int CARD_ALREADY_USED     = 40002; // 卡密已被核销或作废
    static constexpr int CONCURRENT_CONFLICT   = 40004; // 并发冲突
    static constexpr int TRIAL_ALREADY_CLAIMED = 40010; // 已领取过体验
    static constexpr int MISSING_AUTH_HEADERS  = 40101; // 缺少鉴权请求头
    static constexpr int DEVICE_KICK_OUT       = 40103; // 账号在其他设备登录，本端被踢
    static constexpr int SUBSCRIPTION_EXPIRED  = 40301; // 订阅已到期
    static constexpr int QUOTA_EXHAUSTED       = 40302; // 今日配额耗尽
    static constexpr int NO_AVAILABLE_TOKEN    = 50301; // 底层槽位繁忙
    static constexpr int NETWORK_ERROR         = 0;     // 客户端本地：网络不可达 / 非 JSON
};

/* ============================================================================
 * 2. 客户端连接状态（通过 setStateCallback 通知「现在是什么状态」）
 * ========================================================================== */
enum class State : int {
    Uninitialized = 0,  // 未初始化
    Ready,              // 已初始化，未登录
    SmsSent,            // 验证码已发送
    Registering,        // 注册请求中
    Registered,         // 注册成功（已登录）
    LoggingIn,          // 登录请求中
    LoggedIn,           // 登录成功
    LoggingOut,         // 注销请求中
    LoggedOut,          // 已注销
    DeviceUnbound,      // 设备已解绑（账号设备标识保留）
    TokenAcquiring,     // 正在申请短效 Token
    TokenAcquired,      // 已取得并解密 Token（可立刻向拼多多官方发包）
    TokenFailed,        // 申请 Token 失败
    ResultReporting,    // 正在上报业务结果
    Kicked,             // 被其他设备踢下线（40103）
    Error,              // 通用错误
};

/* ============================================================================
 * 3. 细粒度事件（通过 setEventCallback 通知「发生了什么具体事情」）
 * ========================================================================== */
enum class Event : int {
    None = 0,
    RequestSent,        // 已发出 HTTP 请求
    ResponseReceived,   // 已收到 HTTP 响应
    DecryptSucceeded,   // Token 解密成功
    DecryptFailed,      // Token 解密失败
    QuotaLow,           // 剩余配额偏低（<=10）
    SubscriptionExpired,// 订阅已到期
    QuotaExhausted,     // 配额耗尽
    NoAvailableToken,   // 底层槽位繁忙
};

/* ============================================================================
 * 4. 统一响应结构（封装后端 CommonResult 信封：{code,message,data,timestamp}）
 * ========================================================================== */
struct ApiResponse {
    int         httpStatus = 0;   // HTTP 状态码，0 表示网络层失败
    int         code       = 0;   // 业务码（CommonResult.code）
    std::string message;          // 业务消息
    std::string dataJson;         // data 字段的原始 JSON 字符串（可能为 "null"）

    bool ok() const noexcept { return code == ResultCode::SUCCESS; }

    // 便捷读取 data 内的字段（data 必须是对象；缺失或类型不符返回默认值）
    std::string dataString(const std::string& key, const std::string& def = "") const;
    long        dataLong(const std::string& key, long def = 0) const;
    bool        dataBool(const std::string& key, bool def = false) const;
    // 直接取 data 中的子路径 JSON（例如 "assignments/0/slotIndex"），失败返回 ""
    std::string dataAt(const std::string& pointer, const std::string& def = "") const;
};

/* ============================================================================
 * 5. 配置
 * ========================================================================== */
struct Config {
    std::string baseUrl  = "http://localhost:8080"; // 服务端基础地址（生产用 https）
    long        appId    = 1; // 客户端构建固定的公开业务标识；PDD=1
    std::string rootSalt = "PDK_SECRET_SALT_2026_ENTERPRISE"; // 与后端 AesByteFlipUtils 一致
    std::string deviceId;        // 可选：留空则自动生成并持久化（方案A：本地缓存+服务端权威）
    bool        enableDebugLog = false; // 是否把 HTTP 请求/响应/期待 输出到调试日志回调
    int         httpTimeoutMs  = 20000; // 单次请求超时
};

/* ============================================================================
 * 6. 异常
 * ========================================================================== */
class PdkException : public std::runtime_error {
public:
    explicit PdkException(const std::string& what) : std::runtime_error(what) {}
};

/* ============================================================================
 * 7. 主客户端
 * ========================================================================== */
class Client {
public:
    using StateCallback = std::function<void(State, const std::string& detail)>;
    using EventCallback = std::function<void(Event, const std::string& message)>;
    using LogCallback   = std::function<void(const std::string& line)>;

    explicit Client(const Config& cfg = Config{});
    ~Client();

    // 仅可移动（内部持有 unique_ptr<Impl>）；禁止拷贝
    // 注意：移动构造/赋值在 pdk.cpp 中 =default 定义（Impl 完整处），
    // 因为 unique_ptr<Impl> 的移动赋值需要 Impl 完整类型，否则在跨 TU 使用时（pdk_capi.cpp）
    // 会触发“不完整类型”硬错误。
    Client(const Client&) = delete;
    Client& operator=(const Client&) = delete;
    Client(Client&&) noexcept;
    Client& operator=(Client&&) noexcept;

    // —— 回调注册（可随时更换；回调在调用线程内同步触发）——
    void setStateCallback(StateCallback cb) { stateCb_ = std::move(cb); }
    void setEventCallback(EventCallback cb) { eventCb_ = std::move(cb); }
    void setLogCallback(LogCallback cb)     { logCb_   = std::move(cb); }

    // —— 会话信息 ——
    bool        isLoggedIn()  const;
    std::string phone()       const;
    std::string deviceId()    const;
    std::string tokenName()   const;
    std::string tokenValue()  const;
    long        appId()       const;
    void        setAppId(long appId); // 调试/多构建配置；生产客户端不应开放给最终用户修改
    std::string lastStateDetail() const; // 最近一次状态详情（便于无回调时轮询）
    State       lastState()   const;

    // —— 鉴权 ——
    ApiResponse businessInfo(); // 登录前公开业务信息：名称/描述/注册模式/有效状态
    ApiResponse sendSms(const std::string& phone, const std::string& purpose = "REGISTER");
    ApiResponse registerAccount(const std::string& phone,
                                const std::string& password,
                                const std::string& smsCode,
                                const std::string& invitationCode = "");
    ApiResponse login(const std::string& phone, const std::string& password);
    ApiResponse logout();
    ApiResponse unbindDevice();
    ApiResponse changePassword(const std::string& phone,
                               const std::string& oldPassword,
                               const std::string& newPassword);
    // 自助找回密码（无需旧密码）：先 sendSms(phone, "RESET_PASSWORD") 取得验证码，再调用本方法
    ApiResponse resetPassword(const std::string& phone,
                              const std::string& smsCode,
                              const std::string& newPassword);
    // 管理端：代客户重置密码（强制下次登录改密），需管理员会话
    ApiResponse adminResetPassword(long userId, const std::string& newPassword);
    // 管理端：切换客户「强制下次登录改密」标记，需管理员会话
    ApiResponse adminSetPasswordPolicy(long userId, bool mustChange);

    // —— 卡密核销（开放接口，无需登录）——
    ApiResponse activateCard(const std::string& cardKey,
                             const std::string& userPhone,
                             const std::string& paymentChannel = "OFFLINE",
                             double             actualAmount = 0.0);

    // —— 调度网关 ——
    // 申请短效加密 Token（返回加密 VO，含 encryptedPayload / leaseTraceId 等）
    ApiResponse acquireToken(const std::string& actionType, const std::string& goodsId);
    // 解密 acquire-token 返回的 encryptedPayload，得到明文 JSON 字符串（失败抛 PdkException）
    std::string decryptToken(const std::string& encryptedPayload);
    // 一步到位：申请并解密；成功时 outPlainJson 为明文，状态切到 TokenAcquired
    ApiResponse acquireTokenDecrypted(const std::string& actionType,
                                      const std::string& goodsId,
                                      std::string& outPlainJson);
    // 异步上报业务结果（SUCCESS 扣 1 次；FAIL_* 免责扣 0 次并自愈）
    ApiResponse reportResult(const std::string& leaseTraceId,
                             const std::string& status,
                             long               responseDurationMs = 1000,
                             const std::string& errorMessage = "");

    // —— 账号查询 ——
    ApiResponse profile();
    ApiResponse usage(int page = 1, int size = 20);
    ApiResponse resourceStatus();
    ApiResponse cardList();

    // —— 工具 ——
    // 根据当前状态/响应，推断并触发对应的 State / Event（SDK 内部使用，可外部复用）
    static std::string describeState(State s);
    static std::string describeEvent(Event e);

    // 协议信封加密（RSA-OAEP + AES-256-GCM，与后端 BodyCryptoService 对齐）
    // 启用后，所有带 body 的请求会用服务端公钥加密，响应自动解密；请求侧仅一次 RSA，响应侧零非对称开销。
    void enableEnvelope(const std::string& publicKeyPem, const std::string& kid = "v1");
    bool isEnvelopeEnabled() const;
    // 拉取服务端公钥配置（GET /api/v1/client/config/public）并按 encryptionMode 自动启用信封加密。
    // 返回该配置接口的 ApiResponse；mode=off 时不启用，保持明文（便于灰度）。
    // 若已 setPublicKeyPin，会先比对公钥指纹，不符则返回 NETWORK_ERROR 且不启用（防 MITM 替换公钥）。
    ApiResponse refreshCryptoConfig();

    // P0 公钥指纹钉扎：设置期望指纹（SHA-256(DER) 的 hex 前 32 字符）。
    // 留空则不校验；设置后 refreshCryptoConfig 拉到的公钥指纹不符会拒绝启用加密。
    void setPublicKeyPin(const std::string& fingerprint);

    // 设置钉扎指纹的本地持久化文件路径（JSON: {"pin":"..."}）。
    // 未显式 setPublicKeyPin 时，首次拉取成功会把指纹写入该文件（TOFU），
    // 后续拉取比对；生产仍推荐用 setPublicKeyPin 预置指纹。
    void setPinStorePath(const std::string& path);

private:
    struct Impl;                 // PImpl：隐藏 libcurl / OpenSSL 细节
    std::unique_ptr<Impl> impl_;

    StateCallback stateCb_;
    EventCallback eventCb_;
    LogCallback   logCb_;

    void emitState(State s, const std::string& detail);
    void emitEvent(Event e, const std::string& msg);
    void emitLog(const std::string& line);

    // 统一请求封装：发请求 -> 解析 CommonResult 信封 -> 触发请求/响应事件与调试日志
    // retried 为内部重试标记（密钥错误自动重拉公钥后重试一次，防循环）。
    ApiResponse request(const std::string& method,
                        const std::string& path,
                        bool authenticated,
                        const std::string& bodyJson,
                        const std::string& query,
                        const std::string& expectation,
                        bool retried = false);

    // 最近一次状态（便于无回调时轮询）
    State       lastState_ = State::Uninitialized;
    std::string lastStateDetail_;
};

/* ============================================================================
 * 8. 工具函数（可选）
 * ========================================================================== */
// 在 Windows 控制台正确显示中文/UTF-8：将控制台与标准流切换到 UTF-8。
// 仅在 Windows 生效；Linux/macOS 下为空操作。SDK 库本身不调用它（避免副作用），
// 由宿主程序（示例 / 易语言控制台测试等）在 main 入口处调用一次即可。
void enable_utf8_console();

} // namespace pdk
