/*
 * PDK 客户端 SDK —— C++ 实现
 *
 * 依赖：libcurl（HTTPS）、OpenSSL（AES-128-GCM + SHA-256）、nlohmann/json（header-only）。
 * 与后端契约严格对齐：backend-springboot/.../AesByteFlipUtils.java 及各类 Controller。
 */
#include "pdk/pdk.hpp"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstring>
#include <ctime>
#include <fstream>
#include <sstream>
#include <vector>

#include <curl/curl.h>
#include <openssl/evp.h>
#include <openssl/sha.h>
#include <nlohmann/json.hpp>

#ifdef _WIN32
#include <windows.h>
#endif

#ifdef _WIN32
#  include <windows.h>
#  include <winreg.h>
#else
#  include <unistd.h>
#  include <sys/stat.h>
#endif

namespace pdk {

using json = nlohmann::json;

/* ============================================================================
 * 内部辅助：Base64 解码（标准，忽略空白与 '='）
 * ========================================================================== */
static std::vector<unsigned char> base64_decode(const std::string& in) {
    static const char* tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    int rev[256];
    for (int i = 0; i < 256; ++i) rev[i] = -1;
    for (int i = 0; i < 64; ++i) rev[(unsigned char)tbl[i]] = i;

    std::string s;
    for (char c : in) if (!std::isspace((unsigned char)c)) s += c;

    std::vector<unsigned char> out;
    int buf = 0, bits = 0;
    for (char c : s) {
        if (c == '=') break;
        int v = rev[(unsigned char)c];
        if (v < 0) continue;
        buf = (buf << 6) | v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back((unsigned char)((buf >> bits) & 0xFF));
        }
    }
    return out;
}

/* ============================================================================
 * 内部辅助：SHA-256 派生 16 字节 AES 密钥（与后端 deriveKey 一致）
 * ========================================================================== */
static std::vector<unsigned char> derive_key(const std::string& rootSalt, long window) {
    std::string raw = rootSalt + "_" + std::to_string(window);
    unsigned char hash[32];
    EVP_Digest(raw.data(), raw.size(), hash, nullptr, EVP_sha256(), nullptr);
    return std::vector<unsigned char>(hash, hash + 16); // 128-bit
}

/* ============================================================================
 * 内部辅助：AES-128-GCM 解密（末尾 16 字节为 GCM Tag）
 * ========================================================================== */
static std::string aes_gcm_decrypt(const std::vector<unsigned char>& key,
                                   const std::vector<unsigned char>& iv,
                                   const std::vector<unsigned char>& ctWithTag) {
    if (ctWithTag.size() < 16) throw PdkException("密文长度不足（缺少 GCM Tag）");
    std::vector<unsigned char> ciphertext(ctWithTag.begin(), ctWithTag.end() - 16);
    std::vector<unsigned char> tag(ctWithTag.end() - 16, ctWithTag.end());

    EVP_CIPHER_CTX* ctx = EVP_CIPHER_CTX_new();
    if (!ctx) throw PdkException("EVP_CIPHER_CTX 创建失败");
    try {
        if (EVP_DecryptInit_ex(ctx, EVP_aes_128_gcm(), nullptr, nullptr, nullptr) != 1)
            throw PdkException("EVP_DecryptInit_ex 失败");
        if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, (int)iv.size(), nullptr) != 1)
            throw PdkException("设置 IV 长度失败");
        if (EVP_DecryptInit_ex(ctx, nullptr, nullptr, key.data(), iv.data()) != 1)
            throw PdkException("EVP_DecryptInit_ex(key/iv) 失败");
        if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, (int)tag.size(), (void*)tag.data()) != 1)
            throw PdkException("设置 GCM Tag 失败");

        std::vector<unsigned char> out(ciphertext.size());
        int len = 0, total = 0;
        if (EVP_DecryptUpdate(ctx, out.data(), &len, ciphertext.data(), (int)ciphertext.size()) != 1)
            throw PdkException("EVP_DecryptUpdate 失败");
        total += len;
        int fret = 0;
        if (EVP_DecryptFinal_ex(ctx, out.data() + total, &fret) != 1)
            throw PdkException("GCM Tag 校验失败（数据被篡改或密钥错误）");
        total += fret;
        out.resize((size_t)total);
        EVP_CIPHER_CTX_free(ctx);
        return std::string(out.begin(), out.end());
    } catch (...) {
        EVP_CIPHER_CTX_free(ctx);
        throw;
    }
}

