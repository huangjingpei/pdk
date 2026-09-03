#include "zip_install.hpp"

#include <miniz.h>
#include <nlohmann/json.hpp>

#include <windows.h>

#include <algorithm>
#include <cctype>
#include <memory>
#include <set>
#include <string>
#include <vector>

namespace pdk {
namespace {

using json = nlohmann::json;

struct WinZipSource {
    HANDLE handle{INVALID_HANDLE_VALUE};
};

size_t read_zip(void* opaque, mz_uint64 offset, void* buffer, size_t count) {
    auto* source = static_cast<WinZipSource*>(opaque);
    LARGE_INTEGER position{};
    position.QuadPart = static_cast<LONGLONG>(offset);
    if (!SetFilePointerEx(source->handle, position, nullptr, FILE_BEGIN)) return 0;
    DWORD read = 0;
    if (!ReadFile(source->handle, buffer, static_cast<DWORD>(count), &read, nullptr)) return 0;
    return read;
}

struct OutputFile {
    HANDLE handle{INVALID_HANDLE_VALUE};
};

size_t write_file(void* opaque, mz_uint64 offset, const void* buffer, size_t count) {
    auto* output = static_cast<OutputFile*>(opaque);
    LARGE_INTEGER position{};
    position.QuadPart = static_cast<LONGLONG>(offset);
    if (!SetFilePointerEx(output->handle, position, nullptr, FILE_BEGIN)) return 0;
    DWORD written = 0;
    if (!WriteFile(output->handle, buffer, static_cast<DWORD>(count), &written, nullptr)) return 0;
    return written;
}

class ZipReader {
public:
    explicit ZipReader(const std::filesystem::path& path) {
        source_.handle = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                                     OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL | FILE_FLAG_RANDOM_ACCESS, nullptr);
        if (source_.handle == INVALID_HANDLE_VALUE) throw UpdateError(windows_error("cannot open ZIP"));
        LARGE_INTEGER size{};
        if (!GetFileSizeEx(source_.handle, &size) || size.QuadPart <= 0) {
            CloseHandle(source_.handle);
            source_.handle = INVALID_HANDLE_VALUE;
            throw UpdateError(windows_error("cannot read ZIP size"));
        }
        mz_zip_zero_struct(&zip_);
        zip_.m_pRead = read_zip;
        zip_.m_pIO_opaque = &source_;
        if (!mz_zip_reader_init(&zip_, static_cast<mz_uint64>(size.QuadPart), 0)) {
            CloseHandle(source_.handle);
            source_.handle = INVALID_HANDLE_VALUE;
            throw UpdateError("invalid ZIP central directory");
        }
        initialized_ = true;
    }

    ~ZipReader() {
        if (initialized_) mz_zip_reader_end(&zip_);
        if (source_.handle != INVALID_HANDLE_VALUE) CloseHandle(source_.handle);
    }
    ZipReader(const ZipReader&) = delete;
    ZipReader& operator=(const ZipReader&) = delete;
    mz_zip_archive* get() { return &zip_; }

private:
    WinZipSource source_{};
    mz_zip_archive zip_{};
    bool initialized_{false};
};

std::string normalize_entry(const std::string& raw, bool directory) {
    if (raw.empty() || raw.front() == '/' || raw.front() == '\\' || raw.find('\0') != std::string::npos) {
        throw UpdateError("ZIP contains an absolute or empty path");
    }
    std::string path = raw;
    std::replace(path.begin(), path.end(), '\\', '/');
    if (path.size() >= 2 && std::isalpha(static_cast<unsigned char>(path[0])) && path[1] == ':') {
        throw UpdateError("ZIP contains a drive-qualified path");
    }
    std::string component;
    size_t start = 0;
    while (start < path.size()) {
        const size_t end = path.find('/', start);
        component = path.substr(start, end == std::string::npos ? std::string::npos : end - start);
        if (component == ".." || component == "." || component.find(':') != std::string::npos ||
            (!component.empty() && (component.back() == ' ' || component.back() == '.'))) {
            throw UpdateError("ZIP contains an unsafe Windows path: " + raw);
        }
        for (unsigned char c : component) if (c < 32) throw UpdateError("ZIP path contains control characters");
        if (end == std::string::npos) break;
        start = end + 1;
    }
    while (!path.empty() && path.back() == '/') path.pop_back();
    if (path.empty() && !directory) throw UpdateError("ZIP file path is empty");
    return path;
}

std::string lower_ascii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return value;
}

json read_json_entry(mz_zip_archive* zip, const char* name) {
    size_t size = 0;
    void* data = mz_zip_reader_extract_file_to_heap(zip, name, &size, 0);
    if (!data) throw UpdateError(std::string("cannot extract JSON from ZIP: ") + name);
    std::unique_ptr<void, decltype(&mz_free)> holder(data, &mz_free);
    try {
        return json::parse(static_cast<const char*>(data), static_cast<const char*>(data) + size);
    } catch (const json::exception& error) {
        throw UpdateError(std::string("invalid JSON in ZIP: ") + error.what());
    }
}

