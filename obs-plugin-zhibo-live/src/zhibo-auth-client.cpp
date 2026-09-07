#include "zhibo-auth-client.hpp"
#include "zhibo-config.hpp"
#include "utils/json.hpp"
#include "utils/pdk-crypto.hpp"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wincrypt.h>
#include <sstream>
#include <iomanip>
#include <iostream>

#pragma comment(lib, "advapi32.lib")

using json = nlohmann::json;

namespace zhibo {

namespace {

std::string compute_sha256_hex(const std::string &input) {
    HCRYPTPROV hProv = 0;
    HCRYPTHASH hHash = 0;
    BYTE rgbHash[32] = {0};
    DWORD cbHash = 32;

    if (CryptAcquireContext(&hProv, NULL, NULL, PROV_RSA_AES, CRYPT_VERIFYCONTEXT)) {
        if (CryptCreateHash(hProv, CALG_SHA_256, 0, 0, &hHash)) {
            if (CryptHashData(hHash, (const BYTE *)input.c_str(), (DWORD)input.length(), 0)) {
                CryptGetHashParam(hHash, HP_HASHVAL, rgbHash, &cbHash, 0);
            }
            CryptDestroyHash(hHash);
        }
        CryptReleaseContext(hProv, 0);
    }

    std::stringstream ss;
    for (DWORD i = 0; i < cbHash; ++i) {
        ss << std::hex << std::setw(2) << std::setfill('0') << (int)rgbHash[i];
    }
    return ss.str();
}

std::string get_windows_machine_guid() {
    HKEY hKey;
    char guid[256] = {0};
    DWORD dwSize = sizeof(guid);
    if (RegOpenKeyExA(HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Cryptography", 0, KEY_READ | KEY_WOW64_64KEY, &hKey) == ERROR_SUCCESS) {
        RegQueryValueExA(hKey, "MachineGuid", NULL, NULL, (LPBYTE)guid, &dwSize);
        RegCloseKey(hKey);
    }
    if (guid[0] == '\0') {
        return "UNKNOWN-WINDOWS-GUID";
    }
    return std::string(guid);
}

} // namespace

ZhiboAuthClient &ZhiboAuthClient::instance() {
    static ZhiboAuthClient client;
    return client;
}

ZhiboAuthClient::ZhiboAuthClient() {}

std::string ZhiboAuthClient::get_or_create_device_id() {
    auto &cfg = ZhiboConfig::instance();
    std::string device_id = cfg.get_device_id();
    if (!device_id.empty()) {
        return device_id;
    }

    std::string machine_guid = get_windows_machine_guid();
    std::string hash = compute_sha256_hex(machine_guid);
    device_id = "OBS-WIN-" + hash.substr(0, 16);

    cfg.set_device_id(device_id);
    cfg.save();
    return device_id;
}

std::map<std::string, std::string> ZhiboAuthClient::build_auth_headers() {
    auto &cfg = ZhiboConfig::instance();
    std::map<std::string, std::string> headers;
    headers["X-PDK-App-ID"] = std::to_string(cfg.get_app_id());
    headers["X-PDK-Device-ID"] = get_or_create_device_id();
    headers["X-PDK-Phone"] = cfg.get_phone();

    std::string token_name = cfg.get_token_name();
    std::string token_val = cfg.get_token_value();
    if (!token_name.empty() && !token_val.empty()) {
        headers[token_name] = token_val;
    }
    return headers;
}

AuthResult ZhiboAuthClient::login(const std::string &phone, const std::string &password, const std::string &card_key) {
    auto &cfg = ZhiboConfig::instance();
    std::string device_id = get_or_create_device_id();
    std::string base_url = cfg.get_server_url();
    std::string login_url = base_url + "/api/v1/client/auth/login";

    json req;
    req["appId"] = cfg.get_app_id();
    req["phone"] = phone;
    req["password"] = password;
    req["deviceId"] = device_id;
    req["deviceName"] = "OBS Studio (Windows)";
    req["platform"] = "windows";
    req["clientVersion"] = "1.0.0";
    if (!card_key.empty()) {
        req["cardKey"] = card_key;
    }

    // 获取服务端加密配置
    PublicConfig pub_cfg;
    std::string crypto_err;
    bool has_pub = PdkCrypto::instance().fetch_public_config(base_url, pub_cfg, crypto_err);

    std::string send_body;
    std::vector<uint8_t> session_key;
    bool req_encrypted = false;

    if (has_pub && pub_cfg.encryption_mode != "off") {
        if (!PdkCrypto::instance().encrypt_body(req.dump(), pub_cfg, send_body, session_key, crypto_err)) {
            AuthResult result;
            result.phone = phone;
            result.success = false;
            result.code = 42905;
            result.message = "本地报文信封加密失败: " + crypto_err;
            return result;
        }
        req_encrypted = true;
    } else {
        send_body = req.dump();
    }

    std::map<std::string, std::string> headers;
    headers["X-PDK-App-ID"] = std::to_string(cfg.get_app_id());
    headers["X-PDK-Device-ID"] = device_id;
    headers["X-PDK-Phone"] = phone;

    HttpResponse resp = http_.post_json(login_url, send_body, headers);

    AuthResult result;
    result.phone = phone;

    if (!resp.is_success() && resp.body.empty()) {
        result.success = false;
        result.code = resp.status_code;
        result.message = resp.error_message.empty() ? "网络连接异常，HTTP 状态码: " + std::to_string(resp.status_code) : resp.error_message;
        return result;
    }

    std::string body_to_parse = resp.body;
    if (PdkCrypto::is_envelope(resp.body)) {
        std::string plain;
        if (PdkCrypto::instance().decrypt_response(resp.body, session_key, plain, crypto_err)) {
            body_to_parse = plain;
            session_key_ = session_key;
        } else {
            result.success = false;
            result.code = 42904;
            result.message = "解密服务端响应信封失败: " + crypto_err;
            return result;
        }
    } else if (req_encrypted) {
        session_key_ = session_key;
    }

    try {
        json j = json::parse(body_to_parse);
        result.code = j.value("code", 0);
        result.message = j.value("message", "");

        if (result.code == 200) {
            result.success = true;
            if (j.contains("data") && j["data"].is_object()) {
                auto data = j["data"];
                result.token_name = data.value("tokenName", "satoken");
                result.token_value = data.value("tokenValue", "");

                if (data.contains("deviceLicense") && data["deviceLicense"].is_object()) {
                    auto lic = data["deviceLicense"];
                    result.license_status = lic.value("status", "ACTIVE");
                    result.expire_at = lic.value("expireAt", "");
                    result.remaining_calls = lic.value("remainingCalls", 0);
                }

                // 更新本地持久化配置
                cfg.set_phone(phone);
                cfg.set_password(password);
                if (!card_key.empty()) {
                    cfg.set_card_key(card_key);
                }
                cfg.set_token_name(result.token_name);
                cfg.set_token_value(result.token_value);
                cfg.set_license_status(result.license_status);
                cfg.set_expire_at(result.expire_at);
                cfg.set_remaining_calls(result.remaining_calls);

                // 尝试拉取服务端下发的动态流媒体节点地址
                try {
                    std::string biz_url = base_url + "/api/v1/client/business/by-app/" + std::to_string(cfg.get_app_id());
                    HttpResponse b_resp = http_.get(biz_url);
                    if (b_resp.is_success()) {
                        json bj = json::parse(b_resp.body);
                        if (bj.value("code", 0) == 200 && bj.contains("data")) {
                            auto b_data = bj["data"];
                            if (b_data.contains("liveMedia") && b_data["liveMedia"].is_object()) {
                                std::string ms_addr = b_data["liveMedia"].value("mediaServerAddress", "");
                                if (!ms_addr.empty() && ms_addr.find("rtmp://") == 0) {
                                    cfg.set_rtmp_base_url(ms_addr);
                                }
                            }
                        }
                    }
                } catch (...) {}

                cfg.save();
            }
        } else if (result.code == 40380) {
            // 40380: 设备未激活，需要卡密
            result.success = false;
            result.need_card_key = true;
            if (result.message.empty()) {
                result.message = "设备尚未激活，请输入卡密进行绑定激活";
            }
        } else {
            result.success = false;
        }
    } catch (const std::exception &e) {
        result.success = false;
        result.code = -1;
        result.message = "服务端数据解析异常: " + std::string(e.what());
    }

    return result;
}

AuthResult ZhiboAuthClient::check_session() {
    auto &cfg = ZhiboConfig::instance();
    std::string phone = cfg.get_phone();
    std::string password = cfg.get_password();
    std::string card_key = cfg.get_card_key();

    if (phone.empty() || password.empty()) {
        AuthResult res;
        res.success = false;
        res.message = "尚未配置登录信息";
        return res;
    }

    // 尝试重新登录刷新会话
    return login(phone, password, card_key);
}

std::vector<StreamInfo> ZhiboAuthClient::query_current_streams() {
    std::vector<StreamInfo> list;
    auto &cfg = ZhiboConfig::instance();
    if (cfg.get_token_value().empty()) {
        return list;
    }

    std::string base_url = cfg.get_server_url();
    std::string query_url = base_url + "/api/v1/client/zhibo-live/streams/current?scope=account";
    auto headers = build_auth_headers();

    HttpResponse resp = http_.get(query_url, headers);
    if (!resp.is_success()) {
        return list;
    }

    std::string body_to_parse = resp.body;
    if (PdkCrypto::is_envelope(resp.body)) {
        if (!session_key_.empty()) {
            std::string plain;
            std::string crypto_err;
            if (PdkCrypto::instance().decrypt_response(resp.body, session_key_, plain, crypto_err)) {
                body_to_parse = plain;
            } else {
                std::cerr << "[ZhiboAuthClient] 解密推流会话信封响应失败: " << crypto_err << std::endl;
                return list;
            }
        }
    }

    try {
        json j = json::parse(body_to_parse);
        int code = j.value("code", 0);
        if (code == 200 && j.contains("data") && j["data"].is_array()) {
            std::string rtmp_base = cfg.get_rtmp_base_url();
            for (const auto &item : j["data"]) {
                StreamInfo info;
                info.session_no = item.value("streamSessionNo", "");
                info.path = item.value("path", "");
                info.status = item.value("status", "");
                info.media_node_code = item.value("mediaNodeCode", "");

                if (!info.path.empty()) {
                    // 确保 rtmpBase 不以 / 结尾，path 不以 / 开头
                    std::string clean_base = rtmp_base;
                    while (!clean_base.empty() && clean_base.back() == '/') clean_base.pop_back();
                    std::string clean_path = info.path;
                    while (!clean_path.empty() && clean_path.front() == '/') clean_path.erase(0, 1);

                    info.rtmp_url = clean_base + "/" + clean_path;
                }

                list.push_back(info);
            }
        }
    } catch (const std::exception &e) {
        std::cerr << "[ZhiboAuthClient] 解析推流会话异常: " << e.what() << std::endl;
    }

    return list;
}

} // namespace zhibo
