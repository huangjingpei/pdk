#pragma once

#include <thread>
#include <atomic>
#include <condition_variable>
#include <mutex>
#include <functional>
#include "zhibo-auth-client.hpp"

namespace zhibo {

class ZhiboStreamPoller {
public:
    static ZhiboStreamPoller &instance();

    void start();
    void stop();
    void trigger_once();

    bool is_running() const;

    // 回调通知（可用于 UI 状态刷新）
    using StreamStatusCallback = std::function<void(bool is_live, const StreamInfo &info)>;
    void set_status_callback(StreamStatusCallback cb);

private:
    ZhiboStreamPoller();
    ~ZhiboStreamPoller();
    ZhiboStreamPoller(const ZhiboStreamPoller &) = delete;
    ZhiboStreamPoller &operator=(const ZhiboStreamPoller &) = delete;

    void worker_loop();

    std::thread worker_;
    std::atomic<bool> running_{false};
    std::condition_variable cv_;
    std::mutex cv_mutex_;

    std::mutex cb_mutex_;
    StreamStatusCallback callback_;

    std::atomic<bool> force_check_{false};
    bool logged_streaming_active_{false};
};

} // namespace zhibo
