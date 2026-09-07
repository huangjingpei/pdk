#pragma once

#include <string>
#include <vector>
#include <cstdint>

namespace zhibo {

struct PublicConfig {
    std::string encryption_mode = "optional"; // "force", "optional", "off"
    std::string public_key_pem;
    std::string kid = "v1";
    std::string public_key_fingerprint;
};

class PdkCrypto {
public:
    static PdkCrypto& instance();

    // 判断响应报文是否为 PDK 加密信封
    static bool is_envelope(const std::string& text);

    // 获取并缓存服务端公共配置（公钥、kid、加密模式）
    bool fetch_public_config(const std::string& base_url, PublicConfig& out_cfg, std::string& out_err, bool force_refresh = false);

    // 将明文 JSON 请求报文包装为信封格式，并输出用于解密响应的会话 AES 密钥
    bool encrypt_body(const std::string& plain_json,
                      const PublicConfig& cfg,
                      std::string& out_envelope_json,
                      std::vector<uint8_t>& out_session_key,
                      std::string& out_err);

    // 解密服务端返回的加密信封响应
    bool decrypt_response(const std::string& envelope_json,
                          const std::vector<uint8_t>& session_key,
                          std::string& out_plain_json,
                          std::string& out_err);

    // Base64 辅助工具函数
    static std::string base64_encode(const uint8_t* data, size_t len);
    static std::vector<uint8_t> base64_decode(const std::string& input);

private:
    PdkCrypto() = default;
    ~PdkCrypto() = default;

    PublicConfig cached_config_;
    std::string cached_base_url_;
    bool has_cached_config_ = false;
};

} // namespace zhibo
