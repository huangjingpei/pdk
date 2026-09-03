#include "platform.hpp"

#include <commdlg.h>
#include <nlohmann/json.hpp>

#include <chrono>
#include <algorithm>
#include <cstdlib>
#include <cwchar>
#include <cwctype>
#include <fstream>
#include <thread>
#include <vector>

namespace pdk {
namespace {

std::wstring quote(const std::wstring& value) {
    std::wstring result = L"\"";
    size_t slashes = 0;
    for (const wchar_t c : value) {
        if (c == L'\\') {
            ++slashes;
        } else if (c == L'\"') {
            result.append(slashes * 2 + 1, L'\\');
            result.push_back(c);
            slashes = 0;
        } else {
            result.append(slashes, L'\\');
            slashes = 0;
            result.push_back(c);
        }
    }
    result.append(slashes * 2, L'\\');
    result.push_back(L'\"');
    return result;
}

bool health_matches(const UpdateJob& job) {
    try {
        std::ifstream input(job.health_file, std::ios::binary);
        if (!input) return false;
        nlohmann::json value;
        input >> value;
        return value.value("nonce", std::string{}) == job.health_nonce &&
               value.value("version", std::string{}) == job.target_version;
    } catch (...) {
        return false;
    }
}

}  // namespace

bool wait_for_process_exit(uint32_t pid, uint32_t timeout_seconds) {
    if (pid == 0) return true;
    HANDLE process = OpenProcess(SYNCHRONIZE, FALSE, pid);
    if (!process) return GetLastError() == ERROR_INVALID_PARAMETER;
    const DWORD result = WaitForSingleObject(process, timeout_seconds * 1000U);
    CloseHandle(process);
    return result == WAIT_OBJECT_0;
}

namespace {

std::wstring lower_extension(const std::filesystem::path& path) {
    std::wstring ext = path.extension().wstring();
    std::transform(ext.begin(), ext.end(), ext.begin(),
                   [](wchar_t c) { return static_cast<wchar_t>(std::towlower(static_cast<std::wint_t>(c))); });
    return ext;
}

// 安全读取宽字符环境变量（替换被标记不安全的 _wgetenv），缺失时返回空串。
std::wstring get_env_w(const wchar_t* name) {
    wchar_t* buffer = nullptr;
    size_t size = 0;
    if (_wdupenv_s(&buffer, &size, name) != 0 || buffer == nullptr) return {};
    std::wstring value(buffer);
    free(buffer);
    return value;
}

bool entry_needs_launcher(const std::filesystem::path& entry) {
    const auto ext = lower_extension(entry);
    return ext != L".exe" && ext != L".com";
}

// 解析非 exe 入口的启动器。优先使用调用方通过环境变量注入的解释器路径，
// 缺失时回退到平台标准启动器（Windows 的 py.exe / cmd.exe / java.exe）。
std::wstring resolve_launcher(const std::filesystem::path& entry) {
    const auto ext = lower_extension(entry);
    if (ext == L".py" || ext == L".pyw") {
        const std::wstring from_env = get_env_w(L"PDK_PYTHON_EXE");
        if (!from_env.empty()) return from_env;
        return L"py.exe";
    }
    if (ext == L".cmd" || ext == L".bat") return L"cmd.exe";
    if (ext == L".jar") {
        const std::wstring from_env = get_env_w(L"PDK_JAVA_EXE");
        if (!from_env.empty()) return from_env;
        return L"java.exe";
    }
    return L"";
}

// 部分启动器必须先接收开关参数才会把后面的路径当作待执行目标：
//   cmd.exe 需要 /c，否则它会进入交互模式并立即退出；
//   java.exe 需要 -jar，否则会把 jar 路径当作主类名去解析。
std::wstring launcher_prefix_args(const std::filesystem::path& entry) {
    const auto ext = lower_extension(entry);
    if (ext == L".cmd" || ext == L".bat") return L"/c ";
    if (ext == L".jar") return L"-jar ";
    return L"";
}

}  // namespace

PROCESS_INFORMATION launch_client(const std::filesystem::path& root, const std::string& entry_point,
                                  const std::filesystem::path* health_file,
                                  const std::string* health_nonce) {
    const auto entry = root / path_from_utf8(entry_point);
    if (!std::filesystem::exists(entry)) throw UpdateError("client entry point is missing");
    if (health_file && health_nonce) {
        SetEnvironmentVariableW(L"PDK_UPDATE_HEALTH_FILE", health_file->c_str());
        SetEnvironmentVariableW(L"PDK_UPDATE_HEALTH_NONCE", widen(*health_nonce).c_str());
    } else {
        SetEnvironmentVariableW(L"PDK_UPDATE_HEALTH_FILE", nullptr);
        SetEnvironmentVariableW(L"PDK_UPDATE_HEALTH_NONCE", nullptr);
    }
    std::wstring command;
    const wchar_t* application_name = nullptr;
    if (entry_needs_launcher(entry)) {
        const std::wstring launcher = resolve_launcher(entry);
        if (launcher.empty()) throw UpdateError("unsupported client entry point type: " + path_to_utf8(entry));
        // 通过命令行启动「解释器 + 脚本」；lpApplicationName 留空，由命令行首 token 决定可执行文件。
        command = quote(launcher) + L" " + launcher_prefix_args(entry) + quote(entry.wstring());
    } else {
        command = quote(entry.wstring());
        application_name = entry.c_str();
    }
    std::vector<wchar_t> mutable_command(command.begin(), command.end());
    mutable_command.push_back(L'\0');
    STARTUPINFOW startup{};
    startup.cb = sizeof(startup);
    PROCESS_INFORMATION process{};
    if (!CreateProcessW(application_name, mutable_command.data(), nullptr, nullptr, FALSE,
                        CREATE_UNICODE_ENVIRONMENT, nullptr, root.c_str(), &startup, &process)) {
        throw UpdateError(windows_error("cannot launch client"));
    }
    return process;
}

bool wait_for_health(PROCESS_INFORMATION& process, const UpdateJob& job) {
    const auto deadline = std::chrono::steady_clock::now() +
                          std::chrono::seconds(job.health_timeout_seconds);
    while (std::chrono::steady_clock::now() < deadline) {
        if (health_matches(job)) return true;
        if (WaitForSingleObject(process.hProcess, 0) == WAIT_OBJECT_0) return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(400));
    }
    return health_matches(job);
}