/* ============================================================================
 * 内部辅助：设备 ID（方案A：本地缓存 + 服务端权威；与 Python 端逻辑一致）
 * ========================================================================== */
static std::string machine_fingerprint() {
#ifdef _WIN32
    HKEY hKey;
    char buf[256] = {0};
    DWORD sz = sizeof(buf);
    if (RegOpenKeyExA(HKEY_LOCAL_MACHINE,
                      "SOFTWARE\\Microsoft\\Cryptography", 0,
                      KEY_READ | KEY_WOW64_64KEY, &hKey) == ERROR_SUCCESS) {
        if (RegQueryValueExA(hKey, "MachineGuid", nullptr, nullptr, (LPBYTE)buf, &sz) == ERROR_SUCCESS) {
            RegCloseKey(hKey);
            return std::string(buf);
        }
        RegCloseKey(hKey);
    }
    // 兜底：卷序列号
    char vol[4] = "C:\\";
    DWORD sn = 0;
    if (GetVolumeInformationA(vol, nullptr, 0, &sn, nullptr, nullptr, nullptr, 0) && sn)
        return "VOL" + std::to_string(sn);
    return "WIN-" + std::to_string(GetCurrentProcessId());
#else
    // 优先 /etc/machine-id，否则 hostname+mac
    for (const char* p : {"/etc/machine-id", "/var/lib/dbus/machine-id"}) {
        std::ifstream f(p);
        if (f) { std::string s; std::getline(f, s); if (!s.empty()) return s; }
    }
    char host[256] = {0};
    if (gethostname(host, sizeof(host)) == 0) return std::string("HOST-") + host;
    return "POSIX-unk";
#endif
}

static std::string sha256_hex(const std::string& s) {
    unsigned char hash[32];
    EVP_Digest(s.data(), s.size(), hash, nullptr, EVP_sha256(), nullptr);
    std::string out;
    const char* hex = "0123456789abcdef";
    for (int i = 0; i < 32; ++i) { out += hex[hash[i] >> 4]; out += hex[hash[i] & 0xF]; }
    return out;
}

static std::string device_cache_dir() {
#ifdef _WIN32
    const char* appdata = getenv("APPDATA");
    std::string base = appdata ? std::string(appdata) : "C:\\";
    return base + "\\.pdk_client";
#else
    const char* home = getenv("HOME");
    std::string base = home ? std::string(home) : "/tmp";
    return base + "/.pdk_client";
#endif
}

static std::string load_device_id() {
    std::string path = device_cache_dir() + "/device_id.json";
    std::ifstream f(path);
    if (!f) return "";
    try {
        json j; f >> j;
        std::string id = j.value("device_id", "");
        return id;
    } catch (...) { return ""; }
}

static void save_device_id(const std::string& id) {
    if (id.empty()) return;
    std::string dir = device_cache_dir();
#ifdef _WIN32
    CreateDirectoryA(dir.c_str(), nullptr);
#else
    mkdir(dir.c_str(), 0700);
#endif
    std::ofstream f(dir + "/device_id.json");
    if (!f) return;
    json j = {{"device_id", id}, {"updated_at", ""}};
    f << j.dump(2);
}

static std::string default_device_id() {
    const char* env = getenv("PDK_DEVICE_ID");
    if (env && *env) return std::string(env);
    std::string cached = load_device_id();
    if (!cached.empty()) return cached;
    std::string fp = sha256_hex(machine_fingerprint());
    std::string id = "CPP-" + fp.substr(0, 24);
    std::transform(id.begin(), id.end(), id.begin(), ::toupper);
    save_device_id(id);
    return id;
}

/* ============================================================================
 * 内部辅助：libcurl 写入回调 + 单次 HTTP 执行
 * ========================================================================== */
static size_t curl_write_cb(char* ptr, size_t size, size_t nmemb, void* userdata) {
    auto* body = static_cast<std::string*>(userdata);
    body->append(ptr, size * nmemb);
    return size * nmemb;
}

struct HttpResult {
    int httpStatus = 0;     // 0 = 网络失败
    std::string body;       // 原始响应体
    bool networkOk = false;
};

