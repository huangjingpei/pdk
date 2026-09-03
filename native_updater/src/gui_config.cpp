#include "gui_config.hpp"

#include "common.hpp"

#include <nlohmann/json.hpp>

#include <fstream>
#include <stdexcept>

namespace pdk {
namespace {

std::string required_string(const nlohmann::json& source, const char* name) {
    if (!source.contains(name) || !source[name].is_string() ||
        source[name].get<std::string>().empty()) {
        throw UpdateError(std::string("profile missing string field: ") + name);
    }
    return source[name].get<std::string>();
}

}  // namespace

GuiProfile load_gui_profile(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) throw UpdateError("cannot open GUI profile: " + path_to_utf8(path));
    nlohmann::json raw;
    try {
        input >> raw;
    } catch (const nlohmann::json::exception& error) {
        throw UpdateError(std::string("invalid GUI profile JSON: ") + error.what());
    }
    GuiProfile profile;
    try {
        profile.app_id = raw.at("appId").get<int64_t>();
        profile.install_root = path_from_utf8(required_string(raw, "installRoot"));
        profile.entry_point = required_string(raw, "entryPoint");
        profile.public_key = required_string(raw, "publicKey");
        profile.platform = raw.value("platform", "WINDOWS");
        profile.arch = raw.value("arch", "X64");
        profile.package_type = raw.value("packageType", "ZIP");
        if (raw.contains("packagesDir") && raw["packagesDir"].is_string() &&
            !raw["packagesDir"].get<std::string>().empty()) {
            profile.packages_dir = path_from_utf8(raw["packagesDir"].get<std::string>());
        }
        profile.health_timeout_seconds = raw.value("healthTimeoutSeconds", 60U);
        profile.relaunch_on_rollback = raw.value("relaunchOnRollback", true);
        profile.require_health = raw.value("requireHealthCheck", true);
    } catch (const nlohmann::json::exception& error) {
        throw UpdateError(std::string("GUI profile field type error: ") + error.what());
    }
    if (profile.app_id <= 0) throw UpdateError("invalid appId in GUI profile");
    if (!profile.install_root.is_absolute()) throw UpdateError("installRoot must be absolute");
    if (profile.platform != "WINDOWS" || profile.arch != "X64" || profile.package_type != "ZIP") {
        throw UpdateError("GUI profile only supports WINDOWS/X64/ZIP");
    }
    return profile;
}

}  // namespace pdk
