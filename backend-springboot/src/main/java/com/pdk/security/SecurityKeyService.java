package com.pdk.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;

/**
 * 协议安全加密的密钥管理（信封加密中的非对称部分）。
 *
 * <p>服务端持有 RSA-2048 密钥对：公钥可下发客户端，私钥【绝不】离开服务端、不进库、不出现在日志。
 *
 * <p>支持多密钥并存与按 kid 轮换：
 * <ul>
 *   <li>{@code pdk.crypto.keys}：JSON 数组 [{ "kid": "...", "privateKeyPem": "..." }]，用于平滑轮换；
 *       轮换期旧密钥保留，仍能解密在途请求（按信封里的 kid 选对应私钥）。</li>
 *   <li>{@code pdk.crypto.private-key-pem} + {@code pdk.crypto.kid}：单密钥（向后兼容）。</li>
 *   <li>均未配置：启动时一次性生成 kid=v1 的密钥对（仅开发 / 单机）。</li>
 * </ul>
 * 多实例部署必须显式配置同一组密钥，否则各实例公钥不一致，客户端加密必失败。
 */
@Service
public class SecurityKeyService {

    private final Map<String, KeyPair> keyPairs;   // kid -> 密钥对
    private final String activeKid;

    public SecurityKeyService(
            @Value("${pdk.crypto.kid:v1}") String configuredKid,
            @Value("${pdk.crypto.private-key-pem:}") String singlePem,
            @Value("${pdk.crypto.keys:}") String keysJson) {
        this.keyPairs = new LinkedHashMap<>();
        ObjectMapper om = new ObjectMapper();
        if (keysJson != null && !keysJson.isBlank()) {
            try {
                List<?> list = om.readValue(keysJson, List.class);
                for (Object o : list) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    String k = (String) m.get("kid");
                    String pem = (String) m.get("privateKeyPem");
                    if (k != null && !k.isBlank() && pem != null && !pem.isBlank()) {
                        keyPairs.put(k, toKeyPair(loadPrivateKey(pem)));
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("解析 pdk.crypto.keys 失败", e);
            }
            if (keyPairs.isEmpty()) {
                throw new IllegalStateException("pdk.crypto.keys 未解析出任何有效密钥");
            }
            this.activeKid = keyPairs.containsKey(configuredKid) ? configuredKid
                    : keyPairs.keySet().iterator().next();
        } else if (singlePem != null && !singlePem.isBlank()) {
            keyPairs.put(configuredKid, toKeyPair(loadPrivateKey(singlePem)));
            this.activeKid = configuredKid;
        } else {
            KeyPair kp = generateKeyPair();
            keyPairs.put("v1", kp);
            this.activeKid = "v1";
        }
    }

    // ---------------------------------------------------------------- 多密钥查询
    /** 当前生效的 kid（客户端据此选择公钥 / 校验钉扎）。 */
    public String getActiveKid() {
        return activeKid;
    }

    /** 所有支持的 kid（含已退役但仍在宽限期的旧密钥）。 */
    public List<String> getKids() {
        return new ArrayList<>(keyPairs.keySet());
    }

    public PrivateKey getPrivateKey(String kid) {
        KeyPair kp = keyPairs.get(kid);
        return kp == null ? null : kp.getPrivate();
    }

    public PublicKey getPublicKey(String kid) {
        KeyPair kp = keyPairs.get(kid);
        return kp == null ? null : kp.getPublic();
    }

    public String getPublicKeyPem(String kid) {
        PublicKey pk = getPublicKey(kid);
        if (pk == null) return "";
        String b64 = Base64.getEncoder().encodeToString(pk.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----";
    }

    public String getPublicKeyFingerprint(String kid) {
        PublicKey pk = getPublicKey(kid);
        if (pk == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(pk.getEncoded()); // X.509 SubjectPublicKeyInfo DER
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", h[i]));
            }
            return sb.toString(); // 32 字符 hex，与客户端 compute_public_key_fingerprint 一致
        } catch (Exception e) {
            throw new IllegalStateException("计算公钥指纹失败", e);
        }
    }

    // ---------------------------------------------------------------- 向后兼容（单密钥）
    public String getKid() {
        return activeKid;
    }

    public PrivateKey getPrivateKey() {
        return getPrivateKey(activeKid);
    }

    public PublicKey getPublicKey() {
        return getPublicKey(activeKid);
    }

    public String getPublicKeyPem() {
        return getPublicKeyPem(activeKid);
    }

    public String getPublicKeyFingerprint() {
        return getPublicKeyFingerprint(activeKid);
    }

    // ---------------------------------------------------------------- 内部工具
    private KeyPair toKeyPair(PrivateKey priv) {
        return new KeyPair(derivePublicKey(priv), priv);
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA 算法不可用，无法初始化协议加密密钥", e);
        }
    }

    private PrivateKey loadPrivateKey(String pem) {
        try {
            String b64 = pem.replaceAll("-----BEGIN (.*)-----", "")
                    .replaceAll("-----END (.*)-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(b64);
            java.security.spec.PKCS8EncodedKeySpec spec = new java.security.spec.PKCS8EncodedKeySpec(der);
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            return kf.generatePrivate(spec);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析私钥 PEM", e);
        }
    }

    private PublicKey derivePublicKey(PrivateKey privateKey) {
        // 从私钥反推公钥（PKCS#8 RSA 私钥包含公钥信息足够恢复）
        try {
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            java.security.interfaces.RSAPrivateCrtKey crt = (java.security.interfaces.RSAPrivateCrtKey) privateKey;
            RSAPublicKeySpec spec =
                    new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
            return kf.generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalStateException("无法从私钥推导公钥", e);
        }
    }
}
