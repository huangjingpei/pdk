#pragma once

#include "common.hpp"

#include <filesystem>
#include <string>

namespace pdk {

// 回滚到某个历史备份：校验备份完整性后，将当前安装移为新的 .backup-*，
// 再把所选备份换入 install_root，可选重新启动客户端。
// 仅依赖 platform/common，不引入 crypto / telemetry / zip_install。
int rollback_to(const std::filesystem::path& install_root, const std::string& entry_point,
                bool relaunch_on_rollback, const std::filesystem::path& backup_dir);

// 读 install_root/update-manifest.json 的 version，供 GUI 展示当前版本。
std::string installed_version(const std::filesystem::path& install_root);

// 读备份目录/update-manifest.json 的 version，供 GUI 展示可回滚版本。
std::string backup_version(const std::filesystem::path& backup_dir);

}  // namespace pdk
