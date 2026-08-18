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

    private String key(String phone) {
        return "pdk:device:bind:" + phone;
    }

    public String get(String phone) {
        try {
            return redisTemplate.opsForValue().get(key(phone));
        } catch (RuntimeException e) {
            log.warn("Redis 暂不可用，设备校验回退到 MySQL: {}", e.getMessage());
            return null;
        }
    }

    public void bind(String phone, String deviceId) {
        try {
            redisTemplate.opsForValue().set(key(phone), deviceId, Duration.ofMinutes(30));
        } catch (RuntimeException e) {
            log.warn("Redis 暂不可用，设备绑定仅保存在 MySQL: {}", e.getMessage());
        }
    }

    public void unbind(String phone) {
        try {
            redisTemplate.delete(key(phone));
        } catch (RuntimeException e) {
            log.warn("Redis 暂不可用，已完成 MySQL 解绑: {}", e.getMessage());
        }
    }
}
