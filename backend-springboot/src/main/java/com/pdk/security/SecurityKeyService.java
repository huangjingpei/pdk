package com.pdk.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

/**
 * 协议安全加密的密钥管理（信封加密中的非对称部分）。
 *
 * <p>服务端持有 RSA-2048 密钥对：公钥可下发客户端，私钥【绝不】离开服务端、不进库、不出现在日志。
 * 为支持多实例共享同一密钥，推荐通过 {@code pdk.crypto.private-key-pem} 注入 PKCS#8 私钥；
 * 未配置时退化为启动时一次性生成（仅在开发 / 单机场景使用）。</p>
 */
@Service
public class SecurityKeyService {

    private final String kid;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public SecurityKeyService(@Value("${pdk.crypto.private-key-pem:}") String privateKeyPem) {
        if (privateKeyPem != null && !privateKeyPem.isBlank()) {
            this.kid = "v1";
            this.privateKey = loadPrivateKey(privateKeyPem);
            this.publicKey = derivePublicKey(this.privateKey);
        } else {
            try {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                gen.initialize(2048);
                KeyPair kp = gen.generateKeyPair();
                this.kid = "v1";
                this.privateKey = kp.getPrivate();
                this.publicKey = kp.getPublic();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("RSA 算法不可用，无法初始化协议加密密钥", e);
            }
        }
    }

    /** 当前密钥版本标识；客户端据此选择对应公钥并校验钉扎。 */
    public String getKid() {
        return kid;
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    /** 以 PEM (X.509 SubjectPublicKeyInfo) 形式导出公钥，供客户端拉取并钉扎。 */
    public String getPublicKeyPem() {
        String b64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----";
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
            throw new IllegalStateException("无法解析 pdk.crypto.private-key-pem 中的私钥", e);
        }
    }

    private PublicKey derivePublicKey(PrivateKey privateKey) {
        // 从私钥反推公钥（PKCS#8 RSA 私钥包含公钥信息足够恢复）
        try {
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            java.security.interfaces.RSAPrivateCrtKey crt = (java.security.interfaces.RSAPrivateCrtKey) privateKey;
            java.security.spec.RSAPublicKeySpec spec =
                    new java.security.spec.RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
            return kf.generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalStateException("无法从私钥推导公钥", e);
        }
    }
}
