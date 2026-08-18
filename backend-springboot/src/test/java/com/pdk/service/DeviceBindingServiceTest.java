package com.pdk.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceBindingServiceTest {
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @Test
    void readsAndWritesDeviceBinding() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("pdk:device:bind:13800138000")).thenReturn("DEVICE-A");
        DeviceBindingService service = new DeviceBindingService(redisTemplate);

        assertEquals("DEVICE-A", service.get("13800138000"));
        service.bind("13800138000", "DEVICE-A");
        service.unbind("13800138000");

        verify(valueOperations).set(eq("pdk:device:bind:13800138000"), eq("DEVICE-A"), any(java.time.Duration.class));
        verify(redisTemplate).delete("pdk:device:bind:13800138000");
    }

    @Test
    void redisFailureFallsBackWithoutBreakingLogin() {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        DeviceBindingService service = new DeviceBindingService(redisTemplate);

        assertNull(service.get("13800138000"));
        assertDoesNotThrow(() -> service.bind("13800138000", "DEVICE-A"));
        when(redisTemplate.delete(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertDoesNotThrow(() -> service.unbind("13800138000"));
    }
}