static HttpResult curl_exec(const std::string& method,
                            const std::string& url,
                            int timeoutMs,
                            const std::map<std::string, std::string>& headers,
                            const std::string& bodyJson) {
    HttpResult r;
    CURL* curl = curl_easy_init();
    if (!curl) { r.body = R"({"code":0,"message":"curl 初始化失败"})"; return r; }

    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_TIMEOUT_MS, (long)timeoutMs);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, curl_write_cb);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &r.body);

    struct curl_slist* list = nullptr;
    list = curl_slist_append(list, "Accept: application/json");
    for (const auto& kv : headers)
        list = curl_slist_append(list, (kv.first + ": " + kv.second).c_str());
    bool isPost = (method == "POST");
    if (isPost) {
        list = curl_slist_append(list, "Content-Type: application/json");
        curl_easy_setopt(curl, CURLOPT_POST, 1L);
        curl_easy_setopt(curl, CURLOPT_POSTFIELDS, bodyJson.c_str());
        curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, (long)bodyJson.size());
    } else {
        curl_easy_setopt(curl, CURLOPT_HTTPGET, 1L);
    }
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, list);

    // TLS：默认验证；开发环境可用 PDK_INSECURE=1 跳过（仅自测用）
    if (getenv("PDK_INSECURE")) {
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
    }

    CURLcode res = curl_easy_perform(curl);
    long code = 0;
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &code);
    r.httpStatus = (int)code;
    curl_slist_free_all(list);
    curl_easy_cleanup(curl);

    if (res == CURLE_OK) {
        r.networkOk = true;
    } else {
        r.networkOk = false;
        r.body = R"({"code":0,"message":"网络请求失败: )" + std::string(curl_easy_strerror(res)) + R"("})";
    }
    return r;
}

/* ============================================================================
 * PImpl：存放配置与会话，避免对外暴露 curl/openssl 头文件
 * ========================================================================== */
struct Client::Impl {
    std::string baseUrl;
    std::string rootSalt;
    int         timeoutMs = 20000;
    bool        debugLog  = false;

    std::string tokenName  = "satoken";
    std::string tokenValue;
    std::string phone;
    std::string deviceId;
    std::string password;
};

/* ============================================================================
 * 构造 / 析构
 * ========================================================================== */
Client::Client(const Config& cfg) : impl_(std::make_unique<Impl>()) {
    impl_->baseUrl   = cfg.baseUrl;
    if (!impl_->baseUrl.empty() && impl_->baseUrl.back() == '/')
        impl_->baseUrl.pop_back();
    impl_->rootSalt  = cfg.rootSalt.empty() ? "PDK_SECRET_SALT_2026_ENTERPRISE" : cfg.rootSalt;
    impl_->timeoutMs = cfg.httpTimeoutMs;
    impl_->debugLog  = cfg.enableDebugLog;
    impl_->deviceId  = cfg.deviceId.empty() ? default_device_id() : cfg.deviceId;
    emitState(State::Ready, "客户端初始化完成，设备ID=" + impl_->deviceId);
}

Client::~Client() = default;

// 移动构造/赋值在 Impl 完整处（本文件）定义为 default，
// 避免 unique_ptr<Impl> 移动赋值需要完整类型而在头文件中定义报错。
Client::Client(Client&&) noexcept = default;
Client& Client::operator=(Client&&) noexcept = default;

/* ============================================================================
 * 回调触发
 * ========================================================================== */
void Client::emitState(State s, const std::string& detail) {
    lastState_ = s;
    lastStateDetail_ = detail;
    if (stateCb_) { try { stateCb_(s, detail); } catch (...) {} }
}
void Client::emitEvent(Event e, const std::string& msg) {
    if (eventCb_) { try { eventCb_(e, msg); } catch (...) {} }
}
void Client::emitLog(const std::string& line) {
    if (impl_ && impl_->debugLog && logCb_) { try { logCb_(line); } catch (...) {} }
}

State Client::lastState() const { return lastState_; }
std::string Client::lastStateDetail() const { return lastStateDetail_; }

/* ============================================================================
 * 会话信息
 * ========================================================================== */
