/*
 * PDK 客户端 SDK —— C ABI 实现（编译为动态库供 易语言 / C# / Delphi / Go 调用）
 *
 * 设计：把 C++ Pdk::Client 包进一个 CapiInstance，用 void* 句柄透传；
 *       所有返回字符串用 malloc 分配，调用方务必用 pdk_free_string 释放。
 */
#include "pdk/pdk_capi.h"
#include "pdk/pdk.hpp"

#include <cstdlib>
#include <cstring>
#include <string>

#include <nlohmann/json.hpp>

using namespace pdk;

namespace {

struct CapiInstance {
    Client       client;
    PdkStateCallback stateCb   = nullptr;
    void*           stateData = nullptr;
    PdkLogCallback  logCb     = nullptr;
    void*           logData   = nullptr;
};

inline CapiInstance* asInst(PdkHandle h) { return static_cast<CapiInstance*>(h); }

char* dup_str(const std::string& s) {
    char* p = (char*)malloc(s.size() + 1);
    if (!p) return nullptr;
    memcpy(p, s.c_str(), s.size() + 1);
    return p;
}

std::string resp_to_json(const ApiResponse& r) {
    // 统一返回 {"code","message","data","httpStatus"}
    nlohmann::json j = {
        {"code", r.code},
        {"message", r.message},
        {"data", r.dataJson.empty() ? nlohmann::json(nullptr) : nlohmann::json::parse(r.dataJson)},
        {"httpStatus", r.httpStatus},
    };
    return j.dump();
}

} // namespace

