package com.pdk.utils;

import com.pdk.common.utils.AesByteFlipUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AesByteFlipUtilsTest {

    @Test
    @DisplayName("UT-07: AES-128-GCM + 0x50 0x44 字节翻转加解密双向一致性测试")
    void testEncryptAndDecrypt() {
        String originalToken = "pdd_session_tok_998124_secret_alpha";
        String payload = "{\"token\":\"" + originalToken + "\",\"leaseId\":\"TRACE-1002\",\"expire\":300}";

        String encrypted = AesByteFlipUtils.encryptAndFlip(payload);
        assertNotNull(encrypted);
        assertFalse(encrypted.isEmpty());

        String decrypted = AesByteFlipUtils.decryptAndUnflip(encrypted);
        assertEquals(payload, decrypted);
        assertTrue(decrypted.contains(originalToken));
    }
}
