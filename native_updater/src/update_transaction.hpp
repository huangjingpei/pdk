#pragma once

#include "job.hpp"

#include <filesystem>

namespace pdk {

// 执行完整升级事务：验签 → 等父进程 → 安全解压 → 备份/原子替换 → 启动 → 健康检查 → 失败回滚。
// 真实端到端验证过的同一套逻辑，CLI 与 GUI 共用。
int run_update(const UpdateJob& job);

// 回滚到某个历史备份：校验备份完整性后，将当前安装移为新的 .backup-*，
// 再把所选备份换入 install_root，可选重新启动客户端。
int rollback_to(const std::filesystem::path& install_root, const std::string& entry_point,
                bool relaunch_on_rollback, const std::filesystem::path& backup_dir);

// 读 install_root/update-manifest.json 的 version，供 GUI 展示当前版本。
std::string installed_version(const std::filesystem::path& install_root);

// 读备份目录/update-manifest.json 的 version，供 GUI 展示可回滚版本。
std::string backup_version(const std::filesystem::path& backup_dir);

}  // namespace pdk
