#include "zhibo-config.hpp"
#include "utils/json.hpp"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shlobj.h>
#include <fstream>
#include <filesystem>
#include <iostream>

using json = nlohmann::json;
namespace fs = std::filesystem;

namespace zhibo {

ZhiboConfig &ZhiboConfig::instance() {
    static ZhiboConfig cfg;
    return cfg;
}

ZhiboConfig::ZhiboConfig() {
    load();
}

std::string ZhiboConfig::get_config_file_path() const {
    wchar_t appdata[MAX_PATH] = {0};
    if (SUCCEEDED(SHGetFolderPathW(NULL, CSIDL_APPDATA, NULL, 0, appdata))) {
        fs::path dir = fs::path(appdata) / "obs-studio" / "plugin_config" / "obs-zhibo-live";
        std::error_code ec;
        fs::create_directories(dir, ec);
        return (dir / "config.json").string();
    }
    return "zhibo_config.json";
}

void ZhiboConfig::load() {
    std::lock_guard<std::mutex> lock(mutex_);

    // 编译版本约定判定：如果是 Release 版本，则为生产环境 (PRODUCTION)；否则为本地调试环境 (LOCAL_DEBUG)
#if defined(PDK_RELEASE_BUILD) || defined(NDEBUG)
    env_ = PdkEnv::PRODUCTION;
    server_url_ = PROD_SERVER_URL;
    rtmp_base_url_ = PROD_RTMP_BASE_URL;
#else
    env_ = PdkEnv::LOCAL_DEBUG;
    server_url_ = LOCAL_SERVER_URL;
    rtmp_base_url_ = LOCAL_RTMP_BASE_URL;
#endif

    // 优先检查系统环境变量 PDK_BASE_URL (仅供特殊情况下通过系统环境变量手动临时覆盖)
    char *env_base = nullptr;
    size_t env_len = 0;
    if (_dupenv_s(&env_base, &env_len, "PDK_BASE_URL") == 0 && env_base != nullptr) {
        std::string env_url(env_base);
        free(env_base);
        if (!env_url.empty()) {
            if (env_url.find("127.0.0.1") != std::string::npos || env_url.find("localhost") != std::string::npos) {
                env_ = PdkEnv::LOCAL_DEBUG;
                server_url_ = env_url;
                rtmp_base_url_ = LOCAL_RTMP_BASE_URL;
            } else {
                env_ = PdkEnv::PRODUCTION;
                server_url_ = env_url;
                rtmp_base_url_ = PROD_RTMP_BASE_URL;
            }
        }
    }

    std::string path = get_config_file_path();
    if (!fs::exists(path)) {
        return;
    }

    try {
        std::ifstream file(path);
        if (!file.is_open()) return;

        json j;
        file >> j;

        if (j.contains("app_id")) app_id_ = j["app_id"].get<int>();
        if (j.contains("phone")) phone_ = j["phone"].get<std::string>();
        if (j.contains("password")) password_ = j["password"].get<std::string>();
        if (j.contains("card_key")) card_key_ = j["card_key"].get<std::string>();
        if (j.contains("device_id")) device_id_ = j["device_id"].get<std::string>();
        if (j.contains("token_name")) token_name_ = j["token_name"].get<std::string>();
        if (j.contains("token_value")) token_value_ = j["token_value"].get<std::string>();
        if (j.contains("license_status")) license_status_ = j["license_status"].get<std::string>();
        if (j.contains("expire_at")) expire_at_ = j["expire_at"].get<std::string>();
        if (j.contains("remaining_calls")) remaining_calls_ = j["remaining_calls"].get<int>();
        if (j.contains("auto_pull")) auto_pull_ = j["auto_pull"].get<bool>();
        if (j.contains("poll_interval_sec")) poll_interval_sec_ = j["poll_interval_sec"].get<int>();
        if (j.contains("source_name")) source_name_ = j["source_name"].get<std::string>();
        if (j.contains("channel_variant_enabled")) channel_variant_enabled_ = j["channel_variant_enabled"].get<bool>();
        if (j.contains("channel_variant_seed")) channel_variant_seed_ = j["channel_variant_seed"].get<uint32_t>();
    } catch (const std::exception &e) {
        std::cerr << "[ZhiboConfig] 加载配置失败: " << e.what() << std::endl;
    }
}

void ZhiboConfig::save() {
    std::lock_guard<std::mutex> lock(mutex_);
    std::string path = get_config_file_path();

    try {
        json j;
        j["environment"] = (env_ == PdkEnv::LOCAL_DEBUG ? "local" : "production");
        j["server_url"] = server_url_;
        j["rtmp_base_url"] = rtmp_base_url_;
        j["app_id"] = app_id_;
        j["phone"] = phone_;
        j["password"] = password_;
        j["card_key"] = card_key_;
        j["device_id"] = device_id_;
        j["token_name"] = token_name_;
        j["token_value"] = token_value_;
        j["license_status"] = license_status_;
        j["expire_at"] = expire_at_;
        j["remaining_calls"] = remaining_calls_;
        j["auto_pull"] = auto_pull_;
        j["poll_interval_sec"] = poll_interval_sec_;
        j["source_name"] = source_name_;
        j["channel_variant_enabled"] = channel_variant_enabled_;
        j["channel_variant_seed"] = channel_variant_seed_;

        std::ofstream file(path);
        if (file.is_open()) {
            file << j.dump(2);
        }
    } catch (const std::exception &e) {
        std::cerr << "[ZhiboConfig] 保存配置失败: " << e.what() << std::endl;
    }
}

PdkEnv ZhiboConfig::get_environment() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return env_;
}