void terminate_process(PROCESS_INFORMATION& process) {
    if (process.hProcess) {
        if (WaitForSingleObject(process.hProcess, 0) != WAIT_OBJECT_0) {
            TerminateProcess(process.hProcess, 70);
            WaitForSingleObject(process.hProcess, 10000);
        }
        CloseHandle(process.hProcess);
        process.hProcess = nullptr;
    }
    if (process.hThread) {
        CloseHandle(process.hThread);
        process.hThread = nullptr;
    }
}

std::filesystem::path choose_job_file() {
    std::vector<wchar_t> file(32768, L'\0');
    OPENFILENAMEW dialog{};
    dialog.lStructSize = sizeof(dialog);
    dialog.lpstrFile = file.data();
    dialog.nMaxFile = static_cast<DWORD>(file.size());
    dialog.lpstrFilter = L"PDK Updater Job (*.json)\0*.json\0All files (*.*)\0*.*\0";
    dialog.lpstrTitle = L"Select an updater job JSON";
    dialog.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_DONTADDTORECENT;
    if (!GetOpenFileNameW(&dialog)) return {};
    return std::filesystem::path(file.data());
}

void show_result(bool success, const std::string& message) {
    MessageBoxW(nullptr, widen(message).c_str(), success ? L"PDK Updater" : L"PDK Updater Error",
                MB_OK | (success ? MB_ICONINFORMATION : MB_ICONERROR) | MB_SETFOREGROUND);
}

std::string unique_suffix() {
    return std::to_string(GetCurrentProcessId()) + "-" + std::to_string(GetTickCount64());
}

}  // namespace pdk