std::string required_json_string(const json& value, const char* key) {
    if (!value.contains(key) || !value[key].is_string()) throw UpdateError(std::string("manifest missing ") + key);
    return value[key].get<std::string>();
}

void extract_entry(mz_zip_archive* zip, mz_uint index, const std::filesystem::path& destination) {
    std::filesystem::create_directories(destination.parent_path());
    OutputFile output;
    output.handle = CreateFileW(destination.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                                FILE_ATTRIBUTE_NORMAL, nullptr);
    if (output.handle == INVALID_HANDLE_VALUE) throw UpdateError(windows_error("cannot create extracted file"));
    const bool ok = mz_zip_reader_extract_to_callback(zip, index, write_file, &output, 0) == MZ_TRUE;
    FlushFileBuffers(output.handle);
    CloseHandle(output.handle);
    if (!ok) {
        std::error_code ignored;
        std::filesystem::remove(destination, ignored);
        throw UpdateError("ZIP extraction failed: " + path_to_utf8(destination.filename()));
    }
}

}  // namespace

void validate_and_extract(const UpdateJob& job, const std::filesystem::path& target) {
    ZipReader reader(job.package_path);
    auto* zip = reader.get();
    const mz_uint count = mz_zip_reader_get_num_files(zip);
    if (count == 0 || count > 30000) throw UpdateError("ZIP file count exceeds safety limit");
    const int manifest_index = mz_zip_reader_locate_file(zip, "update-manifest.json", nullptr,
                                                         MZ_ZIP_FLAG_CASE_SENSITIVE);
    if (manifest_index < 0) throw UpdateError("ZIP root is missing update-manifest.json");
    const json manifest = read_json_entry(zip, "update-manifest.json");
    if (manifest.value("appId", int64_t{}) != job.app_id ||
        required_json_string(manifest, "version") != job.target_version ||
        required_json_string(manifest, "platform") != job.platform ||
        required_json_string(manifest, "arch") != job.arch ||
        required_json_string(manifest, "entryPoint") != job.entry_point) {
        throw UpdateError("ZIP manifest target does not match updater job");
    }
    if (!manifest.contains("files") || !manifest["files"].is_array()) {
        throw UpdateError("ZIP manifest files whitelist is missing");
    }
    const std::string build_config = required_json_string(manifest, "buildConfig");
    std::set<std::string> allowed{"update-manifest.json"};
    for (const auto& item : manifest["files"]) {
        if (!item.is_string()) throw UpdateError("ZIP manifest contains a non-string file name");
        allowed.insert(normalize_entry(item.get<std::string>(), false));
    }
    if (!allowed.contains(normalize_entry(build_config, false))) {
        throw UpdateError("buildConfig is not part of the signed file whitelist");
    }
    const json embedded = read_json_entry(zip, build_config.c_str());
    if (embedded.value("appId", int64_t{}) != job.app_id ||
        required_json_string(embedded, "version") != job.target_version ||
        required_json_string(embedded, "entryPoint") != job.entry_point) {
        throw UpdateError("embedded buildConfig does not match updater job");
    }

    uint64_t total = 0;
    std::set<std::string> windows_names;
    std::filesystem::create_directories(target);
    for (mz_uint index = 0; index < count; ++index) {
        mz_zip_archive_file_stat info{};
        if (!mz_zip_reader_file_stat(zip, index, &info)) throw UpdateError("cannot inspect ZIP entry");
        const bool directory = mz_zip_reader_is_file_a_directory(zip, index) == MZ_TRUE;
        const std::string name = normalize_entry(info.m_filename, directory);
        if (name.empty() && directory) continue;
        if (!directory && !allowed.contains(name)) throw UpdateError("ZIP contains a file outside manifest: " + name);
        if (!windows_names.insert(lower_ascii(name)).second) throw UpdateError("ZIP has duplicate Windows path: " + name);
        if (mz_zip_reader_is_file_encrypted(zip, index) || !mz_zip_reader_is_file_supported(zip, index)) {
            throw UpdateError("ZIP contains encrypted or unsupported entry: " + name);
        }
        const auto unix_mode = static_cast<unsigned long>((info.m_external_attr >> 16U) & 0170000U);
        if (unix_mode == 0120000U) throw UpdateError("ZIP symbolic links are forbidden");
        total += info.m_uncomp_size;
        if (total > 8ULL * 1024 * 1024 * 1024) throw UpdateError("ZIP expands beyond 8 GiB limit");
        const auto destination = target / path_from_utf8(name);
        if (directory) std::filesystem::create_directories(destination);
        else extract_entry(zip, index, destination);
    }
    if (!std::filesystem::is_regular_file(target / path_from_utf8(job.entry_point))) {
        throw UpdateError("extracted client entry point is missing");
    }
}

}  // namespace pdk
