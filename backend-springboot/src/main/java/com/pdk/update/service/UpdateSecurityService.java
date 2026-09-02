package com.pdk.update.service;

import com.pdk.common.exception.BusinessException;
import com.pdk.update.config.ClientUpdateProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class UpdateSecurityService {
    private final ClientUpdateProperties properties;

    public String anonymousDevice(long bizId, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return null;
        return hex(hmac(properties.getRolloutHmacSecret(), bizId + ":" + deviceId.trim()));
    }

    public int rolloutBucket(long appId, long releaseId, String anonymousDevice) {
        byte[] digest = hmac(properties.getRolloutHmacSecret(), appId + ":" + releaseId + ":" + anonymousDevice);
        long unsigned = ((digest[0] & 255L) << 24) | ((digest[1] & 255L) << 16) | ((digest[2] & 255L) << 8) | (digest[3] & 255L);
        return (int) (unsigned % 10_000);
    }

    public String issueDownloadToken(long appId, long artifactId, long expiresAt) {
        String payload = appId + ":" + artifactId + ":" + expiresAt;
        return base64Url(payload.getBytes(StandardCharsets.UTF_8)) + "." + base64Url(hmac(properties.getDownloadTokenSecret(), payload));
    }

    public void verifyDownloadToken(String token, long appId, long artifactId) {
        String payload = verifyToken(token, properties.getDownloadTokenSecret());
        String[] parts = payload.split(":");
        if (parts.length != 3 || Long.parseLong(parts[0]) != appId || Long.parseLong(parts[1]) != artifactId
                || Long.parseLong(parts[2]) < Instant.now().getEpochSecond()) {
            throw new BusinessException(40490, "下载地址无效或已过期");
        }
    }

    public String issueEventToken(long appId, String checkRequestId, Long artifactId, long expiresAt) {
        String payload = appId + ":" + checkRequestId + ":" + (artifactId == null ? 0 : artifactId) + ":" + expiresAt;
        return base64Url(payload.getBytes(StandardCharsets.UTF_8)) + "." + base64Url(hmac(properties.getEventTokenSecret(), payload));
    }

    public void verifyEventToken(String token, long appId, String checkRequestId, Long artifactId) {
        String payload = verifyToken(token, properties.getEventTokenSecret());
        String[] parts = payload.split(":");
        if (parts.length != 4 || Long.parseLong(parts[0]) != appId || !parts[1].equals(checkRequestId)
                || Long.parseLong(parts[2]) != (artifactId == null ? 0 : artifactId)
                || Long.parseLong(parts[3]) < Instant.now().getEpochSecond()) {
            throw new BusinessException(42290, "升级事件令牌无效或已过期");
        }
    }

    public String signArtifact(String canonical) { return sign(canonical, properties.getArtifactPrivateKey()); }
    public String signPolicy(String canonical) { return sign(canonical, properties.getPolicyPrivateKey()); }

    private String sign(String canonical, String privateKeyBase64) {
        if (privateKeyBase64 == null || privateKeyBase64.isBlank()) {
            throw new BusinessException(50390, "升级签名私钥未配置，禁止发布或签发策略");
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(privateKeyBase64.replaceAll("\\s", ""));
            var privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new BusinessException(50390, "升级签名服务配置无效");
        }
    }

    private String verifyToken(String token, String secret) {
        try {
            String[] pieces = token == null ? new String[0] : token.split("\\.");
            if (pieces.length != 2) throw new IllegalArgumentException();
            String payload = new String(Base64.getUrlDecoder().decode(pieces[0]), StandardCharsets.UTF_8);
            byte[] expected = hmac(secret, payload);
            byte[] actual = Base64.getUrlDecoder().decode(pieces[1]);
            if (!java.security.MessageDigest.isEqual(expected, actual)) throw new IllegalArgumentException();
            return payload;
        } catch (Exception e) {
            throw new BusinessException(42290, "短效令牌无效");
        }
    }

    private byte[] hmac(String secret, String value) {
        if (secret == null || secret.length() < 32) throw new BusinessException(50390, "升级 HMAC 密钥长度不足");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String base64Url(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private String hex(byte[] value) { return HexFormat.of().formatHex(value); }
}
