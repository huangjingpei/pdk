#pragma once

#include <string>
#include <mutex>

namespace zhibo {

class ZhiboObsSourceManager {
public:
    static ZhiboObsSourceManager &instance();

    // 探测到有效 RTMP 推流时，创建或更新 OBS 场景中的 ffmpeg_source 媒体源
    void set_active_stream(const std::string &rtmp_url);

    // 断流或无直播时，清空或重置 OBS 媒体源
    void clear_active_stream();

    // 获取当前拉流地址
    std::string get_current_rtmp_url() const;

    // 是否正在拉流 (本地标记)
    bool is_streaming() const;

    // 检查 OBS 内部当前拉流源是否正在正常活跃拉流播放中
    bool is_stream_active();

private:
    ZhiboObsSourceManager();
    ~ZhiboObsSourceManager() = default;
    ZhiboObsSourceManager(const ZhiboObsSourceManager &) = delete;
    ZhiboObsSourceManager &operator=(const ZhiboObsSourceManager &) = delete;

    mutable std::mutex mutex_;
    std::string current_rtmp_url_;
    bool is_streaming_ = false;
};

} // namespace zhibo
