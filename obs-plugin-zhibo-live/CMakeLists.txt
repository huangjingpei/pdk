cmake_minimum_required(VERSION 3.20)

project(obs-zhibo-live VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 启用 Qt 的自动 MOC、UIC 处理
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)
set(CMAKE_AUTOUIC ON)

# 允许从环境变量获取 OBS 与 Qt 安装路径
if(DEFINED ENV{OBS_STUDIO_DIR})
    list(APPEND CMAKE_PREFIX_PATH "$ENV{OBS_STUDIO_DIR}")
endif()
if(DEFINED ENV{QT6_DIR})
    list(APPEND CMAKE_PREFIX_PATH "$ENV{QT6_DIR}")
endif()
if(DEFINED ENV{Qt6_DIR})
    list(APPEND CMAKE_PREFIX_PATH "$ENV{Qt6_DIR}")
endif()

# 常用 Windows 默认探测路径
list(APPEND CMAKE_PREFIX_PATH
    "C:/Qt/6.9.2/msvc2022_64"
    "C:/Qt/6.9.0/msvc2022_64"
    "C:/Qt/6.8.0/msvc2022_64"
    "C:/Qt/6.7.0/msvc2022_64"
    "C:/Qt/6.6.0/msvc2022_64"
    "C:/Qt/6.5.3/msvc2022_64"
    "C:/Program Files/obs-studio"
    "D:/Program Files/obs-studio"
)

# 默认 fallback 路径检测 (全部自包含于 deps/ 下)
if(NOT DEFINED LIBOBS_INCLUDE_DIR AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/deps/obs-studio-headers/libobs")
    set(LIBOBS_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/deps/obs-studio-headers/libobs")
endif()
if(NOT DEFINED OBS_FRONTEND_API_INCLUDE_DIR AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/deps/obs-studio-headers/frontend/api")
    set(OBS_FRONTEND_API_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/deps/obs-studio-headers/frontend/api")
endif()
if(NOT DEFINED LIBOBS_LIB AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/deps/lib/obs.lib")
    set(LIBOBS_LIB "${CMAKE_CURRENT_SOURCE_DIR}/deps/lib/obs.lib")
endif()
if(NOT DEFINED OBS_FRONTEND_API_LIB AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/deps/lib/obs-frontend-api.lib")
    set(OBS_FRONTEND_API_LIB "${CMAKE_CURRENT_SOURCE_DIR}/deps/lib/obs-frontend-api.lib")
endif()

# 查找 libobs 与 obs-frontend-api
find_package(libobs QUIET)
find_package(obs-frontend-api QUIET)

# 查找 Qt6
find_package(Qt6 COMPONENTS Core Widgets QUIET)

# 如果标准 find_package 未找到，提供兼容变量查找模式
if(NOT TARGET OBS::libobs AND DEFINED LIBOBS_INCLUDE_DIR AND DEFINED LIBOBS_LIB)
    add_library(OBS::libobs INTERFACE IMPORTED)
    target_include_directories(OBS::libobs INTERFACE "${LIBOBS_INCLUDE_DIR}")
    target_link_libraries(OBS::libobs INTERFACE "${LIBOBS_LIB}")
endif()

if(NOT TARGET OBS::obs-frontend-api AND DEFINED OBS_FRONTEND_API_INCLUDE_DIR AND DEFINED OBS_FRONTEND_API_LIB)
    add_library(OBS::obs-frontend-api INTERFACE IMPORTED)
    target_include_directories(OBS::obs-frontend-api INTERFACE "${OBS_FRONTEND_API_INCLUDE_DIR}")
    target_link_libraries(OBS::obs-frontend-api INTERFACE "${OBS_FRONTEND_API_LIB}")
endif()

# 插件源码
set(PLUGIN_SOURCES
    src/plugin-main.cpp
    src/zhibo-config.hpp
    src/zhibo-config.cpp
    src/zhibo-auth-client.hpp
    src/zhibo-auth-client.cpp
    src/zhibo-stream-poller.hpp
    src/zhibo-stream-poller.cpp
    src/zhibo-obs-source-manager.hpp
    src/zhibo-obs-source-manager.cpp
    src/ui/activation-dialog.hpp
    src/ui/activation-dialog.cpp
    src/filters/video-variant-filter.hpp
    src/filters/video-variant-filter.cpp
    src/filters/audio-variant-filter.hpp
    src/filters/audio-variant-filter.cpp
    src/utils/http-client.hpp
    src/utils/http-client.cpp
    src/utils/pdk-crypto.hpp
    src/utils/pdk-crypto.cpp
    src/utils/json.hpp
)

add_library(${PROJECT_NAME} MODULE ${PLUGIN_SOURCES})

target_include_directories(${PROJECT_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)

if(TARGET OBS::libobs)
    target_link_libraries(${PROJECT_NAME} PRIVATE OBS::libobs)
endif()

if(TARGET OBS::obs-frontend-api)
    target_link_libraries(${PROJECT_NAME} PRIVATE OBS::obs-frontend-api)
endif()

if(TARGET Qt6::Core AND TARGET Qt6::Widgets)
    target_link_libraries(${PROJECT_NAME} PRIVATE Qt6::Core Qt6::Widgets)
endif()

# Windows 系统库依赖
target_link_libraries(${PROJECT_NAME} PRIVATE
    winhttp
    advapi32
    bcrypt
    crypt32
)

if(MSVC)
    target_compile_options(${PROJECT_NAME} PRIVATE /utf-8)
endif()

target_compile_definitions(${PROJECT_NAME} PRIVATE
    $<$<CONFIG:Release>:PDK_RELEASE_BUILD>
    $<$<CONFIG:RelWithDebInfo>:PDK_RELEASE_BUILD>
    $<$<CONFIG:MinSizeRel>:PDK_RELEASE_BUILD>
    $<$<CONFIG:Debug>:PDK_DEBUG_BUILD>
)

# 目标输出格式设定 (Windows DLL 前缀置空以符合 OBS 命名规范)
set_target_properties(${PROJECT_NAME} PROPERTIES
    PREFIX ""
    OUTPUT_NAME "obs-zhibo-live"
)

# 拷贝语言包与资源
add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "$<TARGET_FILE_DIR:${PROJECT_NAME}>/data/locale"
    COMMAND ${CMAKE_COMMAND} -E copy_directory "${CMAKE_CURRENT_SOURCE_DIR}/assets/locale" "$<TARGET_FILE_DIR:${PROJECT_NAME}>/data/locale"
    COMMENT "Copying localization files to plugin output directory..."
)
