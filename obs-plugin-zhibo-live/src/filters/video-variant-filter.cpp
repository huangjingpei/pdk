#include "video-variant-filter.hpp"

#include <obs-module.h>
#include <graphics/graphics.h>
#include <graphics/vec2.h>
#include <graphics/vec3.h>

#include <random>
#include <chrono>
#include <cmath>
#include <string>

namespace zhibo {

static const char *s_video_effect_code = R"(
uniform float4x4 ViewProj;
uniform texture2d image;

uniform float2 scale_val;
uniform float2 pan_val;
uniform float rotation_rad;
uniform float brightness_val;
uniform float contrast_val;
uniform float saturation_val;
uniform float gamma_val;
uniform float3 color_temp_factor;

sampler_state def_sampler {
    Filter   = Linear;
    AddressU = Clamp;
    AddressV = Clamp;
};

struct VertInOut {
    float4 pos : POSITION;
    float2 uv  : TEXCOORD0;
};

VertInOut VSDefault(VertInOut vert_in)
{
    VertInOut vert_out;
    vert_out.pos = mul(float4(vert_in.pos.xyz, 1.0), ViewProj);
    vert_out.uv  = vert_in.uv;
    return vert_out;
}

float4 PSDraw(VertInOut vert_in) : TARGET
{
    // 1. 围绕画面中心缩放 (1.000 ~ 1.020)，并叠加微平移 (-0.5% ~ +0.5%)
    float2 uv = (vert_in.uv - 0.5) / scale_val + 0.5 + pan_val;

    // 2. 围绕画面中心进行微旋转 (-0.15° ~ +0.15°)
    if (abs(rotation_rad) > 0.00001) {
        float2 offset = uv - 0.5;
        float c = cos(rotation_rad);
        float s = sin(rotation_rad);
        uv = float2(offset.x * c - offset.y * s, offset.x * s + offset.y * c) + 0.5;
    }

    // 3. 纹理采样
    float4 color = image.Sample(def_sampler, uv);
    float3 rgb = color.rgb;

    // 4. 色温 RGB 微调 (-150K ~ +150K)
    rgb *= color_temp_factor;

    // 5. 亮度微调 (-1.5% ~ +1.5%)
    rgb += brightness_val;

    // 6. 对比度微调 (0.98 ~ 1.02，以中灰点 0.5 为基准)
    rgb = (rgb - 0.5) * contrast_val + 0.5;

    // 7. 饱和度微调 (0.98 ~ 1.02，Rec.709 亮度权重)
    float luma = dot(rgb, float3(0.2126, 0.7152, 0.0722));
    rgb = lerp(float3(luma, luma, luma), rgb, saturation_val);

    // 8. 伽马微调 (0.99 ~ 1.01)
    rgb = pow(max(rgb, 0.0), 1.0 / max(gamma_val, 0.001));

    return float4(saturate(rgb), color.a);
}

technique Draw
{
    pass
    {
        vertex_shader = VSDefault(vert_in);
        pixel_shader  = PSDraw(vert_in);
    }
}
)";

#define S_MODE               "mode"
#define S_SEED               "seed"
#define S_ZOOM               "zoom"
#define S_PAN_X              "pan_x"
#define S_PAN_Y              "pan_y"
#define S_ENABLE_ROTATION    "enable_rotation"
#define S_ROTATION           "rotation"
#define S_BRIGHTNESS         "brightness"
#define S_CONTRAST           "contrast"
#define S_SATURATION         "saturation"
#define S_GAMMA              "gamma"
#define S_COLOR_TEMP         "color_temp"

struct VideoVariantData {
    obs_source_t *source = nullptr;
    gs_effect_t *effect = nullptr;

    gs_eparam_t *param_scale = nullptr;
    gs_eparam_t *param_pan = nullptr;
    gs_eparam_t *param_rotation = nullptr;
    gs_eparam_t *param_brightness = nullptr;
    gs_eparam_t *param_contrast = nullptr;
    gs_eparam_t *param_saturation = nullptr;
    gs_eparam_t *param_gamma = nullptr;
    gs_eparam_t *param_color_temp = nullptr;

    bool auto_mode = true;
    uint32_t seed = 0;

    double zoom = 1.010;
    double pan_x = 0.0;
    double pan_y = 0.0;
    bool enable_rotation = false;
    double rotation_deg = 0.0;
    double brightness = 0.0;
    double contrast = 1.0;
    double saturation = 1.0;
    double gamma = 1.0;
    double color_temp_k = 0.0;