extern "C" {

PDK_CAPI PdkHandle pdk_create(const char* base_url, const char* device_id, const char* root_salt) {
    return pdk_create_ex(base_url, device_id, root_salt, 1);
}

PDK_CAPI PdkHandle pdk_create_ex(const char* base_url, const char* device_id,
                                 const char* root_salt, long app_id) {
    if (app_id <= 0) return nullptr;
    Config cfg;
    if (base_url && *base_url) cfg.baseUrl = base_url;
    if (device_id && *device_id) cfg.deviceId = device_id;
    if (root_salt && *root_salt) cfg.rootSalt = root_salt;
    cfg.appId = app_id;
    cfg.enableDebugLog = false;

    auto* inst = new (std::nothrow) CapiInstance();
    if (!inst) return nullptr;
    // 用 placement-friendly 构造：Client 的构造会触发 emitState(Ready)
    inst->client = Client(cfg);  // 拷贝/移动；Client 可移动（unique_ptr 成员可移动）

    // 适配 C 回调
    inst->client.setStateCallback([inst](State s, const std::string& detail) {
        if (inst->stateCb) inst->stateCb((int)s, detail.c_str(), inst->stateData);
    });
    inst->client.setLogCallback([inst](const std::string& line) {
        if (inst->logCb) inst->logCb(line.c_str(), inst->logData);
    });
    return static_cast<PdkHandle>(inst);
}

PDK_CAPI void pdk_destroy(PdkHandle h) {
    delete asInst(h);
}

PDK_CAPI void pdk_free_string(char* s) {
    free(s);
}

PDK_CAPI void pdk_set_state_callback(PdkHandle h, PdkStateCallback cb, void* userData) {
    auto* inst = asInst(h);
    if (!inst) return;
    inst->stateCb = cb;
    inst->stateData = userData;
}

PDK_CAPI void pdk_set_log_callback(PdkHandle h, PdkLogCallback cb, void* userData) {
    auto* inst = asInst(h);
    if (!inst) return;
    inst->logCb = cb;
    inst->logData = userData;
}

PDK_CAPI int pdk_get_last_state(PdkHandle h) {
    auto* inst = asInst(h);
    return inst ? (int)inst->client.lastState() : 0;
}

PDK_CAPI char* pdk_get_last_state_detail(PdkHandle h) {
    auto* inst = asInst(h);
    return inst ? dup_str(inst->client.lastStateDetail()) : dup_str("");
}

PDK_CAPI int pdk_is_logged_in(PdkHandle h) {
    auto* inst = asInst(h);
    return inst && inst->client.isLoggedIn() ? 1 : 0;
}

PDK_CAPI char* pdk_get_phone(PdkHandle h) {
    auto* inst = asInst(h);
    return inst ? dup_str(inst->client.phone()) : dup_str("");
}

PDK_CAPI char* pdk_get_device_id(PdkHandle h) {
    auto* inst = asInst(h);
    return inst ? dup_str(inst->client.deviceId()) : dup_str("");
}

PDK_CAPI char* pdk_get_token_value(PdkHandle h) {
    auto* inst = asInst(h);
    return inst ? dup_str(inst->client.tokenValue()) : dup_str("");
}

PDK_CAPI long pdk_get_app_id(PdkHandle h) {
    auto* inst = asInst(h);
    return inst ? inst->client.appId() : 0;
}

PDK_CAPI int pdk_set_app_id(PdkHandle h, long app_id) {
    auto* inst = asInst(h);
    if (!inst || app_id <= 0) return 0;
    inst->client.setAppId(app_id);
    return 1;
}

PDK_CAPI char* pdk_send_sms(PdkHandle h, const char* phone, const char* purpose) {
    auto* inst = asInst(h);
    if (!inst) return dup_str(R"({"code":0,"message":"无效句柄"})");
    return dup_str(resp_to_json(inst->client.sendSms(phone ? phone : "", purpose ? purpose : "REGISTER")));
}

PDK_CAPI char* pdk_register(PdkHandle h, const char* phone, const char* password,
                            const char* sms_code, const char* invitation_code) {
    auto* inst = asInst(h);
    if (!inst) return dup_str(R"({"code":0,"message":"无效句柄"})");
    return dup_str(resp_to_json(inst->client.registerAccount(
        phone ? phone : "", password ? password : "", sms_code ? sms_code : "",
        invitation_code ? invitation_code : "")));
}

PDK_CAPI char* pdk_login(PdkHandle h, const char* phone, const char* password) {
    auto* inst = asInst(h);
    if (!inst) return dup_str(R"({"code":0,"message":"无效句柄"})");
    return dup_str(resp_to_json(inst->client.login(phone ? phone : "", password ? password : "")));
}

PDK_CAPI char* pdk_logout(PdkHandle h) {
    auto* inst = asInst(h);
    if (!inst) return dup_str(R"({"code":0,"message":"无效句柄"})");
    return dup_str(resp_to_json(inst->client.logout()));
}

PDK_CAPI char* pdk_unbind_device(PdkHandle h) {
    auto* inst = asInst(h);
    if (!inst) return dup_str(R"({"code":0,"message":"无效句柄"})");
    return dup_str(resp_to_json(inst->client.unbindDevice()));
}

PDK_CAPI char* pdk_change_password(PdkHandle h, const char* phone,
                                   const char* old_password, const char* new_password) {
    auto* inst = asInst(h);
    if (!inst) return dup_str(R"({"code":0,"message":"无效句柄"})");
    return dup_str(resp_to_json(inst->client.changePassword(
        phone ? phone : "", old_password ? old_password : "", new_password ? new_password : "")));
}

PDK_CAPI char* pdk_activate_card(PdkHandle h, const char* card_key, const char* user_phone,
                                 const char* payment_channel, double actual_amount) {
    auto* inst = asInst(h);
    if (!inst) return dup_str(R"({"code":0,"message":"无效句柄"})");
    return dup_str(resp_to_json(inst->client.activateCard(
        card_key ? card_key : "", user_phone ? user_phone : "",
        payment_channel ? payment_channel : "OFFLINE", actual_amount)));
}

PDK_CAPI char* pdk_acquire_token(PdkHandle h, const char* action_type, const char* goods_id) {
    auto* inst = asInst(h);
    if (!inst) return dup_str(R"({"code":0,"message":"无效句柄"})");
    return dup_str(resp_to_json(inst->client.acquireToken(
        action_type ? action_type : "", goods_id ? goods_id : "")));
}

PDK_CAPI char* pdk_decrypt_token(PdkHandle h, const char* encrypted_payload) {
    auto* inst = asInst(h);
    if (!inst || !encrypted_payload) return nullptr;
    try {
        std::string plain = inst->client.decryptToken(encrypted_payload);
        return dup_str(plain);
    } catch (...) {
        return nullptr;
    }
}

PDK_CAPI char* pdk_report_result(PdkHandle h, const char* lease_trace_id, const char* status,
                                 long response_duration_ms, const char* error_message) {
    auto* inst = asInst(h);
    if (!inst) return dup_str(R"({"code":0,"message":"无效句柄"})");
    return dup_str(resp_to_json(inst->client.reportResult(
        lease_trace_id ? lease_trace_id : "", status ? status : "",
        response_duration_ms, error_message ? error_message : "")));
}

PDK_CAPI char* pdk_business_info(PdkHandle h) {
    auto* inst = asInst(h);
    if (!inst) return dup_str(R"({"code":0,"message":"无效句柄"})");
    return dup_str(resp_to_json(inst->client.businessInfo()));
}

PDK_CAPI char* pdk_profile(PdkHandle h) {
    auto* inst = asInst(h);
    if (!inst) return dup_str(R"({"code":0,"message":"无效句柄"})");
    return dup_str(resp_to_json(inst->client.profile()));
}

PDK_CAPI char* pdk_usage(PdkHandle h, int page, int size) {
    auto* inst = asInst(h);
    if (!inst) return dup_str(R"({"code":0,"message":"无效句柄"})");
    return dup_str(resp_to_json(inst->client.usage(page, size)));
}

PDK_CAPI char* pdk_resource_status(PdkHandle h) {
    auto* inst = asInst(h);
    if (!inst) return dup_str(R"({"code":0,"message":"无效句柄"})");
    return dup_str(resp_to_json(inst->client.resourceStatus()));
}

PDK_CAPI char* pdk_card_list(PdkHandle h) {
    auto* inst = asInst(h);
    if (!inst) return dup_str(R"({"code":0,"message":"无效句柄"})");
    return dup_str(resp_to_json(inst->client.cardList()));
}

PDK_CAPI void pdk_enable_envelope(PdkHandle h, const char* public_key_pem, const char* kid) {
    auto* inst = asInst(h);
    if (!inst || !public_key_pem || !*public_key_pem) return;
    inst->client.enableEnvelope(public_key_pem, kid ? kid : "v1");
}

PDK_CAPI int pdk_is_envelope_enabled(PdkHandle h) {
    auto* inst = asInst(h);
    return (inst && inst->client.isEnvelopeEnabled()) ? 1 : 0;
}

PDK_CAPI char* pdk_refresh_crypto_config(PdkHandle h) {
    auto* inst = asInst(h);
    if (!inst) return dup_str(R"({"code":0,"message":"无效句柄"})");
    return dup_str(resp_to_json(inst->client.refreshCryptoConfig()));
}

PDK_CAPI void pdk_set_public_key_pin(PdkHandle h, const char* fingerprint) {
    auto* inst = asInst(h);
    if (!inst) return;
    inst->client.setPublicKeyPin(fingerprint ? fingerprint : "");
}

PDK_CAPI void pdk_set_pin_store_path(PdkHandle h, const char* path) {
    auto* inst = asInst(h);
    if (!inst) return;
    inst->client.setPinStorePath(path ? path : "");
}

} // extern "C"
