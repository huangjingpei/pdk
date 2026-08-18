package com.pdk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pdk.domain.entity.SystemConfig;
import com.pdk.mapper.SystemConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台系统配置服务。
 * 设计：通用 KV 表 + 内存缓存；读取时若缓存/库均无记录，回退到 {@link ConfigKeys} 默认值。
 * 为避免应用启动时因表未就绪而失败，不采用 @PostConstruct 预热，改为懒加载。
 */
@Service
@RequiredArgsConstructor
public class SystemConfigService {
    private final SystemConfigMapper systemConfigMapper;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** 返回全部配置（含当前值），供管理后台「系统设置」页渲染。 */
    public List<SystemConfig> listAll() {
        return systemConfigMapper.selectList(null);
    }

    /** 批量保存（仅更新配置值），并按 key 刷新缓存。 */
    public void saveConfigs(List<SystemConfig> items) {
        if (items == null) return;
        for (SystemConfig item : items) {
            if (item.getConfigKey() == null || item.getConfigValue() == null) continue;
            SystemConfig toUpdate = new SystemConfig();
            toUpdate.setConfigValue(item.getConfigValue());
            systemConfigMapper.update(toUpdate, new LambdaUpdateWrapper<SystemConfig>()
                    .eq(SystemConfig::getConfigKey, item.getConfigKey()));
            cache.put(item.getConfigKey(), item.getConfigValue());
        }
    }

    /**
     * 读取单个配置值。优先缓存，未命中查库，库无记录回退默认值。
     * 任何异常都安全回退到默认值，不会让调用方崩溃。
     */
    public String getValue(String key, String defaultVal) {
        String cached = cache.get(key);
        if (cached != null) return cached;
        try {
            SystemConfig c = systemConfigMapper.selectOne(new LambdaQueryWrapper<SystemConfig>()
                    .eq(SystemConfig::getConfigKey, key));
            if (c != null && c.getConfigValue() != null) {
                cache.put(key, c.getConfigValue());
                return c.getConfigValue();
            }
        } catch (Exception ignored) {
            // 表尚未初始化等情况下回退默认
        }
        cache.put(key, defaultVal);
        return defaultVal;
    }

    /** 失效缓存（外部修改后如需立即生效可调用；正常保存已自动刷新）。 */
    public void invalidate(String key) {
        cache.remove(key);
    }
}
