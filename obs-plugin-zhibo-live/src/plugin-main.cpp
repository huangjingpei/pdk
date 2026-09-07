#include <obs-module.h>
#include <obs-frontend-api.h>
#include <QMainWindow>
#include <QAction>

#include "zhibo-config.hpp"
#include "zhibo-auth-client.hpp"
#include "zhibo-stream-poller.hpp"
#include "zhibo-obs-source-manager.hpp"
#include "ui/activation-dialog.hpp"
#include "filters/video-variant-filter.hpp"
#include "filters/audio-variant-filter.hpp"

OBS_DECLARE_MODULE()
OBS_MODULE_USE_DEFAULT_LOCALE("obs-zhibo-live", "zh-CN")

MODULE_EXPORT const char *obs_module_description(void) {
    return "PDK 智播云控主动 RTMP 拉流与设备许可证授权插件";
}

MODULE_EXPORT const char *obs_module_name(void) {
    return "obs-zhibo-live";
}

namespace {

void on_tools_menu_clicked(void *private_data) {
    Q_UNUSED(private_data);
    zhibo::ActivationDialog::show_dialog();
}

void on_frontend_event(enum obs_frontend_event event, void *private_data) {
    Q_UNUSED(private_data);

    if (event == OBS_FRONTEND_EVENT_SCENE_CHANGED) {
        if (zhibo::ZhiboConfig::instance().is_channel_variant_enabled()) {
            zhibo::ZhiboObsSourceManager::instance().sync_channel_variant_filters(true);
        }
        return;
    }

    if (event == OBS_FRONTEND_EVENT_FINISHED_LOADING) {
        blog(LOG_INFO, "[ZhiboLive] OBS 启动加载完成，开始执行智播云控插件初始化与激活检测");

        auto &cfg = zhibo::ZhiboConfig::instance();
        auto &auth = zhibo::ZhiboAuthClient::instance();

        // 自动同步拉流源去重变异滤镜
        if (cfg.is_channel_variant_enabled()) {
            zhibo::ZhiboObsSourceManager::instance().sync_channel_variant_filters(true);
        }

        // 1. 若本地完全没有配置手机号和密码，直接弹出激活窗口
        if (!cfg.is_configured()) {
            blog(LOG_INFO, "[ZhiboLive] 本地尚未配置账号信息，弹出激活窗口引导用户激活");
            zhibo::ActivationDialog::show_dialog(nullptr, "首次使用，请输入手机号、密码与卡密激活本台设备");
            return;
        }

        // 2. 本地有凭据，执行后台静默验证登录
        blog(LOG_INFO, "[ZhiboLive] 发现本地凭据，开始后台静默登录 (Phone: %s, DeviceId: %s)",
             cfg.get_phone().c_str(), auth.get_or_create_device_id().c_str());

        zhibo::AuthResult res = auth.check_session();
        if (res.success) {
            blog(LOG_INFO, "[ZhiboLive] 后台静默登录成功！许可证状态: %s, 到期时间: %s, 剩余次数: %d",
                 res.license_status.c_str(), res.expire_at.c_str(), res.remaining_calls);

            // 登录成功，启动后台推流探测轮询
            zhibo::ZhiboStreamPoller::instance().start();
        } else {
            blog(LOG_WARNING, "[ZhiboLive] 后台静默登录失败 [%d]: %s", res.code, res.message.c_str());

            // 失败时（如 40380 未激活 / 密码已修改 / 许可证过期），自动弹出激活对话框并呈现错误原因
            QString err_msg;
            if (res.need_card_key) {
                err_msg = "【需要卡密】当前设备未激活，请输入分配给该账号的卡密完成激活。";
            } else {
                err_msg = QString::fromStdString("登录失败 [" + std::to_string(res.code) + "]: " + res.message + "，请核对信息并重新激活。");
            }
            zhibo::ActivationDialog::show_dialog(nullptr, err_msg);
        }
    }
}

} // namespace

bool obs_module_load(void) {
    uint32_t ver = obs_get_version();
    uint8_t major = (uint8_t)(ver >> 24);
    uint8_t minor = (uint8_t)(ver >> 16);
    blog(LOG_INFO, "[ZhiboLive] 正在加载 PDK 智播云控插件 (obs-zhibo-live v1.0.0, 宿主 OBS: v%u.%u / %s)",
         major, minor, obs_get_version_string());

    // 运行环境与版本兼容守卫：本插件采用 Qt6 开发，必须运行在 OBS 28+ 环境中 (推荐 OBS 29/30+)
    if (major < 28) {
        blog(LOG_ERROR, "[ZhiboLive] 宿主 OBS 版本过低 (v%u.%u)，插件需要 OBS 29.0 或更高版本以支持 Qt6 界面", major, minor);
        return false;
    }

    // 预热配置与生成设备标识
    zhibo::ZhiboConfig::instance().load();
    zhibo::ZhiboAuthClient::instance().get_or_create_device_id();

    // 注册渠道变异去重音视频滤镜 (支持作为独立滤镜添加或拉流源自动挂载)
    obs_register_source(&zhibo_video_variant_filter_info);
    obs_register_source(&zhibo_audio_variant_filter_info);

    // 注册 OBS 前端事件监听
    obs_frontend_add_event_callback(on_frontend_event, nullptr);

    // 在 OBS 顶部“工具 (Tools)”菜单注册设置入口
    obs_frontend_add_tools_menu_item("智播云控拉流设置 / 激活", on_tools_menu_clicked, nullptr);

    return true;
}

void obs_module_unload(void) {
    blog(LOG_INFO, "[ZhiboLive] 正在卸载 PDK 智播云控插件");

    // 停止轮询
    zhibo::ZhiboStreamPoller::instance().stop();
    zhibo::ZhiboObsSourceManager::instance().clear_active_stream();

    // 保存最新配置
    zhibo::ZhiboConfig::instance().save();
}
