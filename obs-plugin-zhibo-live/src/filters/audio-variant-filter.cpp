#include "audio-variant-filter.hpp"

#include <obs-module.h>
#include <media-io/audio-io.h>

#include <random>
#include <chrono>
#include <cmath>
#include <vector>

namespace zhibo {

#define S_MODE         "mode"
#define S_SEED         "seed"
#define S_GAIN_DB      "gain_db"
#define S_BALANCE      "balance"
#define S_EQ_LOW       "eq_low"
#define S_EQ_HIGH      "eq_high"
#define S_ANTI_CLIP    "anti_clip"

struct BiquadCoeffs {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f;
    float a1 = 0.0f, a2 = 0.0f;
};

struct BiquadState {
    float x1 = 0.0f, x2 = 0.0f;
    float y1 = 0.0f, y2 = 0.0f;

    inline float process(float in, const BiquadCoeffs &c) {
        float out = c.b0 * in + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2;
        x2 = x1;
        x1 = in;
        y2 = y1;
        y1 = out;
        return out;
    }

    inline void reset() {
        x1 = x2 = y1 = y2 = 0.0f;
    }
};

struct AudioVariantData {
    obs_source_t *source = nullptr;

    bool auto_mode = true;
    uint32_t seed = 0;

    double gain_db = 0.0;     // -0.5 ~ +0.5 dB
    double balance = 0.0;     // -0.01 ~ +0.01 (-1% ~ +1%)
    double eq_low_db = 0.0;   // -0.5 ~ +0.5 dB (低频 200Hz 搁架)
    double eq_high_db = 0.0;  // -0.5 ~ +0.5 dB (高频 6000Hz 搁架)
    bool anti_clip = true;    // 防破音安全保护

    // 运行时参数 (静态只读)
    float eff_gain_linear = 1.0f;
    float balance_factors[MAX_AV_PLANES] = {1.0f, 1.0f};

    BiquadCoeffs low_shelf;
    BiquadCoeffs high_shelf;
    BiquadState low_states[MAX_AV_PLANES];
    BiquadState high_states[MAX_AV_PLANES];
    uint32_t current_sample_rate = 48000;

    void update_biquad_coefficients(uint32_t sample_rate, double low_gain, double high_gain) {
        if (sample_rate == 0) sample_rate = 48000;
        current_sample_rate = sample_rate;

        // 1. 低频搁架 (200Hz Low Shelf) - Robert Bristow-Johnson EQ Cookbook
        {
            double f0 = 200.0;
            double A = std::pow(10.0, low_gain / 40.0);
            double w0 = 2.0 * 3.14159265358979323846 * f0 / sample_rate;
            double cosw0 = std::cos(w0);
            double sinw0 = std::sin(w0);
            double alpha = sinw0 / 2.0 * std::sqrt(2.0); // S = 1
            double sqrtA = std::sqrt(A);

            double a0 = (A + 1.0) + (A - 1.0) * cosw0 + 2.0 * sqrtA * alpha;
            low_shelf.b0 = (float)((A * ((A + 1.0) - (A - 1.0) * cosw0 + 2.0 * sqrtA * alpha)) / a0);
            low_shelf.b1 = (float)((2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0)) / a0);
            low_shelf.b2 = (float)((A * ((A + 1.0) - (A - 1.0) * cosw0 - 2.0 * sqrtA * alpha)) / a0);
            low_shelf.a1 = (float)((-2.0 * ((A - 1.0) + (A + 1.0) * cosw0)) / a0);
            low_shelf.a2 = (float)(((A + 1.0) + (A - 1.0) * cosw0 - 2.0 * sqrtA * alpha) / a0);
        }

