#include "http-client.hpp"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winhttp.h>
#include <sstream>
#include <iostream>

#pragma comment(lib, "winhttp.lib")

namespace zhibo {

namespace {

std::wstring to_wide(const std::string &str) {
    if (str.empty()) return L"";
    int size_needed = MultiByteToWideChar(CP_UTF8, 0, str.c_str(), (int)str.size(), NULL, 0);
    std::wstring wstr(size_needed, 0);
    MultiByteToWideChar(CP_UTF8, 0, str.c_str(), (int)str.size(), &wstr[0], size_needed);
    return wstr;
}

std::string to_utf8(const std::wstring &wstr) {
    if (wstr.empty()) return "";
    int size_needed = WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), (int)wstr.size(), NULL, 0, NULL, NULL);
    std::string str(size_needed, 0);
    WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), (int)wstr.size(), &str[0], size_needed, NULL, NULL);
    return str;
}

struct ParsedUrl {
    bool is_https = false;
    std::wstring host;
    INTERNET_PORT port = 80;
    std::wstring path = L"/";
};

bool parse_url(const std::string &raw_url, ParsedUrl &out) {
    std::wstring wurl = to_wide(raw_url);
    URL_COMPONENTS urlComp = {0};
    urlComp.dwStructSize = sizeof(urlComp);

    wchar_t hostName[256] = {0};
    urlComp.lpszHostName = hostName;
    urlComp.dwHostNameLength = ARRAYSIZE(hostName);

    wchar_t urlPath[2048] = {0};
    urlComp.lpszUrlPath = urlPath;
    urlComp.dwUrlPathLength = ARRAYSIZE(urlPath);

    wchar_t extraInfo[2048] = {0};
    urlComp.lpszExtraInfo = extraInfo;
    urlComp.dwExtraInfoLength = ARRAYSIZE(extraInfo);

    if (!WinHttpCrackUrl(wurl.c_str(), (DWORD)wurl.length(), 0, &urlComp)) {
        return false;
    }

    out.is_https = (urlComp.nScheme == INTERNET_SCHEME_HTTPS);
    out.host = hostName;
    out.port = urlComp.nPort;
    out.path = urlPath;
    if (extraInfo[0] != L'\0') {
        out.path += extraInfo;
    }
    if (out.path.empty()) {
        out.path = L"/";
    }
    return true;
}

} // namespace

HttpClient::HttpClient() {}

HttpClient::~HttpClient() {}

HttpResponse HttpClient::get(const std::string &url, const std::map<std::string, std::string> &headers) {
    return execute_request("GET", url, headers, "");
}

HttpResponse HttpClient::post_json(const std::string &url, const std::string &json_body, const std::map<std::string, std::string> &headers) {
    std::map<std::string, std::string> all_headers = headers;
    all_headers["Content-Type"] = "application/json; charset=utf-8";
    return execute_request("POST", url, all_headers, json_body);
}

HttpResponse HttpClient::execute_request(const std::string &verb, const std::string &url,
                                        const std::map<std::string, std::string> &headers,
                                        const std::string &body) {
    HttpResponse response;

    ParsedUrl parsed;
    if (!parse_url(url, parsed)) {
        response.status_code = 0;
        response.error_message = "URL 解析失败: " + url;
        return response;
    }

    HINTERNET hSession = WinHttpOpen(L"OBS-ZhiboLive-Plugin/1.0",
                                     WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                                     WINHTTP_NO_PROXY_NAME,
                                     WINHTTP_NO_PROXY_BYPASS, 0);
    if (!hSession) {
        response.error_message = "WinHttpOpen 失败";
        return response;
    }

    // 设置超时：连接 5s，发送 10s，接收 10s
    WinHttpSetTimeouts(hSession, 5000, 5000, 10000, 10000);

    HINTERNET hConnect = WinHttpConnect(hSession, parsed.host.c_str(), parsed.port, 0);
    if (!hConnect) {
        WinHttpCloseHandle(hSession);
        response.error_message = "无法连接到服务器: " + to_utf8(parsed.host);
        return response;
    }

    std::wstring wverb = to_wide(verb);
    DWORD dwFlags = parsed.is_https ? WINHTTP_FLAG_SECURE : 0;

    HINTERNET hRequest = WinHttpOpenRequest(hConnect, wverb.c_str(), parsed.path.c_str(),
                                           NULL, WINHTTP_NO_REFERER,
                                           WINHTTP_DEFAULT_ACCEPT_TYPES, dwFlags);
    if (!hRequest) {
        WinHttpCloseHandle(hConnect);
        WinHttpCloseHandle(hSession);
        response.error_message = "WinHttpOpenRequest 失败";
        return response;
    }

    // 自定义请求头
    std::wstring header_block;
    for (const auto &pair : headers) {
        header_block += to_wide(pair.first) + L": " + to_wide(pair.second) + L"\r\n";
    }

    // HTTPS 容错 (例如测试自签名证书，如果需要可以忽略，默认标准验证)
    if (parsed.is_https) {
        DWORD certFlags = SECURITY_FLAG_IGNORE_UNKNOWN_CA |
                          SECURITY_FLAG_IGNORE_CERT_DATE_INVALID |
                          SECURITY_FLAG_IGNORE_CERT_CN_INVALID;
        WinHttpSetOption(hRequest, WINHTTP_OPTION_SECURITY_FLAGS, &certFlags, sizeof(certFlags));
    }

    BOOL bResults = WinHttpSendRequest(hRequest,
                                       header_block.empty() ? WINHTTP_NO_ADDITIONAL_HEADERS : header_block.c_str(),
                                       (DWORD)header_block.length(),
                                       (LPVOID)body.c_str(),
                                       (DWORD)body.length(),
                                       (DWORD)body.length(),
                                       0);

    if (bResults) {
        bResults = WinHttpReceiveResponse(hRequest, NULL);
    }

    if (bResults) {
        DWORD dwStatusCode = 0;
        DWORD dwSize = sizeof(dwStatusCode);
        WinHttpQueryHeaders(hRequest,
                            WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                            WINHTTP_HEADER_NAME_BY_INDEX,
                            &dwStatusCode, &dwSize, WINHTTP_NO_HEADER_INDEX);
        response.status_code = (int)dwStatusCode;

        // 读取响应内容
        std::string response_data;
        DWORD dwDownloaded = 0;
        do {
            dwSize = 0;
            if (!WinHttpQueryDataAvailable(hRequest, &dwSize)) {
                break;
            }
            if (dwSize == 0) {
                break;
            }

            std::vector<char> buffer(dwSize + 1);
            if (WinHttpReadData(hRequest, buffer.data(), dwSize, &dwDownloaded)) {
                response_data.append(buffer.data(), dwDownloaded);
            } else {
                break;
            }
        } while (dwSize > 0);

        response.body = response_data;
    } else {
        DWORD err = GetLastError();
        response.status_code = 0;
        response.error_message = "请求发送/接收失败，错误码: " + std::to_string(err);
    }

    WinHttpCloseHandle(hRequest);
    WinHttpCloseHandle(hConnect);
    WinHttpCloseHandle(hSession);

    return response;
}

} // namespace zhibo
