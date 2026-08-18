package com.pdk.common.utils;

import org.apache.tomcat.util.codec.binary.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;

/**
 * 通信加密核心工具类:
 * 1. 动态10分钟时间窗口派生密钥 Key = SHA256(RootKey + "_" + epochMinutes/10)
 * 2. AES-128-GCM 加密 (12字节IV, 128位Tag)
 * 3. 头部前缀追加 0x50 0x44 (PDK魔数) 并全报文高低位倒序翻转
 */
public class AesByteFlipUtils {

    private static final String ROOT_SALT = "PDK_SECRET_SALT_2026_ENTERPRISE";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final byte MAGIC_BYTE_1 = (byte) 0x50; // 'P'
    private static final byte MAGIC_BYTE_2 = (byte) 0x44; // 'D'

    /**
     * 派生当前时间窗口的 AES 密钥 (16 字节)
     */
    public static byte[] deriveKey(long epochMinuteWindow) {
        try {
            String raw = ROOT_SALT + "_" + epochMinuteWindow;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(hash, 16); // 128 bit key
        } catch (Exception e) {
            throw new RuntimeException("密钥派生失败", e);
        }
    }

    /**
     * 服务端加密并翻转字节
     */
    public static String encryptAndFlip(String plaintext) {
        try {
            long currentMinuteWindow = Instant.now().getEpochSecond() / 60 / 10;
            byte[] key = deriveKey(currentMinuteWindow);

            // 1. 生成 12 字节随机 IV
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            // 2. 执行 AES-128-GCM 加密
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // 3. 组装 Payload: [Magic(2B) + IV(12B) + Ciphertext(with GCM Tag)]
            byte[] rawPayload = new byte[2 + IV_LENGTH + ciphertext.length];
            rawPayload[0] = MAGIC_BYTE_1;
            rawPayload[1] = MAGIC_BYTE_2;
            System.arraycopy(iv, 0, rawPayload, 2, IV_LENGTH);
            System.arraycopy(ciphertext, 0, rawPayload, 2 + IV_LENGTH, ciphertext.length);

            // 4. 全报文字节逆序翻转 (混淆反编译与抓包特征)
            byte[] flipped = new byte[rawPayload.length];
            for (int i = 0; i < rawPayload.length; i++) {
                flipped[i] = rawPayload[rawPayload.length - 1 - i];
            }

            return Base64.encodeBase64String(flipped);
        } catch (Exception e) {
            throw new RuntimeException("数据加密混淆失败: " + e.getMessage(), e);
        }
    }

    /**
     * 客户端解密 (兼容 ±1 个时间窗口容错)
     */
    public static String decryptAndUnflip(String base64Encrypted) {
        try {
            byte[] flipped = Base64.decodeBase64(base64Encrypted);
            // 1. 还原字节正序
            byte[] rawPayload = new byte[flipped.length];
            for (int i = 0; i < flipped.length; i++) {
                rawPayload[i] = flipped[flipped.length - 1 - i];
            }

            // 2. 校验魔数
            if (rawPayload[0] != MAGIC_BYTE_1 || rawPayload[1] != MAGIC_BYTE_2) {
                throw new IllegalArgumentException("魔数校验失败: 非有效 PDK 加密数据包");
            }

            // 3. 提取 IV 与密文
            byte[] iv = Arrays.copyOfRange(rawPayload, 2, 2 + IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(rawPayload, 2 + IV_LENGTH, rawPayload.length);

            // 4. 尝试当前窗口及前后各 1 个窗口解密 (容忍时钟偏差)
            long currentMinuteWindow = Instant.now().getEpochSecond() / 60 / 10;
            long[] testWindows = {currentMinuteWindow, currentMinuteWindow - 1, currentMinuteWindow + 1};

            for (long w : testWindows) {
                try {
                    byte[] key = deriveKey(w);
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
                    SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);
                    byte[] decrypted = cipher.doFinal(ciphertext);
                    return new String(decrypted, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    // 尝试下一个窗口
                }
            }

            throw new IllegalStateException("AES-GCM 解密失败: 密钥过期或数据被篡改");
        } catch (Exception e) {
            throw new RuntimeException("数据解密还原失败: " + e.getMessage(), e);
        }
    }
}
