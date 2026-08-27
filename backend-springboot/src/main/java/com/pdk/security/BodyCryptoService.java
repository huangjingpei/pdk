package com.pdk.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdk.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.security.PrivateKey;
import java.time.Duration;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协议安全加密的报文加解密（信封加密中的对称部分 + 防重放）。
 *
 * <p>信封格式（客户端 -> 服务端 / 服务端 -> 客户端）：
 * <pre>
 * {
 *   "kid": "v1",                         // 密钥版本，与公钥一一对应
 *   "enc": "base64( RSA-OAEP(随机 32 字节 AES 密钥) )",
 *   "iv":  "base64( 12 字节 GCM nonce )",
 *   "data":"base64( AES-256-GCM 密文 + 16 字节认证标签 )",
 *   "ts":  1700000000000,                // 毫秒时间戳，用于防重放
 *   "rnd": "随机串，用于防重放"
 * }
 * </pre>
 *
 * <p>请求：客户端用【服务端公钥】RSA-OAEP 包装一次性 AES 密钥，服务端私钥解开后，用该 AES 密钥
 * 解密 body；响应：服务端直接用同一个 AES 会话密钥加密返回（不再做 RSA），客户端用同一密钥解密。
 * 因此响应侧零非对称开销，整体开销极小。明文本身是一个 JSON 字符串（CommonResult 序列化结果）。
 */
@Service
public class BodyCryptoService {

    private final SecurityKeyService keyService;
    private final ObjectMapper objectMapper;

    /** 防重放时间窗：±5 分钟。 */
    private static final long REPLAY_WINDOW_MS = 5 * 60 * 1000L;
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final int AES_KEY_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final ReplayCache replayCache;

    @Autowired
    public BodyCryptoService(SecurityKeyService keyService, ObjectMapper objectMapper,
                             @Autowired(required = false) StringRedisTemplate redisTemplate,
                             @Value("${pdk.crypto.replay.redis.enabled:false}") boolean redisReplayEnabled) {
        this.keyService = keyService;
        this.objectMapper = objectMapper;
        if (redisReplayEnabled && redisTemplate != null) {
            this.replayCache = new RedisReplayCache(redisTemplate);
        } else {
            this.replayCache = new MemoryReplayCache();
        }
    }

    /** 防重放随机串缓存抽象：首次占用返回 true，重复返回 false。 */
    private interface ReplayCache {
        boolean tryAcquire(String rnd);
    }

    /** 单机内存实现（默认）。rnd -> 过期时间戳，惰性清理。 */
    private static final class MemoryReplayCache implements ReplayCache {
        private final ConcurrentHashMap<String, Long> used = new ConcurrentHashMap<>();
        @Override
        public boolean tryAcquire(String rnd) {
            long now = System.currentTimeMillis();
            used.entrySet().removeIf(e -> e.getValue() < now);
            return used.putIfAbsent(rnd, now + REPLAY_WINDOW_MS) == null;
        }
    }

    /** Redis 实现（多实例部署）。SET rnd NX EX 300，原子去重 + 自动过期。 */
    private static final class RedisReplayCache implements ReplayCache {
        private final StringRedisTemplate redis;
        RedisReplayCache(StringRedisTemplate redis) { this.redis = redis; }
        @Override
        public boolean tryAcquire(String rnd) {
            Boolean ok = redis.opsForValue().setIfAbsent(
                    "pdk:replay:" + rnd, "1", Duration.ofMillis(REPLAY_WINDOW_MS));
            return Boolean.TRUE.equals(ok);
        }
    }

    /** 判断一段字符串是否是加密信封（用于区分明文请求）。 */
    public boolean isEnvelope(String body) {
        if (body == null || body.trim().isEmpty()) {
            return false;
        }
        try {
            Map<?, ?> node = objectMapper.readValue(body, Map.class);
            return node.containsKey("enc") && node.containsKey("data")
                    && node.containsKey("iv") && node.containsKey("kid");
        } catch (Exception e) {
            return false;
        }
    }

    /** 解密请求信封，返回明文 JSON 与本次会话 AES 密钥（供响应复用）。内含时间戳与随机串防重放校验。 */
    public DecryptResult decryptEnvelope(String envelopeJson) {
        try {
            Map<String, Object> env = objectMapper.readValue(envelopeJson, Map.class);
            String kid = (String) env.get("kid");
            PrivateKey priv = keyService.getPrivateKey(kid);
            if (priv == null) {
                throw new BusinessException(42901, "协议密钥版本不支持，请重新拉取公钥配置");
            }
            byte[] encKey = Base64.getDecoder().decode((String) env.get("enc"));
            byte[] iv = Base64.getDecoder().decode((String) env.get("iv"));
            byte[] data = Base64.getDecoder().decode((String) env.get("data"));
            Object tsObj = env.get("ts");
            String rnd = (String) env.get("rnd");

            // 1) RSA-OAEP 解包出一次性 AES 密钥
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            rsa.init(Cipher.DECRYPT_MODE, priv);
            byte[] aesKeyBytes = rsa.doFinal(encKey);

            // 2) AES-256-GCM 解密 body（data 尾部已含 16 字节认证标签）
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKeyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = aes.doFinal(data);

            // 3) 防重放：时间戳窗口 + 随机串去重
            long ts = tsObj instanceof Number ? ((Number) tsObj).longValue() : 0L;
            long now = System.currentTimeMillis();
            if (Math.abs(now - ts) > REPLAY_WINDOW_MS) {
                throw new BusinessException(42902, "请求时间戳过期，可能存在重放攻击");
            }
            if (rnd == null || !replayCache.tryAcquire(rnd)) {
                throw new BusinessException(42903, "请求随机串重复，疑似重放攻击");
            }

            return new DecryptResult(new String(plain, StandardCharsets.UTF_8),
                    new SecretKeySpec(aesKeyBytes, "AES"));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(42904, "报文解密失败，请确认加密协议正确");
        }
    }

    /** 用请求会话密钥加密响应明文（无需再做 RSA，开销极小）。 */
    public String encryptResponse(String plainJson, SecretKeySpec aesKey) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] data = aes.doFinal(plainJson.getBytes(StandardCharsets.UTF_8));

            Map<String, Object> env = new java.util.LinkedHashMap<>();
            env.put("kid", keyService.getActiveKid());
            env.put("enc", Base64.getEncoder().encodeToString(aesKey.getEncoded()));
            env.put("iv", Base64.getEncoder().encodeToString(iv));
            env.put("data", Base64.getEncoder().encodeToString(data));
            env.put("ts", System.currentTimeMillis());
            env.put("rnd", Long.toString(random.nextLong()));
            return objectMapper.writeValueAsString(env);
        } catch (Exception e) {
            throw new BusinessException(42905, "报文加密失败");
        }
    }

    /** 解密结果：明文 JSON 与本次会话 AES 密钥。 */
    public static class DecryptResult {
        public final String plainText;
        public final SecretKeySpec aesKey;

        public DecryptResult(String plainText, SecretKeySpec aesKey) {
            this.plainText = plainText;
            this.aesKey = aesKey;
        }
    }
}
