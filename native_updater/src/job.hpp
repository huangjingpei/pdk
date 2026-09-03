#pragma once

#include "common.hpp"

#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>

namespace pdk {

struct TelemetryJob {
    std::string endpoint;
    int64_t app_id{};
    std::string device_id;
    std::string check_request_id;
    std::string event_token;
    std::optional<int64_t> artifact_id;
    std::string from_version;
    std::string target_version;
    std::string platform;
};

struct UpdateJob {
    int schema_version{1};
    std::filesystem::path package_path;
    std::filesystem::path install_root;
    std::string target_version;
    std::string entry_point;
    int64_t app_id{};
    std::string platform;
    std::string arch;
    std::string package_type;
    uint64_t file_size{};
    std::string sha256;
    std::string signature;
    std::string public_key;
    uint32_t parent_pid{};
    std::filesystem::path health_file;
    std::string health_nonce;
    uint32_t health_timeout_seconds{45};
    bool relaunch_on_rollback{true};
    bool require_health{true};
    std::optional<TelemetryJob> telemetry;
};

UpdateJob load_job(const std::filesystem::path& path);
void validate_job(const UpdateJob& job);

}  // namespace pdk
