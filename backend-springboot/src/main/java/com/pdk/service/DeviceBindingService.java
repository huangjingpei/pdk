package com.pdk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceBindingService {
    private final StringRedisTemplate redisTemplate;

    private String key(Long bizId, Long userId) {
        return "pdk:device:bind:" + bizId + ":" + userId;
    }

    public String get(Long bizId, Long userId) {
        try {
            return redisTemplate.opsForValue().get(key(bizId, userId));
        } catch (RuntimeException e) {
            log.warn("Redis 暂不可用，设备校验回退到 MySQL: {}", e.getMessage());
            return null;
        }
    }

    public void bind(Long bizId, Long userId, String deviceId) {
        try {
            redisTemplate.opsForValue().set(key(bizId, userId), deviceId, Duration.ofMinutes(30));
        } catch (RuntimeException e) {
            log.warn("Redis 暂不可用，设备绑定仅保存在 MySQL: {}", e.getMessage());
        }
    }

    public void unbind(Long bizId, Long userId) {
        try {
            redisTemplate.delete(key(bizId, userId));
        } catch (RuntimeException e) {
            log.warn("Redis 暂不可用，已完成 MySQL 解绑: {}", e.getMessage());
        }
    }
}
