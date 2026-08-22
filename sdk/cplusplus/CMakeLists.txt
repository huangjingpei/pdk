cmake_minimum_required(VERSION 3.16)
project(pdk-sdk CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 统一以 UTF-8 解读源文件（含中文注释与字符串字面量）。
# 否则 MSVC 在 GBK 代码页(936) 下会误报 C4819，并把中文字符串字面量解析错位导致
# “常量中有换行符” 等错误。BOM + /utf-8 双保险（见下方文件也已写入 BOM）。
if(MSVC)
    add_compile_options(/utf-8)
endif()

# ---------- 依赖（通过 vcpkg 或系统包管理器安装） ----------
#   vcpkg install curl openssl nlohmann-json
#   cmake -B build -S . -DCMAKE_TOOLCHAIN_FILE=<vcpkg>/scripts/buildsystems/vcpkg.cmake
find_package(CURL         REQUIRED)
find_package(OpenSSL      REQUIRED)
find_package(nlohmann_json CONFIG REQUIRED)

set(PDK_INCLUDE ${CMAKE_CURRENT_SOURCE_DIR}/include)

# ---------- 静态库：C++ 业务实现（供 C++ 开发者直接链接） ----------
add_library(pdk STATIC src/pdk.cpp)
target_include_directories(pdk PUBLIC ${PDK_INCLUDE})
target_link_libraries(pdk PUBLIC
    CURL::libcurl
    OpenSSL::SSL
    OpenSSL::Crypto
    nlohmann_json::nlohmann_json)

# ---------- 动态库：C ABI（DLL / so），供 易语言 / C# / Delphi / Go 调用 ----------
add_library(pdk_capi SHARED src/pdk_capi.cpp)
target_include_directories(pdk_capi PUBLIC ${PDK_INCLUDE})
target_compile_definitions(pdk_capi PRIVATE PDK_CAPI_EXPORTS)
target_link_libraries(pdk_capi PRIVATE pdk)

# ---------- 示例可执行文件（演示 C++ API + 回调） ----------
add_executable(pdk_example examples/main.cpp)
target_link_libraries(pdk_example PRIVATE pdk)

install(TARGETS pdk pdk_capi pdk_example
        RUNTIME DESTINATION bin
        LIBRARY DESTINATION lib
        ARCHIVE DESTINATION lib)
install(DIRECTORY include/ DESTINATION include)
