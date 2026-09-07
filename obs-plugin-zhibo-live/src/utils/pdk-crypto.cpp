#include "pdk-crypto.hpp"
#include "http-client.hpp"
#include "json.hpp"

#include <windows.h>
#include <wincrypt.h>
#include <bcrypt.h>

#include <chrono>
#include <random>
#include <sstream>

#pragma comment(lib, "bcrypt.lib")
#pragma comment(lib, "crypt32.lib")

using json = nlohmann::json;

namespace zhibo {

PdkCrypto& PdkCrypto::instance() {
    static PdkCrypto s_instance;
    return s_instance;
}

std::string PdkCrypto::base64_encode(const uint8_t* data, size_t len) {
    if (!data || len == 0) return "";
    DWORD size = 0;
    CryptBinaryToStringA(reinterpret_cast<const BYTE*>(data), static_cast<DWORD>(len),
                         CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF, nullptr, &size);
    std::string out(size, '\0');
    CryptBinaryToStringA(reinterpret_cast<const BYTE*>(data), static_cast<DWORD>(len),
                         CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF, &out[0], &size);
    while (!out.empty() && (out.back() == '\0' || out.back() == '\r' || out.back() == '\n')) {
        out.pop_back();
    }
    return out;
}

std::vector<uint8_t> PdkCrypto::base64_decode(const std::string& input) {
    if (input.empty()) return {};
    DWORD size = 0;
    if (!CryptStringToBinaryA(input.c_str(), static_cast<DWORD>(input.size()),
                              CRYPT_STRING_BASE64, nullptr, &size, nullptr, nullptr)) {
        return {};
    }
    std::vector<uint8_t> out(size);
    CryptStringToBinaryA(input.c_str(), static_cast<DWORD>(input.size()),
                         CRYPT_STRING_BASE64, reinterpret_cast<BYTE*>(out.data()), &size, nullptr, nullptr);
    out.resize(size);
    return out;
}

bool PdkCrypto::is_envelope(const std::string& text) {
    if (text.empty()) return false;
    try {
        auto val = json::parse(text, nullptr, false);
        if (!val.is_object()) return false;
        return val.contains("enc") && val.contains("iv") && val.contains("data") && val.contains("kid");
    } catch (...) {
        return false;
    }
}

bool PdkCrypto::fetch_public_config(const std::string& base_url, PublicConfig& out_cfg, std::string& out_err, bool force_refresh) {
    if (has_cached_config_ && !force_refresh && cached_base_url_ == base_url) {
        out_cfg = cached_config_;
        return true;
    }

    std::string url = base_url + "/api/v1/client/config/public";
    HttpClient client;
    HttpResponse resp = client.get(url);
    if (!resp.is_success()) {
        out_err = "拉取客户端公共配置网络错误: " + resp.error_message;
        return false;
    }

    try {
        auto doc = json::parse(resp.body);
        if (doc.value("code", 0) != 200) {
            out_err = "拉取公共配置失败: " + doc.value("message", "未知错误");
            return false;
        }

        auto data = doc.value("data", json::object());
        out_cfg.encryption_mode = data.value("encryptionMode", "optional");
        out_cfg.public_key_pem = data.value("publicKey", "");
        out_cfg.kid = data.value("kid", "v1");
        out_cfg.public_key_fingerprint = data.value("publicKeyFingerprint", "");

        cached_config_ = out_cfg;
        cached_base_url_ = base_url;
        has_cached_config_ = true;
        return true;
    } catch (const std::exception& e) {
        out_err = std::string("解析公共配置异常: ") + e.what();
        return false;
    }
}

bool PdkCrypto::encrypt_body(const std::string& plain_json,
                             const PublicConfig& cfg,
                             std::string& out_envelope_json,
                             std::vector<uint8_t>& out_session_key,
                             std::string& out_err) {
    if (cfg.encryption_mode == "off") {
        out_envelope_json = plain_json;
        return true;
    }

    if (cfg.public_key_pem.empty()) {
        out_err = "服务端未提供 RSA 协议公钥";
        return false;
    }

    // 1. 解码 PEM 公钥到 DER 格式
    DWORD derLen = 0;
    if (!CryptStringToBinaryA(cfg.public_key_pem.c_str(), static_cast<DWORD>(cfg.public_key_pem.size()),
                              CRYPT_STRING_BASE64HEADER, nullptr, &derLen, nullptr, nullptr)) {
        out_err = "Base64 解码 RSA 公钥失败";
        return false;
    }
    std::vector<BYTE> der(derLen);
    CryptStringToBinaryA(cfg.public_key_pem.c_str(), static_cast<DWORD>(cfg.public_key_pem.size()),
                         CRYPT_STRING_BASE64HEADER, der.data(), &derLen, nullptr, nullptr);

    CERT_PUBLIC_KEY_INFO* pInfo = nullptr;
    DWORD infoLen = 0;
    if (!CryptDecodeObjectEx(X509_ASN_ENCODING, X509_PUBLIC_KEY_INFO, der.data(), derLen,
                             CRYPT_DECODE_ALLOC_FLAG, nullptr, &pInfo, &infoLen)) {
        out_err = "CryptDecodeObjectEx 解析 RSA 公钥结构失败";
        return false;
    }

    BCRYPT_KEY_HANDLE hRsaKey = nullptr;
    if (!CryptImportPublicKeyInfoEx2(X509_ASN_ENCODING, pInfo, 0, nullptr, &hRsaKey)) {
        LocalFree(pInfo);
        out_err = "CryptImportPublicKeyInfoEx2 导入 CNG 公钥失败";
        return false;
    }
    LocalFree(pInfo);

    // 2. 生成随机 32 字节 (256-bit) AES 会话密钥与 12 字节 IV
    uint8_t aesKey[32];
    uint8_t iv[12];
    BCryptGenRandom(nullptr, aesKey, sizeof(aesKey), BCRYPT_USE_SYSTEM_PREFERRED_RNG);
    BCryptGenRandom(nullptr, iv, sizeof(iv), BCRYPT_USE_SYSTEM_PREFERRED_RNG);

    out_session_key.assign(aesKey, aesKey + sizeof(aesKey));

    // 3. RSA-OAEP 加密 AES 密钥 (指定 SHA-256 哈希与 MGF1)
    BCRYPT_OAEP_PADDING_INFO paddingInfo = {0};
    paddingInfo.pszAlgId = BCRYPT_SHA256_ALGORITHM;

    DWORD encKeyLen = 0;
    NTSTATUS status = BCryptEncrypt(hRsaKey, aesKey, sizeof(aesKey), &paddingInfo, nullptr, 0,
                                    nullptr, 0, &encKeyLen, BCRYPT_PAD_OAEP);
    if (status < 0) {
        BCryptDestroyKey(hRsaKey);
        out_err = "BCryptEncrypt 计算 RSA-OAEP 密文长度失败";
        return false;
    }
    std::vector<BYTE> encKey(encKeyLen);
    status = BCryptEncrypt(hRsaKey, aesKey, sizeof(aesKey), &paddingInfo, nullptr, 0,
                           encKey.data(), encKeyLen, &encKeyLen, BCRYPT_PAD_OAEP);
    BCryptDestroyKey(hRsaKey);
    if (status < 0) {
        out_err = "BCryptEncrypt RSA-OAEP 加密 AES 密钥失败";
        return false;
    }

    // 4. AES-256-GCM 加密业务报文体
    BCRYPT_ALG_HANDLE hAesAlg = nullptr;
    status = BCryptOpenAlgorithmProvider(&hAesAlg, BCRYPT_AES_ALGORITHM, MS_PRIMITIVE_PROVIDER, 0);
    if (status < 0) {
        out_err = "BCryptOpenAlgorithmProvider 打开 AES 算法提供者失败";
        return false;
    }
    BCryptSetProperty(hAesAlg, BCRYPT_CHAINING_MODE, (PUCHAR)BCRYPT_CHAIN_MODE_GCM, sizeof(BCRYPT_CHAIN_MODE_GCM), 0);

    BCRYPT_KEY_HANDLE hAesKey = nullptr;
    status = BCryptGenerateSymmetricKey(hAesAlg, &hAesKey, nullptr, 0, aesKey, sizeof(aesKey), 0);
    if (status < 0) {
        BCryptCloseAlgorithmProvider(hAesAlg, 0);
        out_err = "BCryptGenerateSymmetricKey 导入 AES 密钥失败";
        return false;
    }

    BYTE tag[16] = {0};
    BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO authInfo;
    BCRYPT_INIT_AUTH_MODE_INFO(authInfo);
    authInfo.pbNonce = iv;
    authInfo.cbNonce = sizeof(iv);
    authInfo.pbTag = tag;
    authInfo.cbTag = sizeof(tag);

    DWORD cipherLen = 0;
    status = BCryptEncrypt(hAesKey, (PUCHAR)plain_json.data(), (ULONG)plain_json.size(),
                           &authInfo, nullptr, 0, nullptr, 0, &cipherLen, 0);
    if (status < 0) {
        BCryptDestroyKey(hAesKey);
        BCryptCloseAlgorithmProvider(hAesAlg, 0);
        out_err = "BCryptEncrypt 计算 AES-GCM 长度失败";
        return false;
    }
    std::vector<BYTE> cipher(cipherLen);
    status = BCryptEncrypt(hAesKey, (PUCHAR)plain_json.data(), (ULONG)plain_json.size(),
                           &authInfo, nullptr, 0, cipher.data(), cipherLen, &cipherLen, 0);
    BCryptDestroyKey(hAesKey);
    BCryptCloseAlgorithmProvider(hAesAlg, 0);

    if (status < 0) {
        out_err = "BCryptEncrypt AES-GCM 加密失败";
        return false;
    }

    // 将 16 字节 tag 追加在密文尾部 (对齐 Java/Python 标准)
    cipher.insert(cipher.end(), tag, tag + sizeof(tag));

    // 5. 组装时间戳与随机字符串 (防重放)
    long long ts = std::chrono::duration_cast<std::chrono::milliseconds>(
                       std::chrono::system_clock::now().time_since_epoch()).count();

    static const char alphanum[] = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    std::mt19937 gen(static_cast<unsigned int>(ts));
    std::uniform_int_distribution<> dis(0, sizeof(alphanum) - 2);
    std::string rnd;
    rnd.reserve(24);
    for (int i = 0; i < 24; ++i) {
        rnd += alphanum[dis(gen)];
    }

    // 6. 生成信封 JSON
    json env = {
        {"kid", cfg.kid},
        {"enc", base64_encode(encKey.data(), static_cast<DWORD>(encKey.size()))},
        {"iv", base64_encode(iv, sizeof(iv))},
        {"data", base64_encode(cipher.data(), static_cast<DWORD>(cipher.size()))},
        {"ts", ts},
        {"rnd", rnd}
    };

    out_envelope_json = env.dump();
    return true;
}

bool PdkCrypto::decrypt_response(const std::string& envelope_json,
                                 const std::vector<uint8_t>& session_key,
                                 std::string& out_plain_json,
                                 std::string& out_err) {
    if (session_key.size() != 32) {
        out_err = "本地缺少对应的 32 字节 AES 会话密钥";
        return false;
    }

    json env;
    try {
        env = json::parse(envelope_json);
    } catch (const std::exception& e) {
        out_err = std::string("解析信封 JSON 失败: ") + e.what();
        return false;
    }

    std::string iv_b64 = env.value("iv", "");
    std::string data_b64 = env.value("data", "");
    if (iv_b64.empty() || data_b64.empty()) {
        out_err = "信封缺少 iv 或 data 密文字段";
        return false;
    }

    std::vector<uint8_t> iv = base64_decode(iv_b64);
    std::vector<uint8_t> data = base64_decode(data_b64);
    if (iv.size() != 12) {
        out_err = "信封 IV 长度无效 (必须为 12 字节)";
        return false;
    }
    if (data.size() < 16) {
        out_err = "密文长度无效 (至少需要 16 字节认证标签)";
        return false;
    }

    // 提取密文尾部的 16 字节 tag
    BYTE tag[16];
    memcpy(tag, data.data() + data.size() - 16, 16);
    ULONG cipher_len = static_cast<ULONG>(data.size() - 16);

    BCRYPT_ALG_HANDLE hAesAlg = nullptr;
    NTSTATUS status = BCryptOpenAlgorithmProvider(&hAesAlg, BCRYPT_AES_ALGORITHM, MS_PRIMITIVE_PROVIDER, 0);
    if (status < 0) {
        out_err = "BCryptOpenAlgorithmProvider 失败";
        return false;
    }
    BCryptSetProperty(hAesAlg, BCRYPT_CHAINING_MODE, (PUCHAR)BCRYPT_CHAIN_MODE_GCM, sizeof(BCRYPT_CHAIN_MODE_GCM), 0);

    BCRYPT_KEY_HANDLE hAesKey = nullptr;
    status = BCryptGenerateSymmetricKey(hAesAlg, &hAesKey, nullptr, 0, const_cast<PUCHAR>(session_key.data()), 32, 0);
    if (status < 0) {
        BCryptCloseAlgorithmProvider(hAesAlg, 0);
        out_err = "BCryptGenerateSymmetricKey 导入解密密钥失败";
        return false;
    }

    BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO authInfo;
    BCRYPT_INIT_AUTH_MODE_INFO(authInfo);
    authInfo.pbNonce = iv.data();
    authInfo.cbNonce = static_cast<ULONG>(iv.size());
    authInfo.pbTag = tag;
    authInfo.cbTag = sizeof(tag);

    DWORD plainLen = 0;
    status = BCryptDecrypt(hAesKey, data.data(), cipher_len, &authInfo, nullptr, 0, nullptr, 0, &plainLen, 0);
    if (status < 0) {
        BCryptDestroyKey(hAesKey);
        BCryptCloseAlgorithmProvider(hAesAlg, 0);
        out_err = "BCryptDecrypt 计算明文长度失败 (可能是秘钥不匹配或数据被篡改)";
        return false;
    }

    std::vector<BYTE> plain(plainLen);
    status = BCryptDecrypt(hAesKey, data.data(), cipher_len, &authInfo, nullptr, 0, plain.data(), plainLen, &plainLen, 0);
    BCryptDestroyKey(hAesKey);
    BCryptCloseAlgorithmProvider(hAesAlg, 0);

    if (status < 0) {
        out_err = "AES-GCM 解密失败 (认证标签校验不通过)";
        return false;
    }

    out_plain_json.assign(reinterpret_cast<char*>(plain.data()), plainLen);
    return true;
}

} // namespace zhibo