bool        Client::isLoggedIn()  const { return !impl_->tokenValue.empty(); }
std::string Client::phone()       const { return impl_->phone; }
std::string Client::deviceId()    const { return impl_->deviceId; }
std::string Client::tokenName()   const { return impl_->tokenName; }
std::string Client::tokenValue()  const { return impl_->tokenValue; }

/* ============================================================================
 * 内部：统一请求 + 解析为 ApiResponse，并发出请求/响应事件与调试日志
 * ========================================================================== */
ApiResponse Client::request(const std::string& method,
                            const std::string& path,
                            bool authenticated,
                            const std::string& bodyJson,
                            const std::string& query,
                            const std::string& expectation) {
    std::map<std::string, std::string> hdrs;
    if (authenticated) {
        if (!impl_->tokenValue.empty())
            hdrs[impl_->tokenName] = impl_->tokenValue;
        if (!impl_->phone.empty())
            hdrs["X-PDK-Phone"] = impl_->phone;
        if (!impl_->deviceId.empty())
            hdrs["X-PDK-Device-ID"] = impl_->deviceId;
    }

    std::string url = impl_->baseUrl + path;
    if (!query.empty()) url += "?" + query;

    emitEvent(Event::RequestSent, method + " " + path);
    emitLog("[请求] " + method + " " + url + (bodyJson.empty() ? "" : "\n   body: " + bodyJson));
    if (!expectation.empty()) emitLog("[期待] " + expectation);

    HttpResult hr = curl_exec(method, url, impl_->timeoutMs, hdrs, bodyJson);

    ApiResponse resp;
    resp.httpStatus = hr.httpStatus;
    if (!hr.networkOk) {
        // 网络层失败：本地错误报文
        try {
            auto j = json::parse(hr.body);
            resp.code = j.value("code", 0);
            resp.message = j.value("message", "网络请求失败");
        } catch (...) {
            resp.code = ResultCode::NETWORK_ERROR;
            resp.message = "网络请求失败";
        }
        emitEvent(Event::ResponseReceived, "HTTP " + std::to_string(hr.httpStatus) +
                  " (网络层) code=" + std::to_string(resp.code));
        emitLog("[响应] HTTP " + std::to_string(hr.httpStatus) + " | " + resp.message);
        if (resp.code != ResultCode::SUCCESS) emitState(State::Error, resp.message);
        return resp;
    }

    try {
        auto root = json::parse(hr.body);
        resp.code    = root.value("code", 0);
        resp.message = root.value("message", "");
        if (root.contains("data") && !root["data"].is_null())
            resp.dataJson = root["data"].dump();
        else
            resp.dataJson = "null";
    } catch (...) {
        resp.code = ResultCode::NETWORK_ERROR;
        resp.message = "服务端返回非 JSON（HTTP " + std::to_string(hr.httpStatus) + "）";
        resp.dataJson = "null";
    }

    emitEvent(Event::ResponseReceived,
              "HTTP " + std::to_string(hr.httpStatus) + " code=" + std::to_string(resp.code));
    emitLog("[响应] HTTP " + std::to_string(hr.httpStatus) +
            " | code=" + std::to_string(resp.code) + " | " + resp.message +
            " | data=" + resp.dataJson);

    // 设备互踢
    if (resp.code == ResultCode::DEVICE_KICK_OUT) {
        impl_->tokenValue.clear();
        emitState(State::Kicked, resp.message);
    }
    return resp;
}

/* ============================================================================
 * 鉴权
 * ========================================================================== */
ApiResponse Client::sendSms(const std::string& phone, const std::string& purpose) {
    emitState(State::Ready, "正在发送验证码到 " + phone);
    json j = {{"phone", phone}, {"purpose", purpose.empty() ? "REGISTER" : purpose}};
    ApiResponse r = request("POST", "/api/v1/client/auth/sms/send", false, j.dump(), "",
                            "期待: code=200 且 data 含 expireMinutes/debugCode");
    if (r.ok()) emitState(State::SmsSent, "验证码已发送（" + phone + "）");
    else if (r.code == 42901) emitState(State::Error, "短信过于频繁，请 60 秒后重试");
    return r;
}

