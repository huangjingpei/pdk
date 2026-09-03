#include "update_transaction.hpp"

#include "crypto.hpp"
#include "platform.hpp"
#include "telemetry.hpp"
#include "zip_install.hpp"
#include "common.hpp"

#include <windows.h>

#include <chrono>
#include <filesystem>
#include <nlohmann/json.hpp>
#include <system_error>
#include <thread>

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

}  // namespace

int run_update(const UpdateJob& job) {
    verify_job_artifact(job);
    if (!wait_for_process_exit(job.parent_pid, 90)) {
        report_event(job, "INSTALL_FAILED", "MAIN_PROCESS_NOT_EXITED");
        throw UpdateError("parent client did not exit within 90 seconds", 65);
    }

    const auto parent = job.install_root.parent_path();
    const auto suffix = unique_suffix();
    const auto stage = parent / (L"." + job.install_root.filename().wstring() + L".update-" + widen(suffix));
    const auto backup = parent / (L"." + job.install_root.filename().wstring() + L".backup-" + widen(suffix));
    const auto failed = parent / (L"." + job.install_root.filename().wstring() + L".failed-" + widen(suffix));
    remove_tree(stage);
    remove_tree(failed);
    std::error_code ignored;
    std::filesystem::remove(job.health_file, ignored);

    bool old_moved = false;
    bool new_installed = false;
    try {
        std::filesystem::create_directories(stage);
        validate_and_extract(job, stage);
        rename_checked(job.install_root, backup, "cannot move current install to backup");
        old_moved = true;
        rename_checked(stage, job.install_root, "cannot activate staged install");
        new_installed = true;

        if (job.require_health) {
            auto process = launch_client(job.install_root, job.entry_point,
                                          &job.health_file, &job.health_nonce);
            if (wait_for_health(process, job)) {
                if (process.hThread) CloseHandle(process.hThread);
                if (process.hProcess) CloseHandle(process.hProcess);
                report_event(job, "INSTALL_SUCCEEDED");
                remove_tree(backup);
                std::filesystem::remove(job.health_file, ignored);
                return 0;
            }
            terminate_process(process);
            rename_checked(job.install_root, failed, "cannot quarantine failed new install");
            new_installed = false;
            rename_checked(backup, job.install_root, "cannot restore previous install");
            old_moved = false;
            if (job.relaunch_on_rollback) {
                auto old_process = launch_client(job.install_root, job.entry_point, nullptr, nullptr);
                if (old_process.hThread) CloseHandle(old_process.hThread);
                if (old_process.hProcess) CloseHandle(old_process.hProcess);
            }
            remove_tree(failed);
            report_event(job, "INSTALL_FAILED", "HEALTH_CHECK_FAILED");
            throw UpdateError("new client failed health check; previous version restored", 70);
        } else {
            // 非强制健康检查：给客户端 3 秒启动窗口，若立即退出才当作失败并回滚。
            auto process = launch_client(job.install_root, job.entry_point,
                                         &job.health_file, &job.health_nonce);
            std::this_thread::sleep_for(std::chrono::seconds(3));
            if (WaitForSingleObject(process.hProcess, 0) == WAIT_OBJECT_0) {
                DWORD code = 0;
                GetExitCodeProcess(process.hProcess, &code);
                terminate_process(process);
                rename_checked(job.install_root, failed, "cannot quarantine failed new install");
                new_installed = false;
                rename_checked(backup, job.install_root, "cannot restore previous install");
                old_moved = false;
                if (job.relaunch_on_rollback) {
                    auto old_process = launch_client(job.install_root, job.entry_point, nullptr, nullptr);
                    if (old_process.hThread) CloseHandle(old_process.hThread);
                    if (old_process.hProcess) CloseHandle(old_process.hProcess);
                }
                remove_tree(failed);
                report_event(job, "INSTALL_FAILED", "CLIENT_EXITED_IMMEDIATELY");
                throw UpdateError("client exited immediately after launch; previous version restored", 70);
            }
            if (process.hThread) CloseHandle(process.hThread);
            if (process.hProcess) CloseHandle(process.hProcess);
            report_event(job, "INSTALL_SUCCEEDED");
            std::filesystem::remove(job.health_file, ignored);
            return 0;  // 保留 backup 作为可回滚版本
        }
    } catch (...) {
        remove_tree(stage);
        if (old_moved) {
            try {
                if (new_installed && std::filesystem::exists(job.install_root)) {
                    rename_checked(job.install_root, failed, "cannot quarantine incomplete install");
                }
                if (!std::filesystem::exists(job.install_root) && std::filesystem::exists(backup)) {
                    rename_checked(backup, job.install_root, "cannot recover current install");
                }
                remove_tree(failed);
            } catch (...) {
                // 保留 backup/failed 目录供人工恢复，不覆盖原始异常。
            }
        }
        throw;
    }
}

}  // namespace pdk
