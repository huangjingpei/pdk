package com.pdk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ResourceLeaseService {
    private static final DefaultRedisScript<List> CONSUME_SCRIPT = new DefaultRedisScript<>(
            "local owner = redis.call('HGET', KEYS[1], 'phone'); " +
                    "if not owner or owner ~= ARGV[1] then return {}; end; " +
                    "local values = redis.call('HGETALL', KEYS[1]); " +
                    "if #values > 0 then redis.call('DEL', KEYS[1]); end; return values;",
            List.class
    );

    private final StringRedisTemplate redisTemplate;

    @Value("${pdk.security.token-lease-seconds:300}")
    private long leaseSeconds;

    public void create(String traceId, LeaseInfo lease) {
        String key = key(traceId);
        Map<String, String> values = new HashMap<>();
        values.put("tokenId", lease.tokenId().toString());
        values.put("phone", lease.phone());
        values.put("accountAlias", lease.accountAlias());
        values.put("actionType", lease.actionType());
        values.put("assignmentId", lease.assignmentId() == null ? "" : lease.assignmentId().toString());
        values.put("slotIndex", lease.slotIndex() == null ? "1" : lease.slotIndex().toString());
        redisTemplate.opsForHash().putAll(key, values);
        redisTemplate.expire(key, Duration.ofSeconds(leaseSeconds));
    }

    /** 原子地读取并删除租约，保证同一 traceId 最多只有一个上报者进入扣次流程。 */
    public LeaseInfo consume(String traceId, String expectedPhone) {
        List<?> values = redisTemplate.execute(CONSUME_SCRIPT, List.of(key(traceId)), expectedPhone);
        if (values == null || values.isEmpty()) {
            return null;
        }
        Map<String, String> fields = new HashMap<>();
        for (int i = 0; i + 1 < values.size(); i += 2) {
            fields.put(String.valueOf(values.get(i)), String.valueOf(values.get(i + 1)));
        }
        return new LeaseInfo(
                Long.valueOf(fields.get("tokenId")),
                fields.get("phone"),
                fields.get("accountAlias"),
                fields.get("actionType"),
                fields.get("assignmentId") == null || fields.get("assignmentId").isBlank() ? null : Long.valueOf(fields.get("assignmentId")),
                fields.get("slotIndex") == null ? 1 : Integer.valueOf(fields.get("slotIndex"))
        );
    }

    private String key(String traceId) {
        return "pdk:lease:" + traceId;
    }

    public record LeaseInfo(Long tokenId, String phone, String accountAlias, String actionType,
                            Long assignmentId, Integer slotIndex) {
        public LeaseInfo(Long tokenId, String phone, String accountAlias, String actionType) {
            this(tokenId, phone, accountAlias, actionType, null, 1);
        }
    }
}