ApiResponse Client::registerAccount(const std::string& phone,
                                    const std::string& password,
                                    const std::string& smsCode,
                                    const std::string& invitationCode) {
    emitState(State::Registering, "正在注册 " + phone);
    json j = {
        {"phone", phone},
        {"password", password},
        {"deviceId", impl_->deviceId},
        {"smsCode", smsCode},
        {"invitationCode", invitationCode.empty() ? json(nullptr) : invitationCode},
    };
    ApiResponse r = request("POST", "/api/v1/client/auth/register", false, j.dump(), "",
                            "期待: code=200，data 含 tokenValue/deviceId/remainingCalls");
    if (r.ok()) {
        auto d = json::parse(r.dataJson);
        impl_->tokenName  = d.value("tokenName", "satoken");
        impl_->tokenValue = d.value("tokenValue", "");
        impl_->phone      = phone;
        std::string srvDid = d.value("deviceId", impl_->deviceId);
        impl_->deviceId   = srvDid;
        save_device_id(srvDid);       // 服务端权威，回写本地缓存
        impl_->password   = password;
        emitState(State::Registered, "注册成功并登录：" + phone);
    } else {
        emitState(State::Error, r.message);
    }
    return r;
}

ApiResponse Client::login(const std::string& phone, const std::string& password) {
    emitState(State::LoggingIn, "正在登录 " + phone);
    json j = {{"phone", phone}, {"password", password}, {"deviceId", impl_->deviceId}};
    ApiResponse r = request("POST", "/api/v1/client/auth/login", false, j.dump(), "",
                            "期待: code=200（40103=设备不一致需解绑；40105=密码错误）");
    if (r.ok()) {
        auto d = json::parse(r.dataJson);
        impl_->tokenName  = d.value("tokenName", "satoken");
        impl_->tokenValue = d.value("tokenValue", "");
        impl_->phone      = phone;
        std::string srvDid = d.value("deviceId", impl_->deviceId);
        impl_->deviceId   = srvDid;
        save_device_id(srvDid);
        impl_->password   = password;
        emitState(State::LoggedIn, "登录成功：" + phone);
    } else if (r.code == ResultCode::DEVICE_KICK_OUT) {
        emitState(State::Kicked, r.message);
    } else {
        emitState(State::Error, r.message);
    }
    return r;
}

ApiResponse Client::logout() {
    emitState(State::LoggingOut, "正在注销");
    ApiResponse r = request("POST", "/api/v1/client/auth/logout", true, "", "",
                            "期待: code=200，清除本地会话");
    if (r.ok()) { impl_->tokenValue.clear(); emitState(State::LoggedOut, "已注销"); }
    else emitState(State::Error, r.message);
    return r;
}

ApiResponse Client::unbindDevice() {
    ApiResponse r = request("POST", "/api/v1/client/auth/unbind-device", true, "", "",
                            "期待: code=200，保留账号 deviceId，仅清登录态");
    if (r.ok()) {
        impl_->tokenValue.clear();   // 方案A：保留 deviceId，仅清登录态
        emitState(State::DeviceUnbound, "已解绑当前会话，账号设备标识保持不变");
    } else emitState(State::Error, r.message);
    return r;
}

ApiResponse Client::changePassword(const std::string& phone,
                                   const std::string& oldPassword,
                                   const std::string& newPassword) {
    json j = {{"phone", phone}, {"oldPassword", oldPassword}, {"newPassword", newPassword}};
    ApiResponse r = request("POST", "/api/v1/client/auth/change-password", false, j.dump(), "",
                            "期待: code=200，密码修改成功");
    if (!r.ok()) emitState(State::Error, r.message);
    return r;
}

/* ============================================================================
 * 卡密核销（开放接口）
 * ========================================================================== */
ApiResponse Client::activateCard(const std::string& cardKey,
                                 const std::string& userPhone,
                                 const std::string& paymentChannel,
                                 double actualAmount) {
    json j = {
        {"cardKey", cardKey},
        {"userPhone", userPhone},
        {"deviceId", impl_->deviceId},
        {"orderType", "NORMAL_SALE"},
        {"paymentChannel", paymentChannel.empty() ? "OFFLINE" : paymentChannel},
    };
    if (actualAmount > 0.0) j["actualAmount"] = actualAmount;
    ApiResponse r = request("POST", "/api/v1/card/activate", false, j.dump(), "",
                            "期待: code=200，data 含 newExpireTime/totalRemainingCalls");
    if (!r.ok()) emitState(State::Error, r.message);
    return r;
}

