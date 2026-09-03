#pragma once

#include "job.hpp"

#include <filesystem>

namespace pdk {

void validate_and_extract(const UpdateJob& job, const std::filesystem::path& target);

}  // namespace pdk
