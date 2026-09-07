#include "zhibo-obs-source-manager.hpp"
#include "zhibo-config.hpp"
#include <obs.h>
#include <obs-frontend-api.h>
#include <iostream>

namespace zhibo {

ZhiboObsSourceManager &ZhiboObsSourceManager::instance() {
    static ZhiboObsSourceManager mgr;
    return mgr;
}

ZhiboObsSourceManager::ZhiboObsSourceManager() {}

void ZhiboObsSourceManager::set_active_stream(const std::string &rtmp_url) {
    if (rtmp_url.empty()) return;

    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (current_rtmp_url_ == rtmp_url && is_streaming_) {
            return;
        }
        current_rtmp_url_ = rtmp_url;
        is_streaming_ = true;
    }

    std::string source_name = ZhiboConfig::instance().get_source_name();
    if (source_name.empty()) {
        source_name = "智播拉流源";
    }

    obs_source_t *source = obs_get_source_by_name(source_name.c_str());
    if (!source) {
        // 创建新的 ffmpeg_source 媒体源
        obs_data_t *settings = obs_data_create();
        obs_data_set_bool(settings, "is_local_file", false);
        obs_data_set_string(settings, "input", rtmp_url.c_str());
        obs_data_set_int(settings, "buffering_mb", 2);
        obs_data_set_int(settings, "reconnect_delay_sec", 2);
        obs_data_set_bool(settings, "restart_on_activate", true);
        obs_data_set_bool(settings, "clear_on_media_end", false);
        obs_data_set_bool(settings, "close_when_inactive", false);

        source = obs_source_create("ffmpeg_source", source_name.c_str(), settings, nullptr);
        obs_data_release(settings);

        if (source) {
            blog(LOG_INFO, "[ZhiboLive] 创建新拉流源 [%s], RTMP: %s", source_name.c_str(), rtmp_url.c_str());
        }
    } else {
        // 已存在媒体源，更新 input 地址
        obs_data_t *settings = obs_source_get_settings(source);
        const char *old_input = obs_data_get_string(settings, "input");
        if (!old_input || std::string(old_input) != rtmp_url) {
            obs_data_set_bool(settings, "is_local_file", false);
            obs_data_set_string(settings, "input", rtmp_url.c_str());
            obs_source_update(source, settings);
            blog(LOG_INFO, "[ZhiboLive] 成功更新拉流源 [%s] 地址: %s", source_name.c_str(), rtmp_url.c_str());
        }
        obs_data_release(settings);
    }

    if (source) {
        // 确保将媒体源挂载到当前活动的 OBS 场景中
        obs_source_t *scene_source = obs_frontend_get_current_scene();
        if (scene_source) {
            obs_scene_t *scene = obs_scene_from_source(scene_source);
            if (scene) {
                struct FindCtx {
                    obs_source_t *target;
                    bool found;
                } ctx = {source, false};

                obs_scene_enum_items(scene, [](obs_scene_t *, obs_sceneitem_t *item, void *param) -> bool {
                    FindCtx *c = static_cast<FindCtx *>(param);
                    if (obs_sceneitem_get_source(item) == c->target) {
                        c->found = true;
                        return false; // 找到则中断枚举
                    }
                    return true;
                }, &ctx);

                if (!ctx.found) {
                    obs_sceneitem_t *item = obs_scene_add(scene, source);
                    if (item) {
                        blog(LOG_INFO, "[ZhiboLive] 成功将拉流源 [%s] 挂载至当前活动场景", source_name.c_str());
                    }
                }
            } else {
                blog(LOG_WARNING, "[ZhiboLive] 当前活动 source 不是 scene");
            }
            obs_source_release(scene_source);
        } else {
            blog(LOG_WARNING, "[ZhiboLive] 未获取到当前活动场景 (obs_frontend_get_current_scene 返回 null)");
        }
        obs_source_release(source);
    }
}

void ZhiboObsSourceManager::clear_active_stream() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!is_streaming_) return;
        current_rtmp_url_.clear();
        is_streaming_ = false;
    }

    std::string source_name = ZhiboConfig::instance().get_source_name();
    if (source_name.empty()) source_name = "智播拉流源";

    obs_source_t *source = obs_get_source_by_name(source_name.c_str());
    if (source) {
        obs_data_t *settings = obs_source_get_settings(source);
        obs_data_set_string(settings, "input", "");
        obs_source_update(source, settings);
        obs_data_release(settings);
        obs_source_release(source);
        blog(LOG_INFO, "[ZhiboLive] 活动流已结束或下线，重置拉流源 [%s]", source_name.c_str());
    }
}

std::string ZhiboObsSourceManager::get_current_rtmp_url() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return current_rtmp_url_;
}

bool ZhiboObsSourceManager::is_streaming() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return is_streaming_;
}

bool ZhiboObsSourceManager::is_stream_active() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!is_streaming_ || current_rtmp_url_.empty()) {
        return false;
    }

    std::string source_name = ZhiboConfig::instance().get_source_name();
    if (source_name.empty()) {
        source_name = "智播拉流源";
    }

    obs_source_t *source = obs_get_source_by_name(source_name.c_str());
    if (!source) {
        // 源已被手动删除或不存在
        is_streaming_ = false;
        current_rtmp_url_.clear();
        return false;
    }

    enum obs_media_state state = obs_source_media_get_state(source);
    obs_source_release(source);

    // PLAYING, BUFFERING, OPENING 代表正在活跃拉流或建立连接
    if (state == OBS_MEDIA_STATE_PLAYING || 
        state == OBS_MEDIA_STATE_BUFFERING || 
        state == OBS_MEDIA_STATE_OPENING) {
        return true;
    }

    // 若处于结束、停止或错误状态，判定为非活跃
    if (state == OBS_MEDIA_STATE_ENDED || 
        state == OBS_MEDIA_STATE_STOPPED || 
        state == OBS_MEDIA_STATE_ERROR) {
        return false;
    }

    return is_streaming_;
}

} // namespace zhibo