/* ============================================================================
 * 调度网关
 * ========================================================================== */
ApiResponse Client::acquireToken(const std::string& actionType, const std::string& goodsId) {
    emitState(State::TokenAcquiring, "正在申请短效 Token（" + actionType + "）");
    json j = {
        {"actionType", actionType},
        {"goodsId", goodsId},
        {"timestamp", (long long)(std::time(nullptr) * 1000)},
    };
    ApiResponse r = request("POST", "/api/v1/dispatch/acquire-token", true, j.dump(), "",
                            "期待: code=200，data 含 encryptedPayload/leaseTraceId/expireAtTimestamp");
    if (r.ok()) {
        // 注意：此处仅拿到加密 VO，需用 decryptToken 解密后使用
    } else if (r.code == ResultCode::NO_AVAILABLE_TOKEN) {
        emitEvent(Event::NoAvailableToken, r.message);
        emitState(State::TokenFailed, r.message);
    } else if (r.code == ResultCode::QUOTA_EXHAUSTED) {
        emitEvent(Event::QuotaExhausted, r.message);
        emitState(State::TokenFailed, r.message);
    } else if (r.code == ResultCode::SUBSCRIPTION_EXPIRED) {
        emitEvent(Event::SubscriptionExpired, r.message);
        emitState(State::TokenFailed, r.message);
    } else {
        emitState(State::TokenFailed, r.message);
    }
    return r;
}

std::string Client::decryptToken(const std::string& encryptedPayload) {
    try {
        std::vector<unsigned char> flipped = base64_decode(encryptedPayload);
        if (flipped.size() < 14 + 16) throw PdkException("加密数据包长度不足");
        std::reverse(flipped.begin(), flipped.end());
        if (flipped[0] != 0x50 || flipped[1] != 0x44)
            throw PdkException("魔数校验失败：非有效 PDK 加密数据包");
        std::vector<unsigned char> iv(flipped.begin() + 2, flipped.begin() + 14);
        std::vector<unsigned char> ct(flipped.begin() + 14, flipped.end());

        long now = (long)(std::time(nullptr) / 60 / 10);
        for (long w : {now, now - 1, now + 1}) {
            try {
                std::vector<unsigned char> key = derive_key(impl_->rootSalt, w);
                std::string plain = aes_gcm_decrypt(key, iv, ct);
                emitEvent(Event::DecryptSucceeded, "Token 解密成功");
                return plain;
            } catch (const PdkException&) { /* 尝试相邻时间窗 */ }
        }
        throw PdkException("解密失败：密钥过期或数据被篡改");
    } catch (const PdkException& e) {
        emitEvent(Event::DecryptFailed, e.what());
        throw;
    }
}

ApiResponse Client::acquireTokenDecrypted(const std::string& actionType,
                                          const std::string& goodsId,
                                          std::string& outPlainJson) {
    outPlainJson.clear();
    ApiResponse r = acquireToken(actionType, goodsId);
    if (r.ok()) {
        try {
            auto d = json::parse(r.dataJson);
            std::string enc = d.value("encryptedPayload", "");
            outPlainJson = decryptToken(enc);
            emitState(State::TokenAcquired, "已取得并解密短效 Token，可立即向拼多多官方发包");
        } catch (const PdkException& e) {
            emitState(State::TokenFailed, e.what());
        }
    }
    return r;
}

ApiResponse Client::reportResult(const std::string& leaseTraceId,
                                 const std::string& status,
                                 long responseDurationMs,
                                 const std::string& errorMessage) {
    emitState(State::ResultReporting, "正在上报业务结果（" + status + "）");
    json j = {
        {"leaseTraceId", leaseTraceId},
        {"status", status},
        {"responseDurationMs", responseDurationMs < 0 ? 1000 : responseDurationMs},
        {"errorMessage", errorMessage},
    };
    ApiResponse r = request("POST", "/api/v1/dispatch/report-result", true, j.dump(), "",
                            "期待: code=200；SUCCESS 扣1次，FAIL_* 免责扣0次");
    if (!r.ok()) emitState(State::Error, r.message);
    return r;
}

/* ============================================================================
 * 账号查询
 * ========================================================================== */
