#pragma once

#include "job.hpp"

#include <string>

namespace pdk {

void report_event(const UpdateJob& job, const std::string& event_type,
                  const std::string& error_category = "") noexcept;

}  // namespace pdk