        // 2. 高频搁架 (6000Hz High Shelf) - 避开人声主共振峰 (800~3500Hz)
        {
            double f0 = 6000.0;
            double A = std::pow(10.0, high_gain / 40.0);
            double w0 = 2.0 * 3.14159265358979323846 * f0 / sample_rate;
            double cosw0 = std::cos(w0);
            double sinw0 = std::sin(w0);
            double alpha = sinw0 / 2.0 * std::sqrt(2.0);
            double sqrtA = std::sqrt(A);

            double a0 = (A + 1.0) - (A - 1.0) * cosw0 + 2.0 * sqrtA * alpha;
            high_shelf.b0 = (float)((A * ((A + 1.0) + (A - 1.0) * cosw0 + 2.0 * sqrtA * alpha)) / a0);
            high_shelf.b1 = (float)((-2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0)) / a0);
            high_shelf.b2 = (float)((A * ((A + 1.0) + (A - 1.0) * cosw0 - 2.0 * sqrtA * alpha)) / a0);
            high_shelf.a1 = (float)((2.0 * ((A - 1.0) - (A + 1.0) * cosw0)) / a0);
            high_shelf.a2 = (float)(((A + 1.0) - (A - 1.0) * cosw0 - 2.0 * sqrtA * alpha) / a0);
        }
    }

    void calculate_effective_params(uint32_t sample_rate) {
        double eff_gain = gain_db;
        double eff_balance = balance;
        double eff_low = eq_low_db;
        double eff_high = eq_high_db;

        if (auto_mode) {
            uint32_t s = seed ? seed : 54321;
            std::mt19937 rng(s);
            std::uniform_real_distribution<double> dist_gain(-0.35, 0.35);
            std::uniform_real_distribution<double> dist_bal(-0.008, 0.008);
            std::uniform_real_distribution<double> dist_low(-0.25, 0.25);
            std::uniform_real_distribution<double> dist_high(-0.25, 0.25);

            eff_gain = dist_gain(rng);
            eff_balance = dist_bal(rng);
            eff_low = dist_low(rng);
            eff_high = dist_high(rng);
        }

        // 纯线性增益 (绝无非线性杂音)
        eff_gain_linear = (float)std::pow(10.0, eff_gain / 20.0);

        // 立体声声道平衡
        balance_factors[0] = (float)(1.0 - eff_balance);
        balance_factors[1] = (float)(1.0 + eff_balance);
        for (int c = 2; c < MAX_AV_PLANES; ++c) {
            balance_factors[c] = 1.0f;
        }

        update_biquad_coefficients(sample_rate, eff_low, eff_high);
    }
};

static const char *audio_variant_get_name(void *) {
    return "OBS Channel Variant - Audio (渠道去重-音频)";
}

static void audio_variant_destroy(void *data) {
    auto *filter = static_cast<AudioVariantData *>(data);
    delete filter;
}

static void audio_variant_update(void *data, obs_data_t *settings) {
    auto *filter = static_cast<AudioVariantData *>(data);
    if (!filter || !settings) return;

    filter->auto_mode = obs_data_get_bool(settings, S_MODE);
    filter->seed = (uint32_t)obs_data_get_int(settings, S_SEED);
    if (filter->seed == 0) {
        filter->seed = (uint32_t)std::chrono::system_clock::now().time_since_epoch().count();
        obs_data_set_int(settings, S_SEED, filter->seed);
    }

    filter->gain_db = obs_data_get_double(settings, S_GAIN_DB);
    filter->balance = obs_data_get_double(settings, S_BALANCE);
    filter->eq_low_db = obs_data_get_double(settings, S_EQ_LOW);
    filter->eq_high_db = obs_data_get_double(settings, S_EQ_HIGH);
    filter->anti_clip = obs_data_get_bool(settings, S_ANTI_CLIP);

    uint32_t sr = 48000;
    obs_audio_info aoi;
    if (obs_get_audio_info(&aoi) && aoi.samples_per_sec > 0) {
        sr = aoi.samples_per_sec;
    }
    filter->calculate_effective_params(sr);
}

static void *audio_variant_create(obs_data_t *settings, obs_source_t *source) {
    auto *filter = new AudioVariantData();
    filter->source = source;
    audio_variant_update(filter, settings);
    return filter;
}

static struct obs_audio_data *audio_variant_filter(void *data, struct obs_audio_data *audio) {
    auto *filter = static_cast<AudioVariantData *>(data);
    if (!filter || !audio || audio->frames == 0) return audio;

    size_t channels = audio_output_get_channels(obs_get_audio());
    if (channels > MAX_AV_PLANES) channels = MAX_AV_PLANES;

    const float gain = filter->eff_gain_linear;
    const bool anti_clip = filter->anti_clip;

    for (size_t c = 0; c < channels; ++c) {
        float *samples = (float *)audio->data[c];
        if (!samples) continue;

        const float bal = filter->balance_factors[c];
        const float total_gain = gain * bal;
        auto &low_st = filter->low_states[c];
        auto &high_st = filter->high_states[c];
        const auto &low_c = filter->low_shelf;
        const auto &high_c = filter->high_shelf;

        for (uint32_t i = 0; i < audio->frames; ++i) {
            float s = samples[i];

            // 1. 平滑双二阶频响微扰滤波 (不触碰中频共振峰，保持真人自然厚度)
            s = low_st.process(s, low_c);
            s = high_st.process(s, high_c);

            // 2. 线性音量与平衡调控
            s *= total_gain;

            // 3. 防数字削顶软保护 (仅在接近 0dBFS 时触发平滑限制，正常人声不失真)
            if (anti_clip) {
                float abs_s = std::abs(s);
                if (abs_s > 0.98f) {
                    float sign = (s >= 0.0f) ? 1.0f : -1.0f;
                    s = sign * (0.98f + 0.015f * std::tanh((abs_s - 0.98f) / 0.015f));
                }
            }

            samples[i] = s;
        }
    }

