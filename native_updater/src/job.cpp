#include "job.hpp"

#include <nlohmann/json.hpp>

#include <fstream>
#include <regex>

namespace pdk {
namespace {

using json = nlohmann::json;

std::string required_string(const json& source, const char* name) {
    if (!source.contains(name) || !source[name].is_string() || source[name].get<std::string>().empty()) {
        throw UpdateError(std::string("job missing string field: ") + name);
    }
    return source[name].get<std::string>();
}

bool safe_relative_path(const std::string& raw) {
    const auto path = path_from_utf8(raw);
    if (path.empty() || path.is_absolute() || path.has_root_name()) return false;
    for (const auto& part : path) {
        if (part == L".." || part == L".") return false;
    }
    return true;
}

// 从已解析的 JSON 构造 UpdateJob。base_dir 用于将包内相对的 packagePath 解析为绝对路径，
// 这样本地包目录里的 job.json 可以用相对路径引用同目录的 .zip。
UpdateJob load_job_from_json(const json& raw, const std::filesystem::path& base_dir) {
    UpdateJob job;
    try {
        job.schema_version = raw.value("schemaVersion", 0);
        job.package_path = path_from_utf8(required_string(raw, "packagePath"));
        if (!job.package_path.is_absolute()) job.package_path = base_dir / job.package_path;
        job.target_version = required_string(raw, "targetVersion");
        job.entry_point = required_string(raw, "entryPoint");
        job.app_id = raw.at("appId").get<int64_t>();
        job.platform = required_string(raw, "platform");
        job.arch = required_string(raw, "arch");
        job.package_type = required_string(raw, "packageType");
        job.file_size = raw.at("fileSize").get<uint64_t>();
        job.sha256 = required_string(raw, "sha256");
        job.signature = required_string(raw, "signature");
        job.public_key = required_string(raw, "publicKey");
        // 以下为「安装/运行期」字段：本地包清单可能省略，由调用方（GUI）在运行时填充。
        job.parent_pid = raw.value("parentPid", 0U);
        if (raw.contains("installRoot") && raw["installRoot"].is_string() &&
            !raw["installRoot"].get<std::string>().empty()) {
            job.install_root = path_from_utf8(raw["installRoot"].get<std::string>());
        }
        if (raw.contains("healthFile") && raw["healthFile"].is_string() &&
            !raw["healthFile"].get<std::string>().empty()) {
            job.health_file = path_from_utf8(raw["healthFile"].get<std::string>());
        }
        job.health_nonce = raw.contains("healthNonce") && raw["healthNonce"].is_string()
            ? raw["healthNonce"].get<std::string>() : std::string{};
        job.health_timeout_seconds = raw.value("healthTimeoutSeconds", 45U);
        job.relaunch_on_rollback = raw.value("relaunchOnRollback", true);
        job.require_health = raw.value("requireHealthCheck", true);
        if (raw.contains("telemetry") && raw["telemetry"].is_object()) {
            const auto& value = raw["telemetry"];
            TelemetryJob telemetry;
            telemetry.endpoint = required_string(value, "endpoint");
            telemetry.app_id = value.at("appId").get<int64_t>();
            telemetry.device_id = required_string(value, "deviceId");
            telemetry.check_request_id = required_string(value, "checkRequestId");
            telemetry.event_token = required_string(value, "eventToken");
            if (value.contains("artifactId") && !value["artifactId"].is_null()) {
                telemetry.artifact_id = value["artifactId"].get<int64_t>();
            }
            telemetry.from_version = required_string(value, "fromVersion");
            telemetry.target_version = required_string(value, "targetVersion");
            telemetry.platform = required_string(value, "platform");
            job.telemetry = std::move(telemetry);
        }
    } catch (const json::exception& error) {
        throw UpdateError(std::string("updater job field type error: ") + error.what());
    }
    validate_job(job);
    return job;
}

}  // namespace

UpdateJob load_job(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) throw UpdateError("cannot open updater job: " + path_to_utf8(path));
    json raw;
    try {
        input >> raw;
    } catch (const json::exception& error) {
        throw UpdateError(std::string("invalid updater job JSON: ") + error.what());
    }
    return load_job_from_json(raw, path.parent_path());
}

void validate_job(const UpdateJob& job) {
    static const std::regex version_pattern(R"(^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$)");
    static const std::regex sha_pattern(R"(^[0-9a-fA-F]{64}$)");
    if (job.schema_version != 1) throw UpdateError("unsupported updater job schemaVersion");
    if (job.app_id <= 0 || job.file_size == 0) throw UpdateError("invalid appId or fileSize");
    if (!std::regex_match(job.target_version, version_pattern)) throw UpdateError("invalid targetVersion");
    if (!std::regex_match(job.sha256, sha_pattern)) throw UpdateError("invalid SHA-256 value");
    if (job.platform != "WINDOWS" || job.arch != "X64" || job.package_type != "ZIP") {
        throw UpdateError("this updater only accepts WINDOWS/X64/ZIP artifacts");
    }
    if (!safe_relative_path(job.entry_point)) throw UpdateError("entryPoint must be a safe relative path");
    // 安装/运行期字段允许为空：本地包清单省略它们，由 GUI 在构建安装任务时填充。
    if (!job.install_root.empty()) {
        if (!job.install_root.is_absolute()) throw UpdateError("installRoot must be an absolute path");
        if (job.install_root == job.install_root.root_path()) throw UpdateError("refusing to replace a drive root");
    }
    if (!job.health_file.empty() && !job.health_file.is_absolute()) {
        throw UpdateError("healthFile must be an absolute path");
    }
    if (!job.health_nonce.empty() && (job.health_nonce.size() < 16 || job.health_nonce.size() > 128)) {
        throw UpdateError("healthNonce length is invalid");
    }
    if (job.health_timeout_seconds < 10 || job.health_timeout_seconds > 300) {
        throw UpdateError("healthTimeoutSeconds must be between 10 and 300");
    }
    if (job.telemetry && (job.telemetry->app_id != job.app_id ||
                          job.telemetry->target_version != job.target_version)) {
        throw UpdateError("telemetry scope does not match updater job");
    }
}

}  // namespace pdk
