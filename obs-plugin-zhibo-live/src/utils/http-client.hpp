#pragma once

#include <string>
#include <map>
#include <vector>

namespace zhibo {

struct HttpResponse {
    int status_code = 0;
    std::string body;
    std::string error_message;

    bool is_success() const {
        return status_code >= 200 && status_code < 300;
    }
};

class HttpClient {
public:
    HttpClient();
    ~HttpClient();

    HttpResponse get(const std::string &url, const std::map<std::string, std::string> &headers = {});
    HttpResponse post_json(const std::string &url, const std::string &json_body, const std::map<std::string, std::string> &headers = {});

private:
    HttpResponse execute_request(const std::string &verb, const std::string &url, const std::map<std::string, std::string> &headers, const std::string &body);
};

} // namespace zhibo
