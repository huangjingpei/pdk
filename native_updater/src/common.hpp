#pragma once

#include <windows.h>

#include <filesystem>
#include <stdexcept>
#include <string>

namespace pdk {

class UpdateError : public std::runtime_error {
public:
    explicit UpdateError(const std::string& message, int exit_code = 2)
        : std::runtime_error(message), exit_code_(exit_code) {}
    [[nodiscard]] int exit_code() const noexcept { return exit_code_; }
private:
    int exit_code_;
};

inline std::wstring widen(const std::string& value) {
    if (value.empty()) return {};
    const int size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(),
                                         static_cast<int>(value.size()), nullptr, 0);
    if (size <= 0) throw UpdateError("UTF-8 path conversion failed");
    std::wstring result(static_cast<size_t>(size), L'\0');
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(),
                        static_cast<int>(value.size()), result.data(), size);
    return result;
}

inline std::string narrow(const std::wstring& value) {
    if (value.empty()) return {};
    const int size = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value.data(),
                                         static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
    if (size <= 0) throw UpdateError("UTF-16 path conversion failed");
    std::string result(static_cast<size_t>(size), '\0');
    WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value.data(),
                        static_cast<int>(value.size()), result.data(), size, nullptr, nullptr);
    return result;
}

inline std::filesystem::path path_from_utf8(const std::string& value) {
    return std::filesystem::path(widen(value));
}

inline std::string path_to_utf8(const std::filesystem::path& value) {
    return narrow(value.wstring());
}

inline std::string windows_error(const std::string& prefix, DWORD code = GetLastError()) {
    wchar_t* buffer = nullptr;
    FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM |
                       FORMAT_MESSAGE_IGNORE_INSERTS,
                   nullptr, code, 0, reinterpret_cast<wchar_t*>(&buffer), 0, nullptr);
    std::wstring detail = buffer ? buffer : L"unknown";
    if (buffer) LocalFree(buffer);
    return prefix + ": " + narrow(detail) + " (" + std::to_string(code) + ")";
}

}  // namespace pdk