    // 运行时 Shader 参数 (初始化/配置变更时一次性计算，每帧只读)
    struct vec2 scale_vec;
    struct vec2 pan_vec;
    float rotation_rad = 0.0f;
    float brightness_val = 0.0f;
    float contrast_val = 1.0f;
    float saturation_val = 1.0f;
    float gamma_val = 1.0f;
    struct vec3 color_temp_rgb;

    void calculate_effective_params() {
        double eff_zoom = zoom;
        double eff_pan_x = pan_x;
        double eff_pan_y = pan_y;
        double eff_rot = enable_rotation ? rotation_deg : 0.0;
        double eff_bright = brightness;
        double eff_contrast = contrast;
        double eff_sat = saturation;
        double eff_gamma = gamma;
        double eff_temp = color_temp_k;

        if (auto_mode) {
            uint32_t s = seed ? seed : 12345;
            std::mt19937 rng(s);
            std::uniform_real_distribution<double> dist_zoom(1.008, 1.018);
            std::uniform_real_distribution<double> dist_pan(-0.0035, 0.0035);
            std::uniform_real_distribution<double> dist_rot(-0.08, 0.08);
            std::uniform_real_distribution<double> dist_bright(-0.010, 0.010);
            std::uniform_real_distribution<double> dist_contrast(0.988, 1.012);
            std::uniform_real_distribution<double> dist_sat(0.988, 1.012);
            std::uniform_real_distribution<double> dist_gamma(0.993, 1.007);
            std::uniform_real_distribution<double> dist_temp(-100.0, 100.0);

            eff_zoom = dist_zoom(rng);
            eff_pan_x = dist_pan(rng);
            eff_pan_y = dist_pan(rng);
            eff_rot = enable_rotation ? dist_rot(rng) : 0.0;
            eff_bright = dist_bright(rng);
            eff_contrast = dist_contrast(rng);
            eff_sat = dist_sat(rng);
            eff_gamma = dist_gamma(rng);
            eff_temp = dist_temp(rng);
        }

        vec2_set(&scale_vec, (float)eff_zoom, (float)eff_zoom);
        vec2_set(&pan_vec, (float)eff_pan_x, (float)eff_pan_y);
        rotation_rad = (float)(eff_rot * 3.14159265358979323846 / 180.0);
        brightness_val = (float)eff_bright;
        contrast_val = (float)eff_contrast;
        saturation_val = (float)eff_sat;
        gamma_val = (float)eff_gamma;

        // 色温 RGB 转换系数 (微调)
        float r_factor = 1.0f;
        float b_factor = 1.0f;
        if (eff_temp > 0.0) {
            float t = (float)(eff_temp / 150.0);
            r_factor += t * 0.015f;
            b_factor -= t * 0.015f;
        } else if (eff_temp < 0.0) {
            float t = (float)(-eff_temp / 150.0);
            r_factor -= t * 0.015f;
            b_factor += t * 0.015f;
        }
        vec3_set(&color_temp_rgb, r_factor, 1.0f, b_factor);
    }
};

static const char *video_variant_get_name(void *) {
    return "OBS Channel Variant - Video (渠道去重-视频)";
}

static void video_variant_destroy(void *data) {
    auto *filter = static_cast<VideoVariantData *>(data);
    if (!filter) return;

    if (filter->effect) {
        obs_enter_graphics();
        gs_effect_destroy(filter->effect);
        obs_leave_graphics();
        filter->effect = nullptr;
    }

    delete filter;
}

static void video_variant_update(void *data, obs_data_t *settings) {
    auto *filter = static_cast<VideoVariantData *>(data);
    if (!filter || !settings) return;

    filter->auto_mode = obs_data_get_bool(settings, S_MODE);
    filter->seed = (uint32_t)obs_data_get_int(settings, S_SEED);
    if (filter->seed == 0) {
        filter->seed = (uint32_t)std::chrono::system_clock::now().time_since_epoch().count();
        obs_data_set_int(settings, S_SEED, filter->seed);
    }

    filter->zoom = obs_data_get_double(settings, S_ZOOM);
    filter->pan_x = obs_data_get_double(settings, S_PAN_X);
    filter->pan_y = obs_data_get_double(settings, S_PAN_Y);
    filter->enable_rotation = obs_data_get_bool(settings, S_ENABLE_ROTATION);
    filter->rotation_deg = obs_data_get_double(settings, S_ROTATION);
    filter->brightness = obs_data_get_double(settings, S_BRIGHTNESS);
    filter->contrast = obs_data_get_double(settings, S_CONTRAST);
    filter->saturation = obs_data_get_double(settings, S_SATURATION);
    filter->gamma = obs_data_get_double(settings, S_GAMMA);
    filter->color_temp_k = obs_data_get_double(settings, S_COLOR_TEMP);

    filter->calculate_effective_params();
}

