/*
 * PDK 客户端 SDK —— C ABI 头文件（供 DLL 导出，便于 易语言 / C# / Delphi / Go 等调用）
 *
 * 设计要点：
 *   - 所有 API 调用统一返回 JSON 字符串（完整 ApiResponse：{"code","message","data","httpStatus"}），
 *     由调用方用各自 JSON 库解析，避免复杂的跨语言结构体封送。
 *   - 字符串全部为 UTF-8 的 char*；调用方必须用 pdk_free_string 释放返回值。
 *   - 状态/事件通过「回调」或「轮询」两种方式暴露（见下方说明），满足“状态通过回调告诉开发者”的要求：
 *        * C/C#/Delphi 开发者：使用 pdk_set_state_callback / pdk_set_log_callback 注册 C 函数指针；
 *        * 易语言 开发者：易语言对 C 回调支持有限，推荐用 pdk_get_last_state / pdk_get_last_state_detail 轮询。
 *
 * 编译为动态库：见 CMakeLists.txt（BUILD_SHARED_LIBS=ON 会导出 pdk_capi 符号）。
 */
#ifndef PDK_CAPI_H
#define PDK_CAPI_H

#ifdef _WIN32
#  ifdef PDK_CAPI_EXPORTS
#    define PDK_CAPI __declspec(dllexport)
#  else
#    define PDK_CAPI __declspec(dllimport)
#  endif
#else
#  define PDK_CAPI __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* 句柄：代表一个客户端实例 */
typedef void* PdkHandle;

/* 状态回调：state 见 pdk::State，detail 为 UTF-8 文本，userData 透传 */
#ifdef _WIN32
typedef void (__stdcall* PdkStateCallback)(int state, const char* detail, void* userData);
typedef void (__stdcall* PdkLogCallback)(const char* line, void* userData);
#else
typedef void (*PdkStateCallback)(int state, const char* detail, void* userData);
typedef void (*PdkLogCallback)(const char* line, void* userData);
#endif

/* 创建 / 销毁实例 */
PDK_CAPI PdkHandle pdk_create(const char* base_url, const char* device_id, const char* root_salt);
/* 多业务构造；旧 pdk_create 保持兼容并默认 app_id=1。 */
PDK_CAPI PdkHandle pdk_create_ex(const char* base_url, const char* device_id,
                                 const char* root_salt, long app_id);
PDK_CAPI void      pdk_destroy(PdkHandle h);

/* 释放由本库返回的字符串（务必调用，避免内存泄漏） */
PDK_CAPI void      pdk_free_string(char* s);

/* 注册回调（userData 会随回调原样回传；传 NULL 关闭对应回调） */
PDK_CAPI void      pdk_set_state_callback(PdkHandle h, PdkStateCallback cb, void* userData);
PDK_CAPI void      pdk_set_log_callback(PdkHandle h, PdkLogCallback cb, void* userData);

/* 轮询式状态（无需回调即可知道“现在是什么状态”） */
PDK_CAPI int       pdk_get_last_state(PdkHandle h);
PDK_CAPI char*     pdk_get_last_state_detail(PdkHandle h);

/* 会话信息 */
PDK_CAPI int       pdk_is_logged_in(PdkHandle h);
PDK_CAPI char*     pdk_get_phone(PdkHandle h);
PDK_CAPI char*     pdk_get_device_id(PdkHandle h);
PDK_CAPI char*     pdk_get_token_value(PdkHandle h);
PDK_CAPI long      pdk_get_app_id(PdkHandle h);
/* 成功返回1，非法句柄或 app_id<=0 返回0。 */
PDK_CAPI int       pdk_set_app_id(PdkHandle h, long app_id);

/* 鉴权相关（返回 JSON 字符串，需 pdk_free_string 释放） */
PDK_CAPI char* pdk_send_sms(PdkHandle h, const char* phone, const char* purpose);
PDK_CAPI char* pdk_register(PdkHandle h, const char* phone, const char* password,
                            const char* sms_code, const char* invitation_code);
