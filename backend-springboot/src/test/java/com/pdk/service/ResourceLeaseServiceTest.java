package com.pdk.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResourceLeaseServiceTest {
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOperations;

    @Test
    void createStoresLeaseWithConfiguredTtl() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        ResourceLeaseService service = service();

        service.create("TRACE-1", new ResourceLeaseService.LeaseInfo(9L, "13800138000", "slot-9", "DETAIL_QUERY"));

        verify(hashOperations).putAll(eq("pdk:lease:TRACE-1"), any(Map.class));
        verify(redisTemplate).expire("pdk:lease:TRACE-1", Duration.ofSeconds(300));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void consumeParsesAtomicLuaResultAndMissingLease() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of("tokenId", "9", "phone", "13800138000", "accountAlias", "slot-9", "actionType", "DETAIL_QUERY"))
                .thenReturn(List.of());
        ResourceLeaseService service = service();

        ResourceLeaseService.LeaseInfo lease = service.consume("TRACE-1", "13800138000");
        assertNotNull(lease);
        assertEquals(9L, lease.tokenId());
        assertEquals("slot-9", lease.accountAlias());
        assertNull(service.consume("TRACE-MISSING", "13800138000"));
    }

    private ResourceLeaseService service() {
        ResourceLeaseService service = new ResourceLeaseService(redisTemplate);
        ReflectionTestUtils.setField(service, "leaseSeconds", 300L);
        return service;
    }
}
