cmake_minimum_required(VERSION 3.24)
project(pdk_native_updater VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

include(FetchContent)

# 固定到明确提交，避免上游分支变化导致发布构建不可复现。
FetchContent_Declare(
  nlohmann_json
  URL https://codeload.github.com/nlohmann/json/zip/refs/tags/v3.11.3
  URL_HASH SHA256=04022B05D806EB5FF73023C280B68697D12B93E1B7267A0B22A1A39EC7578069
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(nlohmann_json)

FetchContent_Declare(
  miniz_source
  URL https://codeload.github.com/richgel999/miniz/zip/refs/tags/3.1.2
  URL_HASH SHA256=E7A0334927A069DC5AEB69616D371542A4376E1569290D7102B88C6F3992D6CB
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_GetProperties(miniz_source)
if(NOT miniz_source_POPULATED)
  FetchContent_Populate(miniz_source)
endif()
add_library(pdk_miniz STATIC
  ${miniz_source_SOURCE_DIR}/miniz.c
  ${miniz_source_SOURCE_DIR}/miniz_zip.c
  ${miniz_source_SOURCE_DIR}/miniz_tinfl.c
  ${miniz_source_SOURCE_DIR}/miniz_tdef.c
)
include(GenerateExportHeader)
generate_export_header(pdk_miniz BASE_NAME MINIZ
  EXPORT_FILE_NAME ${CMAKE_CURRENT_BINARY_DIR}/miniz_export.h)
target_include_directories(pdk_miniz PUBLIC
  ${miniz_source_SOURCE_DIR}
  ${CMAKE_CURRENT_BINARY_DIR}
)
target_compile_definitions(pdk_miniz PRIVATE MINIZ_NO_ARCHIVE_WRITING_APIS)

FetchContent_Declare(
  monocypher_source
  URL https://codeload.github.com/LoupVaillant/Monocypher/zip/refs/tags/4.0.2
  URL_HASH SHA256=07F591A694AA7CA8FD170B481AF01DAEAF745CB909811B391784B53FCE8264E3
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_GetProperties(monocypher_source)
if(NOT monocypher_source_POPULATED)
  FetchContent_Populate(monocypher_source)
endif()
add_library(pdk_monocypher STATIC
  ${monocypher_source_SOURCE_DIR}/src/monocypher.c
  ${monocypher_source_SOURCE_DIR}/src/optional/monocypher-ed25519.c
)
target_include_directories(pdk_monocypher PUBLIC
  ${monocypher_source_SOURCE_DIR}/src
  ${monocypher_source_SOURCE_DIR}/src/optional
)

add_executable(pdk_updater
  src/main.cpp
  src/crypto.cpp
  src/job.cpp
  src/platform.cpp
  src/telemetry.cpp
  src/zip_install.cpp
  src/update_transaction.cpp
  src/rollback.cpp
)
target_include_directories(pdk_updater PRIVATE src)
target_link_libraries(pdk_updater PRIVATE
  nlohmann_json::nlohmann_json
  pdk_miniz
  pdk_monocypher
  bcrypt
  crypt32
  comdlg32
  shell32
  user32
  winhttp
)
target_compile_definitions(pdk_updater PRIVATE
  UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX
  PDK_UPDATER_VERSION="${PROJECT_VERSION}"
)
if(MSVC)
  target_compile_options(pdk_updater PRIVATE /W4 /permissive- /utf-8 /EHsc)
endif()

install(TARGETS pdk_updater RUNTIME DESTINATION .)

# 带原生 Win32 UI 的升级器（GUI 形态）。复用同一套核心逻辑，不改动 CLI。
add_executable(pdk_updater_gui WIN32
  src/gui.cpp
  src/gui_config.cpp
  src/update_transaction.cpp
  src/rollback.cpp
  src/crypto.cpp
  src/job.cpp
  src/platform.cpp
  src/telemetry.cpp
  src/zip_install.cpp
)
target_include_directories(pdk_updater_gui PRIVATE src)
target_link_libraries(pdk_updater_gui PRIVATE
  nlohmann_json::nlohmann_json
  pdk_miniz
  pdk_monocypher
  bcrypt
  crypt32
  comdlg32
  shell32
  user32
  winhttp
)
target_compile_definitions(pdk_updater_gui PRIVATE
  UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX
  PDK_UPDATER_VERSION="${PROJECT_VERSION}"
)
if(MSVC)
  target_compile_options(pdk_updater_gui PRIVATE /W4 /permissive- /utf-8 /EHsc)
endif()

install(TARGETS pdk_updater_gui RUNTIME DESTINATION .)
