#pragma once

#include "job.hpp"

#include <windows.h>

#include <filesystem>
#include <string>

namespace pdk {

bool wait_for_process_exit(uint32_t pid, uint32_t timeout_seconds);
PROCESS_INFORMATION launch_client(const std::filesystem::path& root, const std::string& entry_point,
                                  const std::filesystem::path* health_file,
                                  const std::string* health_nonce);
bool wait_for_health(PROCESS_INFORMATION& process, const UpdateJob& job);
void terminate_process(PROCESS_INFORMATION& process);
std::filesystem::path choose_job_file();
void show_result(bool success, const std::string& message);
std::string unique_suffix();

}  // namespace pdk
