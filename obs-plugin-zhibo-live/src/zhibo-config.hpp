#pragma once

#include <string>
#include <mutex>

namespace zhibo {

enum class PdkEnv {
    PRODUCTION = 0,
    LOCAL_DEBUG = 1
};

class ZhiboConfig {
public:
    static ZhiboConfig &instance();

    static constexpr const char *PROD_SERVER_URL = "https://pdk.graddu.com";
    static constexpr const char *PROD_RTMP_BASE_URL = "rtmp://43.248.187.207:1935";

    static constexpr const char *LOCAL_SERVER_URL = "http://127.0.0.1:8080";
    static constexpr const char *LOCAL_RTMP_BASE_URL = "rtmp://127.0.0.1:1935";

    void load();
    void save();

    // 运行环境与预设地址约定（无需用户手动输入地址）
    PdkEnv get_environment() const;
    void set_environment(PdkEnv env);

    std::string get_server_url() const;
    void set_server_url(const std::string &url);

    std::string get_rtmp_base_url() const;
    void set_rtmp_base_url(const std::string &url);

    int get_app_id() const;

    // 凭据与设备
    std::string get_phone() const;
    void set_phone(const std::string &phone);

    std::string get_password() const;
    void set_password(const std::string &password);

    std::string get_card_key() const;
    void set_card_key(const std::string &card_key);

    std::string get_device_id() const;
    void set_device_id(const std::string &device_id);

    // 会话令牌
    std::string get_token_name() const;
    void set_token_name(const std::string &token_name);

    std::string get_token_value() const;
    void set_token_value(const std::string &token_value);

    // 许可证快照
    std::string get_license_status() const;
    void set_license_status(const std::string &status);

    std::string get_expire_at() const;
    void set_expire_at(const std::string &expire_at);

    int get_remaining_calls() const;
    void set_remaining_calls(int calls);

    // 运行行为
    bool is_auto_pull() const;
    void set_auto_pull(bool auto_pull);

    int get_poll_interval_sec() const;
    void set_poll_interval_sec(int sec);

    std::string get_source_name() const;
    void set_source_name(const std::string &name);

    // 渠道去重变异 (Channel Variant)
    bool is_channel_variant_enabled() const;
    void set_channel_variant_enabled(bool enabled);

    uint32_t get_channel_variant_seed() const;
    void set_channel_variant_seed(uint32_t seed);

    bool is_configured() const;
    bool is_activated() const;

private:
    ZhiboConfig();
    ~ZhiboConfig() = default;
    ZhiboConfig(const ZhiboConfig &) = delete;
    ZhiboConfig &operator=(const ZhiboConfig &) = delete;

    std::string get_config_file_path() const;

    mutable std::mutex mutex_;
#if defined(PDK_RELEASE_BUILD) || defined(NDEBUG)
    PdkEnv env_ = PdkEnv::PRODUCTION;
    std::string server_url_ = PROD_SERVER_URL;
    std::string rtmp_base_url_ = PROD_RTMP_BASE_URL;
#else
    PdkEnv env_ = PdkEnv::LOCAL_DEBUG;
    std::string server_url_ = LOCAL_SERVER_URL;
    std::string rtmp_base_url_ = LOCAL_RTMP_BASE_URL;
#endif
    int app_id_ = 3;

    std::string phone_;
    std::string password_;
    std::string card_key_;
    std::string device_id_;

    std::string token_name_ = "satoken";
    std::string token_value_;

    std::string license_status_ = "UNACTIVATED";
    std::string expire_at_;
    int remaining_calls_ = 0;

    bool auto_pull_ = true;
    int poll_interval_sec_ = 5;
    std::string source_name_ = "智播拉流源";

    bool channel_variant_enabled_ = true;
    uint32_t channel_variant_seed_ = 0;
};

} // namespace zhibo