ApiResponse Client::profile() {
    return request("GET", "/api/v1/client/account/profile", true, "", "",
                   "期待: code=200，data 含 remainingCalls/expireTime/packageName");
}

ApiResponse Client::usage(int page, int size) {
    std::string q = "page=" + std::to_string(page) + "&size=" + std::to_string(size);
    return request("GET", "/api/v1/client/account/usage", true, "", q,
                   "期待: code=200，data 含 remainingCalls/successCount/failureCount");
}

ApiResponse Client::resourceStatus() {
    return request("GET", "/api/v1/client/resources/status", true, "", "",
                   "期待: code=200，data 含 assignedResourceCount/availableResourceCount");
}

ApiResponse Client::cardList() {
    return request("GET", "/api/v1/client/account/card", true, "", "",
                   "期待: code=200，data 含 cardKey/cardStatus/assignments");
}

/* ============================================================================
 * ApiResponse 便捷取值
 * ========================================================================== */
std::string ApiResponse::dataString(const std::string& key, const std::string& def) const {
    try {
        auto d = json::parse(dataJson);
        if (d.is_object() && d.contains(key)) {
            const auto& v = d[key];
            if (v.is_string()) return v.get<std::string>();
            return v.dump();
        }
    } catch (...) {}
    return def;
}

long ApiResponse::dataLong(const std::string& key, long def) const {
    try {
        auto d = json::parse(dataJson);
        if (d.is_object() && d.contains(key) && d[key].is_number())
            return d[key].get<long>();
    } catch (...) {}
    return def;
}

bool ApiResponse::dataBool(const std::string& key, bool def) const {
    try {
        auto d = json::parse(dataJson);
        if (d.is_object() && d.contains(key) && d[key].is_boolean())
            return d[key].get<bool>();
    } catch (...) {}
    return def;
}

std::string ApiResponse::dataAt(const std::string& pointer, const std::string& def) const {
    try {
        auto d = json::parse(dataJson);
        const auto& v = d.at(json::json_pointer("/" + pointer));
        if (v.is_string()) return v.get<std::string>();
        return v.dump();
    } catch (...) {}
    return def;
}

/* ============================================================================
 * 状态 / 事件 文案
 * ========================================================================== */
std::string Client::describeState(State s) {
    switch (s) {
        case State::Uninitialized: return "未初始化";
        case State::Ready:         return "就绪（未登录）";
        case State::SmsSent:       return "验证码已发送";
        case State::Registering:   return "注册中";
        case State::Registered:    return "注册成功（已登录）";
        case State::LoggingIn:     return "登录中";
        case State::LoggedIn:      return "已登录";
        case State::LoggingOut:    return "注销中";
        case State::LoggedOut:     return "已注销";
        case State::DeviceUnbound: return "设备已解绑";
        case State::TokenAcquiring:return "申请 Token 中";
        case State::TokenAcquired: return "已取得 Token";
        case State::TokenFailed:   return "申请 Token 失败";
        case State::ResultReporting:return "上报结果中";
        case State::Kicked:        return "被其他设备踢下线";
        case State::Error:         return "错误";
    }
    return "未知状态";
}

std::string Client::describeEvent(Event e) {
    switch (e) {
        case Event::RequestSent:        return "已发送请求";
        case Event::ResponseReceived:   return "已收到响应";
        case Event::DecryptSucceeded:   return "Token 解密成功";
        case Event::DecryptFailed:      return "Token 解密失败";
        case Event::QuotaLow:           return "剩余配额偏低";
        case Event::SubscriptionExpired:return "订阅已到期";
        case Event::QuotaExhausted:     return "配额耗尽";
        case Event::NoAvailableToken:   return "底层槽位繁忙";
        case Event::None:               return "无";
    }
    return "未知事件";
}

/* ============================================================================
 * 控制台 UTF-8 支持（Windows 控制台默认 GBK，会乱码中文）
 * ========================================================================== */
void enable_utf8_console() {
#ifdef _WIN32
    // 让控制台设备按 UTF-8 解读写入的字节。
    // 注意：不要把 stdout 设成 _O_U8TEXT；那样会让 std::cout（窄字符流）与 CRT 冲突，
    // 在输出 std::string/const char* 时直接 crash。
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
#endif
}

} // namespace pdk
