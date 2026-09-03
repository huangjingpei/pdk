#pragma once

#include "job.hpp"

#include <filesystem>

namespace pdk {

// GUI 升级器管理的「安装档案」：告诉 GUI 要管理哪个安装、信任哪个公钥、
// 去哪个目录找可安装的本地版本包。对应 updater-gui.json。
struct GuiProfile {
    int64_t app_id{};
    std::string platform = "WINDOWS";
    std::string arch = "X64";
    std::string package_type = "ZIP";
    std::filesystem::path install_root;
    std::string entry_point;
    std::string public_key;
    std::filesystem::path packages_dir;
    uint32_t health_timeout_seconds = 60;
    bool relaunch_on_rollback = true;
    bool require_health = true;
};

GuiProfile load_gui_profile(const std::filesystem::path& path);

}  // namespace pdk
