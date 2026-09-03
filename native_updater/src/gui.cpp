#include "gui_config.hpp"
#include "update_transaction.hpp"
#include "rollback.hpp"
#include "platform.hpp"
#include "common.hpp"

#include <windows.h>
#include <commdlg.h>
#include <shellapi.h>
#include <bcrypt.h>

#include <nlohmann/json.hpp>

#include <filesystem>
#include <string>
#include <vector>
#include <thread>
#include <sstream>
#include <iomanip>

namespace fs = std::filesystem;

namespace {

constexpr int IDC_CURRENT = 1001;
constexpr int IDC_INSTALL_LIST = 1002;
constexpr int IDC_INSTALL_BTN = 1003;
constexpr int IDC_BROWSE_BTN = 1004;
constexpr int IDC_ROLLBACK_LIST = 1005;
constexpr int IDC_ROLLBACK_BTN = 1006;
constexpr int IDC_REFRESH_BTN = 1007;
constexpr int IDC_STATUS = 1008;
constexpr int IDC_EXIT_BTN = 1009;

constexpr UINT WM_APP_LOG = WM_APP + 1;
constexpr UINT WM_APP_DONE = WM_APP + 2;

// 子控件 ID（int）转 HMENU：先提升为指针宽度再 reinterpret，消除 64 位截断警告。
inline HMENU as_menu(int id) {
    return reinterpret_cast<HMENU>(static_cast<INT_PTR>(id));
}

struct PackageEntry {
    fs::path job_path;
    std::string version;
    std::string label;
};

struct BackupEntry {
    fs::path dir;
    std::string version;
    std::string label;
};

struct GuiState {
    pdk::GuiProfile profile;
    HWND hwnd = nullptr;
    HWND current_ver = nullptr;
    HWND install_list = nullptr;
    HWND rollback_list = nullptr;
    HWND status_edit = nullptr;
    HWND install_btn = nullptr;
    HWND browse_btn = nullptr;
    HWND rollback_btn = nullptr;
    HWND refresh_btn = nullptr;
    HWND exit_btn = nullptr;
    std::vector<PackageEntry> packages;
    std::vector<BackupEntry> backups;
    bool busy = false;
};

GuiState state;

// ---- 工具函数 ----

std::string random_hex(size_t bytes) {
    std::vector<unsigned char> buffer(bytes);
    if (BCryptGenRandom(nullptr, buffer.data(), static_cast<ULONG>(buffer.size()),
                         BCRYPT_USE_SYSTEM_PREFERRED_RNG) < 0) {
        // 极少见的回退路径：用时间熵填充，仅用于 healthNonce，不用于密钥。
        srand(static_cast<unsigned>(GetTickCount64()));
        for (auto& b : buffer) b = static_cast<unsigned char>(rand() & 0xFF);
    }
    std::ostringstream out;
    out << std::hex << std::setfill('0');
    for (const auto b : buffer) out << std::setw(2) << static_cast<int>(b);
    return out.str();
}

void append_log(const std::wstring& line) {
    if (!state.status_edit) return;
    const int len = GetWindowTextLengthW(state.status_edit);
    std::wstring existing(static_cast<size_t>(len) + 1, L'\0');
    GetWindowTextW(state.status_edit, existing.data(), len + 1);
    existing.resize(static_cast<size_t>(len));
    existing += line + L"\r\n";
    SetWindowTextW(state.status_edit, existing.c_str());
    const DWORD end = static_cast<DWORD>(existing.size());
    SendMessageW(state.status_edit, EM_SETSEL, end, end);
    SendMessageW(state.status_edit, EM_SCROLLCARET, 0, 0);
}

void post_log(const std::wstring& line) {
    const size_t n = line.size() + 1;
    auto* buffer = new wchar_t[n];
    wcscpy_s(buffer, n, line.c_str());
    // 投递失败（窗口已销毁或消息队列不可用）时必须就地释放，否则缓冲泄漏。
    if (!PostMessageW(state.hwnd, WM_APP_LOG, 0, reinterpret_cast<LPARAM>(buffer))) delete[] buffer;
}

// 升级事务进行中禁止关闭：工作线程被强杀会让 install_root 停在半成品状态。
bool request_close(HWND hwnd) {
    if (state.busy) {
        MessageBoxW(hwnd, L"升级事务正在进行中。\r\n现在关闭会中断安装，可能导致客户端目录无法启动。\r\n请等待本次操作完成。",
                    L"PDK 客户端升级器", MB_ICONWARNING | MB_OK);
        return false;
    }
    DestroyWindow(hwnd);
    return true;
}

void enable_controls(bool enabled) {
    if (state.install_btn) EnableWindow(state.install_btn, enabled ? TRUE : FALSE);
    if (state.browse_btn) EnableWindow(state.browse_btn, enabled ? TRUE : FALSE);
    if (state.rollback_btn) EnableWindow(state.rollback_btn, enabled ? TRUE : FALSE);
    if (state.refresh_btn) EnableWindow(state.refresh_btn, enabled ? TRUE : FALSE);
}

// 由 GUI 档案 + 选中的本地包 job.json 构造一次完整的安装任务。
pdk::UpdateJob build_install_job(const pdk::GuiProfile& profile, const fs::path& job_path) {
    auto job = pdk::load_job(job_path);
    // 安装/运行期字段以 GUI 档案为准，覆盖包内自述值。
    job.install_root = profile.install_root;
    job.entry_point = profile.entry_point;
    job.app_id = profile.app_id;
    job.platform = profile.platform;
    job.arch = profile.arch;
    job.package_type = profile.package_type;
    job.public_key = profile.public_key;
    job.parent_pid = 0;
    job.health_timeout_seconds = profile.health_timeout_seconds;
    job.relaunch_on_rollback = profile.relaunch_on_rollback;
    job.require_health = profile.require_health;
    job.telemetry.reset();
    const auto parent = profile.install_root.parent_path();
    const auto name = profile.install_root.filename().wstring();
    job.health_file = parent / (L"." + name + L".health-" + pdk::widen(pdk::unique_suffix()));
    job.health_nonce = random_hex(24);
    return job;
}

// ---- 列表刷新 ----

void refresh_lists() {
    if (!state.hwnd) return;
    const std::wstring cv = pdk::widen(pdk::installed_version(state.profile.install_root));
    SetWindowTextW(state.current_ver, (std::wstring(L"当前安装版本: ") + cv).c_str());

    state.packages.clear();
    SendMessageW(state.install_list, LB_RESETCONTENT, 0, 0);
    if (!state.profile.packages_dir.empty() && fs::exists(state.profile.packages_dir)) {
        std::error_code ignored;
        try {
            for (auto it = fs::recursive_directory_iterator(state.profile.packages_dir);
                 it != fs::recursive_directory_iterator(); it.increment(ignored)) {
                if (ignored) { ignored.clear(); continue; }
                const auto& entry = *it;
                if (!entry.is_regular_file() ||
                    entry.path().extension().wstring() != L".json") continue;
                try {
                    const auto job = pdk::load_job(entry.path());
                    PackageEntry e;
                    e.job_path = entry.path();
                    e.version = job.target_version;
                    e.label = job.target_version + "  (" + pdk::path_to_utf8(entry.path().filename()) + ")";
                    state.packages.push_back(e);
                    SendMessageW(state.install_list, LB_ADDSTRING, 0,
                                 reinterpret_cast<LPARAM>(pdk::widen(e.label).c_str()));
                } catch (...) {
                    // 非法的 job.json 跳过，不阻塞列表。
                }
            }
        } catch (...) {
            // 包目录无法遍历时静默跳过。
        }
    }

    state.backups.clear();
    SendMessageW(state.rollback_list, LB_RESETCONTENT, 0, 0);
    const auto parent = state.profile.install_root.parent_path();
    const std::wstring name = state.profile.install_root.filename().wstring();
    const std::wstring prefix = L"." + name + L".backup-";
    if (fs::exists(parent)) {
        std::error_code ignored;
        for (auto it = fs::directory_iterator(parent, ignored);
             it != fs::directory_iterator(); ++it) {
            if (ignored) break;
            const auto& d = *it;
            if (!d.is_directory()) continue;
            const std::wstring fname = d.path().filename().wstring();
            if (fname.rfind(prefix, 0) != 0) continue;
            try {
                const std::string ver = pdk::backup_version(d.path());
                BackupEntry e;
                e.dir = d.path();
                e.version = ver;
                e.label = ver + "  (" + pdk::path_to_utf8(d.path().filename()) + ")";
                state.backups.push_back(e);
                SendMessageW(state.rollback_list, LB_ADDSTRING, 0,
                             reinterpret_cast<LPARAM>(pdk::widen(e.label).c_str()));
            } catch (...) {
                // 无法读取版本的备份跳过。
            }
        }
    }
}

// ---- 任务执行（工作线程） ----

void run_install(const fs::path& job_path) {
    try {
        const auto job = build_install_job(state.profile, job_path);
        post_log(std::wstring(L"目标版本: ") + pdk::widen(job.target_version));
        post_log(L"校验签名与解压中…");
        pdk::run_update(job);
        post_log(std::wstring(L"✅ 安装成功: ") + pdk::widen(job.target_version));
    } catch (const pdk::UpdateError& e) {
        post_log(std::wstring(L"❌ 安装失败: ") + pdk::widen(std::string(e.what())));
    } catch (const std::exception& e) {
        post_log(std::wstring(L"❌ 安装异常: ") + pdk::widen(std::string(e.what())));
    }
    PostMessageW(state.hwnd, WM_APP_DONE, 0, 0);
}

void run_rollback(const fs::path& backup_dir) {
    try {
        post_log(std::wstring(L"回滚到: ") + pdk::widen(pdk::path_to_utf8(backup_dir.filename())));
        pdk::rollback_to(state.profile.install_root, state.profile.entry_point,
                         state.profile.relaunch_on_rollback, backup_dir);
        post_log(L"✅ 回滚成功");
    } catch (const pdk::UpdateError& e) {
        post_log(std::wstring(L"❌ 回滚失败: ") + pdk::widen(std::string(e.what())));
    } catch (const std::exception& e) {
        post_log(std::wstring(L"❌ 回滚异常: ") + pdk::widen(std::string(e.what())));
    }
    PostMessageW(state.hwnd, WM_APP_DONE, 0, 0);
}

void start_install(const fs::path& job_path) {
    if (state.busy) { post_log(L"已有任务进行中，请稍候"); return; }
    state.busy = true;
    enable_controls(false);
    post_log(L"=== 开始安装 ===");
    std::thread(run_install, job_path).detach();
}

void start_rollback(const fs::path& backup_dir) {
    if (state.busy) { post_log(L"已有任务进行中，请稍候"); return; }
    state.busy = true;
    enable_controls(false);
    post_log(L"=== 开始回滚 ===");
    std::thread(run_rollback, backup_dir).detach();
}

// ---- 控件事件 ----

void on_install() {
    const int sel = static_cast<int>(SendMessageW(state.install_list, LB_GETCURSEL, 0, 0));
    if (sel == LB_ERR || sel < 0 || sel >= static_cast<int>(state.packages.size())) {
        post_log(L"请先在「可安装版本」里选择一个版本");
        return;
    }
    start_install(state.packages[static_cast<size_t>(sel)].job_path);
}

void on_rollback() {
    const int sel = static_cast<int>(SendMessageW(state.rollback_list, LB_GETCURSEL, 0, 0));
    if (sel == LB_ERR || sel < 0 || sel >= static_cast<int>(state.backups.size())) {
        post_log(L"请先在「历史备份」里选择一个备份");
        return;
    }
    start_rollback(state.backups[static_cast<size_t>(sel)].dir);
}

void on_browse() {
    wchar_t file[32768]{};
    OPENFILENAMEW ofn{};
    ofn.lStructSize = sizeof(ofn);
    ofn.lpstrFile = file;
    ofn.nMaxFile = static_cast<DWORD>(std::size(file));
    ofn.lpstrFilter = L"Updater Job (*.json)\0*.json\0All files (*.*)\0*.*\0";
    ofn.lpstrTitle = L"选择版本包 job.json";
    ofn.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_DONTADDTORECENT;
    if (!GetOpenFileNameW(&ofn)) return;
    const fs::path path(file);
    try {
        const auto job = pdk::load_job(path);
        PackageEntry e;
        e.job_path = path;
        e.version = job.target_version;
        e.label = job.target_version + "  (浏览: " + pdk::path_to_utf8(path.filename()) + ")";
        state.packages.push_back(e);
        SendMessageW(state.install_list, LB_ADDSTRING, 0,
                     reinterpret_cast<LPARAM>(pdk::widen(e.label).c_str()));
        post_log(std::wstring(L"已加入待安装版本: ") + pdk::widen(job.target_version));
    } catch (const std::exception& e) {
        post_log(std::wstring(L"无法加载该 job: ") + pdk::widen(std::string(e.what())));
    }
}

// ---- 窗口过程 ----

LRESULT CALLBACK gui_proc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
        case WM_CREATE: {
            state.hwnd = hwnd;
            const HINSTANCE h = GetModuleHandleW(nullptr);
            const auto font = reinterpret_cast<HFONT>(GetStockObject(DEFAULT_GUI_FONT));

            state.current_ver = CreateWindowW(L"STATIC", L"当前安装版本: 检测中…",
                WS_CHILD | WS_VISIBLE | SS_LEFT, 12, 12, 520, 22, hwnd,
                as_menu(IDC_CURRENT), h, nullptr);

            CreateWindowW(L"BUTTON", L"可安装版本（本地包目录）",
                WS_CHILD | WS_VISIBLE | BS_GROUPBOX, 12, 44, 524, 210, hwnd, nullptr, h, nullptr);
            state.install_list = CreateWindowW(L"LISTBOX", nullptr,
                WS_CHILD | WS_VISIBLE | WS_BORDER | LBS_NOTIFY | LBS_HASSTRINGS | WS_VSCROLL,
                24, 64, 500, 130, hwnd, as_menu(IDC_INSTALL_LIST), h, nullptr);
            state.install_btn = CreateWindowW(L"BUTTON", L"安装所选版本",
                WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON, 24, 204, 170, 30, hwnd,
                as_menu(IDC_INSTALL_BTN), h, nullptr);
            state.browse_btn = CreateWindowW(L"BUTTON", L"浏览包…",
                WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON, 210, 204, 140, 30, hwnd,
                as_menu(IDC_BROWSE_BTN), h, nullptr);

            CreateWindowW(L"BUTTON", L"历史备份（可回滚）",
                WS_CHILD | WS_VISIBLE | BS_GROUPBOX, 12, 270, 524, 190, hwnd, nullptr, h, nullptr);
            state.rollback_list = CreateWindowW(L"LISTBOX", nullptr,
                WS_CHILD | WS_VISIBLE | WS_BORDER | LBS_NOTIFY | LBS_HASSTRINGS | WS_VSCROLL,
                24, 290, 500, 110, hwnd, as_menu(IDC_ROLLBACK_LIST), h, nullptr);
            state.rollback_btn = CreateWindowW(L"BUTTON", L"回滚到所选备份",
                WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON, 24, 412, 170, 30, hwnd,
                as_menu(IDC_ROLLBACK_BTN), h, nullptr);
            state.refresh_btn = CreateWindowW(L"BUTTON", L"刷新",
                WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON, 210, 412, 120, 30, hwnd,
                as_menu(IDC_REFRESH_BTN), h, nullptr);

            state.status_edit = CreateWindowW(L"EDIT", nullptr,
                WS_CHILD | WS_VISIBLE | WS_BORDER | ES_MULTILINE | ES_READONLY | WS_VSCROLL | ES_AUTOVSCROLL,
                12, 474, 524, 120, hwnd, as_menu(IDC_STATUS), h, nullptr);
            state.exit_btn = CreateWindowW(L"BUTTON", L"退出",
                WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON, 446, 606, 90, 30, hwnd,
                as_menu(IDC_EXIT_BTN), h, nullptr);

            for (const auto ctrl : {state.current_ver, state.install_list, state.install_btn,
                                    state.browse_btn, state.rollback_list, state.rollback_btn,
                                    state.refresh_btn, state.status_edit, state.exit_btn}) {
                if (ctrl) SendMessageW(ctrl, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
            }
            refresh_lists();
            return 0;
        }
        case WM_COMMAND: {
            const int id = LOWORD(wParam);
            if (id == IDC_INSTALL_BTN || (id == IDC_INSTALL_LIST && HIWORD(wParam) == LBN_DBLCLK)) {
                on_install();
            } else if (id == IDC_BROWSE_BTN) {
                on_browse();
            } else if (id == IDC_ROLLBACK_BTN || (id == IDC_ROLLBACK_LIST && HIWORD(wParam) == LBN_DBLCLK)) {
                on_rollback();
            } else if (id == IDC_REFRESH_BTN) {
                refresh_lists();
            } else if (id == IDC_EXIT_BTN) {
                request_close(hwnd);
            }
            return 0;
        }
        case WM_APP_LOG: {
            auto* text = reinterpret_cast<wchar_t*>(lParam);
            append_log(std::wstring(text));
            delete[] text;
            return 0;
        }
        case WM_APP_DONE: {
            state.busy = false;
            enable_controls(true);
            refresh_lists();
            return 0;
        }
        case WM_CLOSE:
            request_close(hwnd);
            return 0;
        case WM_DESTROY:
            PostQuitMessage(0);
            return 0;
        default:
            return DefWindowProcW(hwnd, msg, wParam, lParam);
    }
}

}  // namespace

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE, PWSTR lpCmdLine, int nCmdShow) {
    (void)nCmdShow;
    fs::path config_path;
    {
        int argc = 0;
        LPWSTR* argv = CommandLineToArgvW(lpCmdLine, &argc);
        for (int i = 0; i < argc; ++i) {
            if (wcscmp(argv[i], L"--config") == 0 && i + 1 < argc) {
                config_path = argv[i + 1];
            }
        }
        if (argv) LocalFree(argv);
    }
    if (config_path.empty()) {
        wchar_t module_path[32768]{};
        GetModuleFileNameW(nullptr, module_path, static_cast<DWORD>(std::size(module_path)));
        config_path = fs::path(module_path).parent_path() / L"updater-gui.json";
    }

    try {
        state.profile = pdk::load_gui_profile(config_path);
    } catch (const std::exception& e) {
        MessageBoxW(nullptr, pdk::widen(std::string("配置加载失败: ") + e.what()).c_str(),
                    L"PDK 客户端升级器", MB_ICONERROR);
        return 1;
    }

    WNDCLASSW wc{};
    wc.lpfnWndProc = gui_proc;
    wc.hInstance = hInstance;
    wc.lpszClassName = L"PdkUpdaterGui";
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_BTNFACE + 1);
    if (!RegisterClassW(&wc)) {
        MessageBoxW(nullptr, L"无法注册窗口类", L"PDK 客户端升级器", MB_ICONERROR);
        return 1;
    }

    const HWND hwnd = CreateWindowExW(
        0, L"PdkUpdaterGui", L"PDK 客户端升级器", WS_OVERLAPPEDWINDOW,
        CW_USEDEFAULT, CW_USEDEFAULT, 564, 680, nullptr, nullptr, hInstance, nullptr);
    if (!hwnd) return 1;
    ShowWindow(hwnd, SW_SHOW);
    UpdateWindow(hwnd);

    MSG msg{};
    while (GetMessageW(&msg, nullptr, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    return static_cast<int>(msg.wParam);
}
