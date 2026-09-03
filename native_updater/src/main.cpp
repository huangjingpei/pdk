#include "crypto.hpp"
#include "job.hpp"
#include "platform.hpp"
#include "telemetry.hpp"
#include "update_transaction.hpp"
#include "zip_install.hpp"

#include <windows.h>

#include <filesystem>
#include <iostream>
#include <string>
#include <system_error>

namespace {

struct Options {
    std::filesystem::path job_path;
    bool interactive{false};
    bool quiet{false};
};

Options parse_options(int argc, wchar_t** argv) {
    Options options;
    for (int i = 1; i < argc; ++i) {
        const std::wstring arg = argv[i];
        if (arg == L"--job" && i + 1 < argc) options.job_path = argv[++i];
        else if (arg == L"--interactive") options.interactive = true;
        else if (arg == L"--quiet") options.quiet = true;
        else if (arg == L"--version") {
            std::cout << "pdk_updater " << PDK_UPDATER_VERSION << '\n';
            std::exit(0);
        } else if (arg == L"--help" || arg == L"-h") {
            std::cout << "Usage: pdk_updater.exe --job <absolute-job.json> [--quiet]\n"
                         "       pdk_updater.exe --interactive\n";
            std::exit(0);
        } else {
            throw pdk::UpdateError("unknown or incomplete command line option");
        }
    }
    if (argc == 1) options.interactive = true;
    if (options.interactive && options.job_path.empty()) options.job_path = pdk::choose_job_file();
    if (options.job_path.empty()) throw pdk::UpdateError("no updater job selected", 64);
    return options;
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
    SetConsoleOutputCP(CP_UTF8);
    bool interactive = argc == 1;
    try {
        const auto options = parse_options(argc, argv);
        interactive = options.interactive;
        const auto job = pdk::load_job(options.job_path);
        const int result = pdk::run_update(job);
        if (!options.quiet) std::cout << "PDK_UPDATE_OK version=" << job.target_version << '\n';
        if (interactive) pdk::show_result(true, "Update installed successfully: " + job.target_version);
        return result;
    } catch (const pdk::UpdateError& error) {
        std::cerr << "PDK_UPDATE_ERROR " << error.what() << '\n';
        if (interactive) pdk::show_result(false, error.what());
        return error.exit_code();
    } catch (const std::exception& error) {
        std::cerr << "PDK_UPDATE_ERROR " << error.what() << '\n';
        if (interactive) pdk::show_result(false, error.what());
        return 71;
    }
}
