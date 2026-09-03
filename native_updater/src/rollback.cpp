#include "rollback.hpp"

#include "platform.hpp"

#include <windows.h>

#include <filesystem>
#include <fstream>
#include <nlohmann/json.hpp>
#include <system_error>

namespace pdk {
namespace {

void remove_tree(const std::filesystem::path& path) noexcept {
    std::error_code ignored;
    std::filesystem::remove_all(path, ignored);
}

void rename_checked(const std::filesystem::path& from, const std::filesystem::path& to,
                    const char* operation) {
    std::error_code error;
    std::filesystem::rename(from, to, error);
    if (error) throw UpdateError(std::string(operation) + ": " + error.message());
}

nlohmann::json read_manifest(const std::filesystem::path& root) {
    const auto path = root / path_from_utf8("update-manifest.json");
    std::ifstream input(path, std::ios::binary);
    if (!input) throw UpdateError("missing update-manifest.json in " + path_to_utf8(root));
    nlohmann::json value;
    input >> value;
    return value;
}

}  // namespace

std::string installed_version(const std::filesystem::path& install_root) {
    try {
        return read_manifest(install_root).value("version", std::string("未知"));
    } catch (...) {
        return "未知（无 update-manifest.json）";
    }
}

std::string backup_version(const std::filesystem::path& backup_dir) {
    try {
        return read_manifest(backup_dir).value("version", std::string("未知"));
    } catch (...) {
        return "未知";
    }
}

int rollback_to(const std::filesystem::path& install_root, const std::string& entry_point,
                bool relaunch_on_rollback, const std::filesystem::path& backup_dir) {
    if (!std::filesystem::is_directory(backup_dir)) throw UpdateError("backup directory does not exist");
    if (!std::filesystem::is_regular_file(backup_dir / path_from_utf8("update-manifest.json"))) {
        throw UpdateError("selected backup is missing update-manifest.json");
    }
    if (!std::filesystem::exists(backup_dir / path_from_utf8(entry_point))) {
        throw UpdateError("selected backup is missing the client entry point");
    }
    if (!std::filesystem::exists(install_root)) throw UpdateError("current install directory does not exist");

    const auto parent = install_root.parent_path();
    const auto name = install_root.filename().wstring();
    const auto suffix = widen(unique_suffix());
    const auto new_backup = parent / (L"." + name + L".backup-" + suffix);
    const auto failed = parent / (L"." + name + L".failed-" + suffix);
    remove_tree(failed);
    try {
        rename_checked(install_root, new_backup, "cannot move current install to backup");
    } catch (...) {
        throw;
    }
    try {
        rename_checked(backup_dir, install_root, "cannot activate selected backup");
    } catch (...) {
        rename_checked(new_backup, install_root, "cannot restore current install after rollback failure");
        throw;
    }
    // 回滚成功：当前版本变成新的 .backup-*，可被再次回滚；所选备份成为活动安装。
    if (relaunch_on_rollback) {
        try {
            auto process = launch_client(install_root, entry_point, nullptr, nullptr);
            if (process.hThread) CloseHandle(process.hThread);
            if (process.hProcess) CloseHandle(process.hProcess);
        } catch (...) {
            // 回滚本身已成功，重启动失败不致命。
        }
    }
    return 0;
}

}  // namespace pdk
