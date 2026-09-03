package com.pdk.update.config;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 客户端升级签名密钥的启动期自检。
 *
 * <p>要防的是一类真实发生过、且极难排查的故障：签名私钥可以同时来自「配置文件」和
 * 「OS 环境变量」，而 Spring 中环境变量的优先级更高。当两边都存在、又不是同一对密钥时，
 * 会出现 key-id 取自文件、私钥取自环境变量的分裂状态——后端签名一切正常、日志毫无异常，
 * 但每一个客户端的验签都必然失败。
 *
 * <p>所以这里在启动时做一次「私钥 ↔ 声明公钥」的配对校验：用私钥签一个固定串，
 * 再用配置中声明的公钥验签。不配对就拒绝启动，把一个「只有客户端才看得见」的问题
 * 提前暴露在服务端启动阶段。
 */
@Component
@Order(10)
@RequiredArgsConstructor
public class ClientUpdateKeyInspector implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ClientUpdateKeyInspector.class);

    /** 自检专用的固定明文，只用于验证密钥对是否匹配，不参与任何业务签名。 */
    private static final byte[] SELF_TEST_PAYLOAD = "PDK-KEYPAIR-SELFTEST-V1".getBytes(StandardCharsets.UTF_8);

    /** 密钥的唯一配置位置（相对于进程工作目录），与 application.yml 的 spring.config.import 一致。 */
    private static final String KEY_FILE = "config/client-update-keys.yml";

    private final ClientUpdateProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.info("[客户端升级] 模块已关闭（pdk.client-update.enabled=false），跳过签名密钥自检");
            return;
        }
        inspect("构件", "artifact", properties.getArtifactPrivateKey(),
                properties.getArtifactKeyId(), properties.getArtifactPublicKey());
        inspect("策略", "policy", properties.getPolicyPrivateKey(),
                properties.getPolicyKeyId(), properties.getPolicyPublicKey());
    }

    private void inspect(String label, String prefix, String privateKeyBase64,
                         String keyId, String publicKeyBase64) {
        if (isBlank(privateKeyBase64)) {
            log.warn("[客户端升级] {}签名私钥未配置。服务可以正常启动，但「发布构件 / 签发升级策略」"
                            + "会直接失败（错误码 50390）。请把密钥写入 {} "
                            + "（键名 pdk.client-update.{}-private-key / -key-id / -public-key），"
                            + "密钥用 python scripts/generate_update_keys.py 生成，"
                            + "模板见 backend-springboot/config/client-update-keys.example.yml",
                    label, keyFileHint(), prefix);
            return;
        }

        PrivateKey privateKey;
        try {
            privateKey = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(decode(privateKeyBase64)));
        } catch (Exception e) {
            throw new IllegalStateException(("[客户端升级] %s签名私钥无法解析为 Ed25519 PKCS#8 私钥，已拒绝启动。"
                    + "请检查 %s 中的 pdk.client-update.%s-private-key，它应当是 base64 编码的 PKCS#8 DER。")
                    .formatted(label, keyFileHint(), prefix), e);
        }

        String fingerprint = fingerprint(privateKeyBase64);

        if (isBlank(publicKeyBase64)) {
            log.warn("[客户端升级] {}签名私钥已加载（key-id={}，私钥指纹={}），但未声明 {}-public-key，"
                            + "因此无法校验密钥对一致性。强烈建议在 {} 中补上配对公钥："
                            + "否则一旦私钥被更高优先级的配置源（命令行 / 系统属性 / 环境变量）悄悄覆盖，"
                            + "只能等到客户端验签失败时才会发现。",
                    label, keyId, fingerprint, prefix, keyFileHint());
            return;
        }

        boolean matched;
        try {
            matched = keyPairMatches(privateKey, publicKeyBase64);
        } catch (Exception e) {
            throw new IllegalStateException(("[客户端升级] %s签名公钥无法解析为 Ed25519 SPKI 公钥，已拒绝启动。"
                    + "请检查 %s 中的 pdk.client-update.%s-public-key。")
                    .formatted(label, keyFileHint(), prefix), e);
        }

        if (!matched) {
            throw new IllegalStateException(("""
                    [客户端升级] %s签名密钥对不匹配，已拒绝启动。
                      key-id     = %s
                      私钥指纹   = %s
                      声明的公钥 = %s
                    含义：用当前生效的私钥签出的内容，客户端拿上面这个公钥【无法】验签通过。
                          若放任启动，后端会正常签出所有升级包，但每一个客户端都装不上，
                          而服务端日志不会有任何异常——这比服务起不来危险得多，故此处硬性拦截。
                    常见原因（按概率排序）：
                      1) 私钥被更高优先级的配置源覆盖了。Spring 优先级为：
                         命令行参数 > 系统属性(-D) > OS 环境变量 > spring.config.import 导入的文件。
                         请检查是否残留 PDK_UPDATE_%s_PRIVATE_KEY 这类环境变量，
                         或 IDE 运行配置（.idea/workspace.xml）里注入的同名变量。
                      2) 换过密钥对，但只更新了私钥、忘了同步更新公钥。
                      3) 私钥与公钥取自两次不同的 generate_update_keys.py 运行结果。
                    修复：确保 %s 里的私钥与公钥来自同一次生成，且没有任何环境变量覆盖它们。""")
                    .formatted(label, keyId, fingerprint, publicKeyBase64,
                            prefix.toUpperCase(Locale.ROOT), keyFileHint()));
        }

        log.info("[客户端升级] {}签名密钥自检通过。key-id={} 私钥指纹={} 公钥={}（客户端需内置该公钥）",
                label, keyId, fingerprint, publicKeyBase64);
    }

    /** 用私钥签固定串、再用声明的公钥验签，以此判断两者是否为同一对密钥。 */
    private boolean keyPairMatches(PrivateKey privateKey, String publicKeyBase64) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(SELF_TEST_PAYLOAD);
        byte[] signature = signer.sign();

        PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(decode(publicKeyBase64)));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(SELF_TEST_PAYLOAD);
        return verifier.verify(signature);
    }

    /** 私钥的 SHA-256 前 6 字节，用于在日志中区分「当前用的是哪把私钥」，不泄露私钥本身。 */
    private String fingerprint(String privateKeyBase64) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(decode(privateKeyBase64));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (Exception e) {
            return "unavailable";
        }
    }

    /** 打印密钥文件的绝对路径：工作目录不对时，这一行能直接暴露问题。 */
    private String keyFileHint() {
        return new File(KEY_FILE).getAbsolutePath();
    }

    private byte[] decode(String base64) {
        return Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