PDK_CAPI char* pdk_login(PdkHandle h, const char* phone, const char* password);
PDK_CAPI char* pdk_logout(PdkHandle h);
PDK_CAPI char* pdk_unbind_device(PdkHandle h);
PDK_CAPI char* pdk_change_password(PdkHandle h, const char* phone,
                                   const char* old_password, const char* new_password);
/* 自助找回密码（无需旧密码）：先 pdk_send_sms(phone,"RESET_PASSWORD") 取验证码，再调用本函数 */
PDK_CAPI char* pdk_reset_password(PdkHandle h, const char* phone,
                                  const char* sms_code, const char* new_password);
/* 管理端：代客户重置密码（强制下次登录改密），需管理员会话 */
PDK_CAPI char* pdk_admin_reset_password(PdkHandle h, long user_id, const char* new_password);
/* 管理端：切换客户「强制下次登录改密」标记，需管理员会话 */
PDK_CAPI char* pdk_admin_set_password_policy(PdkHandle h, long user_id, int must_change);

/* 卡密核销（开放接口） */
PDK_CAPI char* pdk_activate_card(PdkHandle h, const char* card_key, const char* user_phone,
                                 const char* payment_channel, double actual_amount);

/* 调度网关 */
PDK_CAPI char* pdk_acquire_token(PdkHandle h, const char* action_type, const char* goods_id);
/* 解密 encrypted_payload（来自 acquire-token 的 data.encryptedPayload）；失败返回 NULL */
PDK_CAPI char* pdk_decrypt_token(PdkHandle h, const char* encrypted_payload);
PDK_CAPI char* pdk_report_result(PdkHandle h, const char* lease_trace_id, const char* status,
                                 long response_duration_ms, const char* error_message);

/* 账号查询 */
PDK_CAPI char* pdk_business_info(PdkHandle h);
PDK_CAPI char* pdk_profile(PdkHandle h);
PDK_CAPI char* pdk_usage(PdkHandle h, int page, int size);
PDK_CAPI char* pdk_resource_status(PdkHandle h);
PDK_CAPI char* pdk_card_list(PdkHandle h);

/* 协议信封加密（RSA-OAEP + AES-256-GCM，与后端 BodyCryptoService 对齐）
 *   - pdk_enable_envelope: 用服务端公钥(PEM)与 kid 直接启用；启用后带 body 的请求自动加密、响应自动解密。
 *   - pdk_is_envelope_enabled: 返回 1=已启用 / 0=未启用。
 *   - pdk_refresh_crypto_config: 拉取 GET /api/v1/client/config/public 并按 encryptionMode 自动启用/关闭。
 *     返回该配置接口的 JSON（同其他接口格式）；mode=off 时不启用，便于灰度。 */
PDK_CAPI void pdk_enable_envelope(PdkHandle h, const char* public_key_pem, const char* kid);
PDK_CAPI int  pdk_is_envelope_enabled(PdkHandle h);
PDK_CAPI char* pdk_refresh_crypto_config(PdkHandle h);

/* P0 公钥指纹钉扎：设置期望指纹（SHA-256(DER) 的 hex 前 32 字符）。
 * 留空("")不校验；设置后 pdk_refresh_crypto_config 拉到的公钥指纹不符会拒绝启用。
 * 指纹需通过独立可信渠道获取（编译期内置 / 配置文件 / 运维下发），勿从同一接口动态取。 */
PDK_CAPI void pdk_set_public_key_pin(PdkHandle h, const char* fingerprint);

/* 设置钉扎指纹的本地持久化文件路径（JSON: {"pin":"..."}）。
 * 未显式 pdk_set_public_key_pin 时，首次拉取成功会把指纹写入该文件（TOFU），
 * 后续拉取比对；生产仍推荐用 pdk_set_public_key_pin 预置指纹。 */
PDK_CAPI void pdk_set_pin_store_path(PdkHandle h, const char* path);

#ifdef __cplusplus
}
#endif

#endif /* PDK_CAPI_H */