static void *video_variant_create(obs_data_t *settings, obs_source_t *source) {
    auto *filter = new VideoVariantData();
    filter->source = source;

    obs_enter_graphics();
    char *errors = nullptr;
    filter->effect = gs_effect_create(s_video_effect_code, "video_variant_effect", &errors);
    if (!filter->effect) {
        blog(LOG_ERROR, "[VideoVariantFilter] 编译视频 Shader 失败: %s", errors ? errors : "未知错误");
        bfree(errors);
    } else {
        filter->param_scale = gs_effect_get_param_by_name(filter->effect, "scale_val");
        filter->param_pan = gs_effect_get_param_by_name(filter->effect, "pan_val");
        filter->param_rotation = gs_effect_get_param_by_name(filter->effect, "rotation_rad");
        filter->param_brightness = gs_effect_get_param_by_name(filter->effect, "brightness_val");
        filter->param_contrast = gs_effect_get_param_by_name(filter->effect, "contrast_val");
        filter->param_saturation = gs_effect_get_param_by_name(filter->effect, "saturation_val");
        filter->param_gamma = gs_effect_get_param_by_name(filter->effect, "gamma_val");
        filter->param_color_temp = gs_effect_get_param_by_name(filter->effect, "color_temp_factor");
    }
    obs_leave_graphics();

    video_variant_update(filter, settings);
    return filter;
}

static void video_variant_render(void *data, gs_effect_t *) {
    auto *filter = static_cast<VideoVariantData *>(data);
    if (!filter || !filter->effect) {
        obs_source_skip_video_filter(filter->source);
        return;
    }

    if (!obs_source_process_filter_begin(filter->source, GS_RGBA, OBS_ALLOW_DIRECT_RENDERING)) {
        return;
    }

    gs_effect_set_vec2(filter->param_scale, &filter->scale_vec);
    gs_effect_set_vec2(filter->param_pan, &filter->pan_vec);
    gs_effect_set_float(filter->param_rotation, filter->rotation_rad);
    gs_effect_set_float(filter->param_brightness, filter->brightness_val);
    gs_effect_set_float(filter->param_contrast, filter->contrast_val);
    gs_effect_set_float(filter->param_saturation, filter->saturation_val);
    gs_effect_set_float(filter->param_gamma, filter->gamma_val);
    gs_effect_set_vec3(filter->param_color_temp, &filter->color_temp_rgb);

    obs_source_process_filter_end(filter->source, filter->effect, 0, 0);
}

static void video_variant_defaults(obs_data_t *settings) {
    obs_data_set_default_bool(settings, S_MODE, true);
    uint32_t seed = (uint32_t)std::chrono::system_clock::now().time_since_epoch().count();
    obs_data_set_default_int(settings, S_SEED, seed);

    obs_data_set_default_double(settings, S_ZOOM, 1.010);
    obs_data_set_default_double(settings, S_PAN_X, 0.0);
    obs_data_set_default_double(settings, S_PAN_Y, 0.0);
    obs_data_set_default_bool(settings, S_ENABLE_ROTATION, false);
    obs_data_set_default_double(settings, S_ROTATION, 0.0);
    obs_data_set_default_double(settings, S_BRIGHTNESS, 0.0);
    obs_data_set_default_double(settings, S_CONTRAST, 1.0);
    obs_data_set_default_double(settings, S_SATURATION, 1.0);
    obs_data_set_default_double(settings, S_GAMMA, 1.0);
    obs_data_set_default_double(settings, S_COLOR_TEMP, 0.0);
}

