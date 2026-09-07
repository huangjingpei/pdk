#include "zhibo-stream-poller.hpp"
#include "zhibo-config.hpp"
#include "zhibo-obs-source-manager.hpp"
#include <obs.h>
#include <chrono>
#include <iostream>

namespace zhibo {

ZhiboStreamPoller &ZhiboStreamPoller::instance() {
    static ZhiboStreamPoller poller;
    return poller;
}

ZhiboStreamPoller::ZhiboStreamPoller() {}

ZhiboStreamPoller::~ZhiboStreamPoller() {
    stop();
}

void ZhiboStreamPoller::start() {
    if (running_.load()) return;
    running_.store(true);
    worker_ = std::thread(&ZhiboStreamPoller::worker_loop, this);
    blog(LOG_INFO, "[ZhiboLive] 活动流探测后台轮询已启动");
}

void ZhiboStreamPoller::stop() {
    if (!running_.load()) return;
    running_.store(false);
    cv_.notify_all();
    if (worker_.joinable()) {
        worker_.join();
    }
    blog(LOG_INFO, "[ZhiboLive] 活动流探测后台轮询已停止");
}

void ZhiboStreamPoller::trigger_once() {
    force_check_.store(true);
    cv_.notify_all();
}

bool ZhiboStreamPoller::is_running() const {
    return running_.load();
}

void ZhiboStreamPoller::set_status_callback(StreamStatusCallback cb) {
    std::lock_guard<std::mutex> lock(cb_mutex_);
    callback_ = cb;
}

void ZhiboStreamPoller::worker_loop() {
    while (running_.load()) {
        auto &cfg = ZhiboConfig::instance();
        if (cfg.is_activated()) {
            bool force = force_check_.exchange(false);
            bool locally_streaming = ZhiboObsSourceManager::instance().is_streaming();

            // 如果本地正在拉流，且非手动强制触发，检测本地拉流源状态
            if (!force && locally_streaming) {
                if (ZhiboObsSourceManager::instance().is_stream_active()) {
                    if (!logged_streaming_active_) {
                        blog(LOG_INFO, "[ZhiboLive] 本地正在拉流播放中，暂停向服务器轮询查询");
                        logged_streaming_active_ = true;
                    }

                    // 本地正在拉流播放，跳过请求，直接等待下一个周期
                    std::unique_lock<std::mutex> lock(cv_mutex_);
                    int interval = cfg.get_poll_interval_sec();
                    cv_.wait_for(lock, std::chrono::seconds(interval), [this]() {
                        return !running_.load();
                    });
                    continue;
                } else {
                    blog(LOG_INFO, "[ZhiboLive] 检测到本地拉流已中断或停止，恢复向服务器查询状态");
                    logged_streaming_active_ = false;
                    ZhiboObsSourceManager::instance().clear_active_stream();
                }
            } else {
                logged_streaming_active_ = false;
            }

            try {
                auto streams = ZhiboAuthClient::instance().query_current_streams();
                bool found_live = false;
                StreamInfo live_stream;

                for (const auto &s : streams) {
                    if ((s.status == "LIVE" || s.status == "AUTHORIZED") && !s.rtmp_url.empty()) {
                        found_live = true;
                        live_stream = s;
                        break;
                    }
                }

                static bool last_found_live = false;
                static std::string last_url = "";
                if (found_live != last_found_live || (found_live && live_stream.rtmp_url != last_url)) {
                    last_found_live = found_live;
                    last_url = live_stream.rtmp_url;
                    if (found_live) {
                        blog(LOG_INFO, "[ZhiboLive] 探测到活动直播流: %s (会话: %s)", live_stream.rtmp_url.c_str(), live_stream.session_no.c_str());
                    } else {
                        blog(LOG_INFO, "[ZhiboLive] 当前无活动直播流 (等待开播中...)");
                    }
                }

                if (found_live) {
                    if (cfg.is_auto_pull()) {
                        ZhiboObsSourceManager::instance().set_active_stream(live_stream.rtmp_url);
                    }
                } else {
                    if (ZhiboObsSourceManager::instance().is_streaming()) {
                        ZhiboObsSourceManager::instance().clear_active_stream();
                    }
                }

                // 回调通知
                {
                    std::lock_guard<std::mutex> lock(cb_mutex_);
                    if (callback_) {
                        callback_(found_live, live_stream);
                    }
                }
            } catch (const std::exception &e) {
                blog(LOG_WARNING, "[ZhiboLive] 轮询流状态异常: %s", e.what());
            }
        }

        // 等待下一个周期
        std::unique_lock<std::mutex> lock(cv_mutex_);
        int interval = cfg.get_poll_interval_sec();
        cv_.wait_for(lock, std::chrono::seconds(interval), [this]() {
            return !running_.load();
        });
    }
}

} // namespace zhibo
