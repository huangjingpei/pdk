#include "telemetry.hpp"

#include "common.hpp"

#include <winhttp.h>
#include <nlohmann/json.hpp>

#include <vector>

namespace pdk {

void report_event(const UpdateJob& job, const std::string& event_type,
                  const std::string& error_category) noexcept {
    try {
        if (!job.telemetry) return;
        const auto& telemetry = *job.telemetry;
        const std::wstring url = widen(telemetry.endpoint);
        URL_COMPONENTSW parts{};
        parts.dwStructSize = sizeof(parts);
        parts.dwSchemeLength = static_cast<DWORD>(-1);
        parts.dwHostNameLength = static_cast<DWORD>(-1);
        parts.dwUrlPathLength = static_cast<DWORD>(-1);
        parts.dwExtraInfoLength = static_cast<DWORD>(-1);
        if (!WinHttpCrackUrl(url.c_str(), static_cast<DWORD>(url.size()), 0, &parts)) return;
        const std::wstring host(parts.lpszHostName, parts.dwHostNameLength);
        std::wstring path(parts.lpszUrlPath, parts.dwUrlPathLength);
        if (parts.dwExtraInfoLength) path.append(parts.lpszExtraInfo, parts.dwExtraInfoLength);
        HINTERNET session = WinHttpOpen(L"PDK-Native-Updater/1.0", WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
                                        WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
        if (!session) return;
        WinHttpSetTimeouts(session, 3000, 3000, 3000, 5000);
        HINTERNET connection = WinHttpConnect(session, host.c_str(), parts.nPort, 0);
        if (!connection) { WinHttpCloseHandle(session); return; }
        const DWORD flags = parts.nScheme == INTERNET_SCHEME_HTTPS ? WINHTTP_FLAG_SECURE : 0;
        HINTERNET request = WinHttpOpenRequest(connection, L"POST", path.c_str(), nullptr,
                                               WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, flags);
        if (!request) { WinHttpCloseHandle(connection); WinHttpCloseHandle(session); return; }
        nlohmann::json body{
            {"checkRequestId", telemetry.check_request_id}, {"eventToken", telemetry.event_token},
            {"artifactId", telemetry.artifact_id ? nlohmann::json(*telemetry.artifact_id) : nlohmann::json(nullptr)},
            {"eventType", event_type}, {"fromVersion", telemetry.from_version},
            {"targetVersion", telemetry.target_version}, {"platform", telemetry.platform},
            {"errorCategory", error_category.empty() ? nlohmann::json(nullptr) : nlohmann::json(error_category)},
        };
        const std::string payload = body.dump();
        const std::wstring headers = L"Content-Type: application/json\r\nX-PDK-App-ID: " +
            std::to_wstring(telemetry.app_id) + L"\r\nX-PDK-Device-ID: " + widen(telemetry.device_id) + L"\r\n";
        WinHttpSendRequest(request, headers.c_str(), static_cast<DWORD>(-1),
                           const_cast<char*>(payload.data()), static_cast<DWORD>(payload.size()),
                           static_cast<DWORD>(payload.size()), 0);
        WinHttpReceiveResponse(request, nullptr);
        WinHttpCloseHandle(request);
        WinHttpCloseHandle(connection);
        WinHttpCloseHandle(session);
    } catch (...) {
        // 遥测永远不能影响升级事务。
    }
}

}  // namespace pdk