static bool on_mode_changed(obs_properties_t *props, obs_property_t *, obs_data_t *settings) {
    bool auto_mode = obs_data_get_bool(settings, S_MODE);
    obs_property_set_visible(obs_properties_get(props, S_ZOOM), !auto_mode);
    obs_property_set_visible(obs_properties_get(props, S_PAN_X), !auto_mode);
    obs_property_set_visible(obs_properties_get(props, S_PAN_Y), !auto_mode);
    obs_property_set_visible(obs_properties_get(props, S_ROTATION), !auto_mode);
    obs_property_set_visible(obs_properties_get(props, S_BRIGHTNESS), !auto_mode);
    obs_property_set_visible(obs_properties_get(props, S_CONTRAST), !auto_mode);
    obs_property_set_visible(obs_properties_get(props, S_SATURATION), !auto_mode);
    obs_property_set_visible(obs_properties_get(props, S_GAMMA), !auto_mode);
    obs_property_set_visible(obs_properties_get(props, S_COLOR_TEMP), !auto_mode);
    return true;
}

static bool on_reseed_clicked(obs_properties_t *, obs_property_t *, void *data) {
    auto *filter = static_cast<VideoVariantData *>(data);
    if (!filter || !filter->source) return false;

    obs_data_t *settings = obs_source_get_settings(filter->source);
    uint32_t new_seed = (uint32_t)std::chrono::system_clock::now().time_since_epoch().count();
    obs_data_set_int(settings, S_SEED, new_seed);
    obs_source_update(filter->source, settings);
    obs_data_release(settings);
    return true;
}

static obs_properties_t *video_variant_properties(void *data) {
    obs_properties_t *props = obs_properties_create();

    obs_property_t *p_mode = obs_properties_add_bool(props, S_MODE, "自动随机去重模式 (Auto Random)");
    obs_property_set_modified_callback(p_mode, on_mode_changed);

    obs_properties_add_int(props, S_SEED, "随机种子 (Seed)", 0, 2147483647, 1);
    obs_properties_add_button(props, "btn_reseed", "生成新随机参数 (Regenerate)", on_reseed_clicked);

    obs_properties_add_float_slider(props, S_ZOOM, "画面缩放 (1.000 ~ 1.020)", 1.000, 1.020, 0.001);
    obs_properties_add_float_slider(props, S_PAN_X, "水平平移 (-0.5% ~ +0.5%)", -0.005, 0.005, 0.0005);
    obs_properties_add_float_slider(props, S_PAN_Y, "垂直平移 (-0.5% ~ +0.5%)", -0.005, 0.005, 0.0005);
    obs_properties_add_bool(props, S_ENABLE_ROTATION, "启用微角度旋转 (默认关闭)");
    obs_properties_add_float_slider(props, S_ROTATION, "微角度旋转 (-0.15° ~ +0.15°)", -0.15, 0.15, 0.01);

    obs_properties_add_float_slider(props, S_BRIGHTNESS, "亮度偏置 (-1.5% ~ +1.5%)", -0.015, 0.015, 0.001);
    obs_properties_add_float_slider(props, S_CONTRAST, "对比度微调 (0.98 ~ 1.02)", 0.98, 1.02, 0.001);
    obs_properties_add_float_slider(props, S_SATURATION, "饱和度微调 (0.98 ~ 1.02)", 0.98, 1.02, 0.001);
    obs_properties_add_float_slider(props, S_GAMMA, "伽马微调 (0.99 ~ 1.01)", 0.99, 1.01, 0.001);
    obs_properties_add_float_slider(props, S_COLOR_TEMP, "色温偏移 (-150K ~ +150K)", -150.0, 150.0, 1.0);

    return props;
}

} // namespace zhibo

static struct obs_source_info create_video_variant_info() {
    struct obs_source_info info = {};
    info.id             = "zhibo_video_variant_filter";
    info.type           = OBS_SOURCE_TYPE_FILTER;
    info.output_flags   = OBS_SOURCE_VIDEO | OBS_SOURCE_SRGB;
    info.get_name       = zhibo::video_variant_get_name;
    info.create         = zhibo::video_variant_create;
    info.destroy        = zhibo::video_variant_destroy;
    info.get_defaults   = zhibo::video_variant_defaults;
    info.get_properties = zhibo::video_variant_properties;
    info.update         = zhibo::video_variant_update;
    info.video_render   = zhibo::video_variant_render;
    return info;
}

extern "C" struct obs_source_info zhibo_video_variant_filter_info = create_video_variant_info();