void ZhiboConfig::set_environment(PdkEnv env) {
    std::lock_guard<std::mutex> lock(mutex_);
    env_ = env;
    if (env == PdkEnv::PRODUCTION) {
        server_url_ = PROD_SERVER_URL;
        rtmp_base_url_ = PROD_RTMP_BASE_URL;
    } else {
        server_url_ = LOCAL_SERVER_URL;
        rtmp_base_url_ = LOCAL_RTMP_BASE_URL;
    }
}

std::string ZhiboConfig::get_server_url() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return server_url_;
}

void ZhiboConfig::set_server_url(const std::string &url) {
    std::lock_guard<std::mutex> lock(mutex_);
    server_url_ = url;
}

std::string ZhiboConfig::get_rtmp_base_url() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return rtmp_base_url_;
}

void ZhiboConfig::set_rtmp_base_url(const std::string &url) {
    std::lock_guard<std::mutex> lock(mutex_);
    rtmp_base_url_ = url;
}

int ZhiboConfig::get_app_id() const {
    return app_id_;
}

std::string ZhiboConfig::get_phone() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return phone_;
}

void ZhiboConfig::set_phone(const std::string &phone) {
    std::lock_guard<std::mutex> lock(mutex_);
    phone_ = phone;
}

std::string ZhiboConfig::get_password() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return password_;
}

void ZhiboConfig::set_password(const std::string &password) {
    std::lock_guard<std::mutex> lock(mutex_);
    password_ = password;
}

std::string ZhiboConfig::get_card_key() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return card_key_;
}

void ZhiboConfig::set_card_key(const std::string &card_key) {
    std::lock_guard<std::mutex> lock(mutex_);
    card_key_ = card_key;
}

std::string ZhiboConfig::get_device_id() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return device_id_;
}

void ZhiboConfig::set_device_id(const std::string &device_id) {
    std::lock_guard<std::mutex> lock(mutex_);
    device_id_ = device_id;
}

std::string ZhiboConfig::get_token_name() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return token_name_;
}

void ZhiboConfig::set_token_name(const std::string &token_name) {
    std::lock_guard<std::mutex> lock(mutex_);
    token_name_ = token_name;
}

std::string ZhiboConfig::get_token_value() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return token_value_;
}

void ZhiboConfig::set_token_value(const std::string &token_value) {
    std::lock_guard<std::mutex> lock(mutex_);
    token_value_ = token_value;
}

std::string ZhiboConfig::get_license_status() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return license_status_;
}

void ZhiboConfig::set_license_status(const std::string &status) {
    std::lock_guard<std::mutex> lock(mutex_);
    license_status_ = status;
}

std::string ZhiboConfig::get_expire_at() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return expire_at_;
}

void ZhiboConfig::set_expire_at(const std::string &expire_at) {
    std::lock_guard<std::mutex> lock(mutex_);
    expire_at_ = expire_at;
}

int ZhiboConfig::get_remaining_calls() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return remaining_calls_;
}

void ZhiboConfig::set_remaining_calls(int calls) {
    std::lock_guard<std::mutex> lock(mutex_);
    remaining_calls_ = calls;
}

bool ZhiboConfig::is_auto_pull() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return auto_pull_;
}

void ZhiboConfig::set_auto_pull(bool auto_pull) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto_pull_ = auto_pull;
}

int ZhiboConfig::get_poll_interval_sec() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return poll_interval_sec_;
}

void ZhiboConfig::set_poll_interval_sec(int sec) {
    std::lock_guard<std::mutex> lock(mutex_);
    poll_interval_sec_ = (sec > 1) ? sec : 5;
}

std::string ZhiboConfig::get_source_name() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return source_name_;
}

void ZhiboConfig::set_source_name(const std::string &name) {
    std::lock_guard<std::mutex> lock(mutex_);
    source_name_ = name;
}

bool ZhiboConfig::is_channel_variant_enabled() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return channel_variant_enabled_;
}

void ZhiboConfig::set_channel_variant_enabled(bool enabled) {
    std::lock_guard<std::mutex> lock(mutex_);
    channel_variant_enabled_ = enabled;
}

uint32_t ZhiboConfig::get_channel_variant_seed() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return channel_variant_seed_;
}

void ZhiboConfig::set_channel_variant_seed(uint32_t seed) {
    std::lock_guard<std::mutex> lock(mutex_);
    channel_variant_seed_ = seed;
}

bool ZhiboConfig::is_configured() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return !phone_.empty() && !password_.empty();
}

bool ZhiboConfig::is_activated() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return !token_value_.empty() && (license_status_ == "ACTIVE" || license_status_ == "SUSPENDED");
}

} // namespace zhibo