    return audio;
}

static void audio_variant_defaults(obs_data_t *settings) {
    obs_data_set_default_bool(settings, S_MODE, true);
    uint32_t seed = (uint32_t)std::chrono::system_clock::now().time_since_epoch().count();
    obs_data_set_default_int(settings, S_SEED, seed);

    obs_data_set_default_double(settings, S_GAIN_DB, 0.0);
    obs_data_set_default_double(settings, S_BALANCE, 0.0);
    obs_data_set_default_double(settings, S_EQ_LOW, 0.0);
    obs_data_set_default_double(settings, S_EQ_HIGH, 0.0);
    obs_data_set_default_bool(settings, S_ANTI_CLIP, true);
}

static bool on_audio_mode_changed(obs_properties_t *props, obs_property_t *, obs_data_t *settings) {
    bool auto_mode = obs_data_get_bool(settings, S_MODE);
    obs_property_set_visible(obs_properties_get(props, S_GAIN_DB), !auto_mode);
    obs_property_set_visible(obs_properties_get(props, S_BALANCE), !auto_mode);
    obs_property_set_visible(obs_properties_get(props, S_EQ_LOW), !auto_mode);
    obs_property_set_visible(obs_properties_get(props, S_EQ_HIGH), !auto_mode);
    return true;
}

static bool on_audio_reseed_clicked(obs_properties_t *, obs_property_t *, void *data) {
    auto *filter = static_cast<AudioVariantData *>(data);
    if (!filter || !filter->source) return false;

    obs_data_t *settings = obs_source_get_settings(filter->source);
    uint32_t new_seed = (uint32_t)std::chrono::system_clock::now().time_since_epoch().count();
    obs_data_set_int(settings, S_SEED, new_seed);
    obs_source_update(filter->source, settings);
    obs_data_release(settings);
    return true;
}

static obs_properties_t *audio_variant_properties(void *data) {
    obs_properties_t *props = obs_properties_create();

    obs_property_t *p_mode = obs_properties_add_bool(props, S_MODE, "自动随机去重模式 (Auto Random)");
    obs_property_set_modified_callback(p_mode, on_audio_mode_changed);

    obs_properties_add_int(props, S_SEED, "随机种子 (Seed)", 0, 2147483647, 1);
    obs_properties_add_button(props, "btn_reseed", "生成新随机参数 (Regenerate)", on_audio_reseed_clicked);

    obs_properties_add_float_slider(props, S_GAIN_DB, "音量增益 (-0.5dB ~ +0.5dB)", -0.5, 0.5, 0.05);
    obs_properties_add_float_slider(props, S_BALANCE, "声道平衡 (-1.0% ~ +1.0%)", -0.01, 0.01, 0.001);
    obs_properties_add_float_slider(props, S_EQ_LOW, "低频微扰 (-0.5dB ~ +0.5dB @ 200Hz)", -0.5, 0.5, 0.05);
    obs_properties_add_float_slider(props, S_EQ_HIGH, "高频微扰 (-0.5dB ~ +0.5dB @ 6kHz)", -0.5, 0.5, 0.05);
    obs_properties_add_bool(props, S_ANTI_CLIP, "自然人声防削顶保护 (Anti-Clipping Guard)");

    return props;
}

} // namespace zhibo

static struct obs_source_info create_audio_variant_info() {
    struct obs_source_info info = {};
    info.id             = "zhibo_audio_variant_filter";
    info.type           = OBS_SOURCE_TYPE_FILTER;
    info.output_flags   = OBS_SOURCE_AUDIO;
    info.get_name       = zhibo::audio_variant_get_name;
    info.create         = zhibo::audio_variant_create;
    info.destroy        = zhibo::audio_variant_destroy;
    info.get_defaults   = zhibo::audio_variant_defaults;
    info.get_properties = zhibo::audio_variant_properties;
    info.update         = zhibo::audio_variant_update;
    info.filter_audio   = zhibo::audio_variant_filter;
    return info;
}

extern "C" struct obs_source_info zhibo_audio_variant_filter_info = create_audio_variant_info();
