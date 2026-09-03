#pragma once

#include "job.hpp"

#include <filesystem>
#include <string>
#include <vector>

namespace pdk {

std::string sha256_file(const std::filesystem::path& path);
std::vector<unsigned char> decode_base64(const std::string& encoded);
void verify_job_artifact(const UpdateJob& job);

}  // namespace pdk
