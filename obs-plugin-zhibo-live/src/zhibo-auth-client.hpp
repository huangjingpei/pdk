#pragma once

#include <string>
#include <vector>
#include <memory>
#include "utils/http-client.hpp"

namespace zhibo {

struct AuthResult {
    bool success = false;
    int code = 0;
    std::string message;
    bool need_card_key = false; // 当 code == 40380 时为 true
    std::string phone;
    std::string token_name;
    std::string token_value;
    std::string license_status;
    std::string expire_at;
    int remaining_calls = 0;
};

struct StreamInfo {
    std::string session_no;
    std::string path;
    std::string status; // "ISSUED", "AUTHORIZED", "LIVE", "ENDED"
    std::string media_node_code;
    std::string rtmp_url;
};

class ZhiboAuthClient {
public:
    static ZhiboAuthClient &instance();

    // 获取或自动生成设备硬件唯一标识 (OBS-WIN-xxxx)
    std::string get_or_create_device_id();

    // 客户端登录与激活 (POST /api/v1/client/auth/login)
    AuthResult login(const std::string &phone, const std::string &password, const std::string &card_key = "");

    // 检查/恢复现有会话
    AuthResult check_session();

    // 查询当前账号与许可证下的推流会话 (GET /api/v1/client/zhibo-live/streams/current)
    std::vector<StreamInfo> query_current_streams();

private:
    ZhiboAuthClient();
    ~ZhiboAuthClient() = default;
    ZhiboAuthClient(const ZhiboAuthClient &) = delete;
    ZhiboAuthClient &operator=(const ZhiboAuthClient &) = delete;

    std::map<std::string, std::string> build_auth_headers();

    HttpClient http_;
    std::vector<uint8_t> session_key_;
};

} // namespace zhibo
