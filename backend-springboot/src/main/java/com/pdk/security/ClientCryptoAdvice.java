package com.pdk.security;

import com.pdk.common.exception.BusinessException;
import com.pdk.config.ConfigKeys;
import com.pdk.service.SystemConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端协议加密拦截：对 /api/v1/client、/api/v1/dispatch 与 /api/v1/card 下的接口，
 * 在请求侧解密信封、响应侧按"请求是否加密"决定是否加密返回。对业务 Controller 零侵入。
 */
@RestControllerAdvice
public class ClientCryptoAdvice implements RequestBodyAdvice, ResponseBodyAdvice<Object> {

    private final BodyCryptoService bodyCrypto;
    private final SystemConfigService configService;
    private final ObjectMapper objectMapper;

    /** 请求线程内共享：本次请求是否以加密信封形式到达（用于响应侧决定是否加密）。 */
    private static final ThreadLocal<Boolean> REQUEST_ENCRYPTED = new ThreadLocal<>();
    /** 请求线程内共享：本次会话 AES 密钥（响应复用，避免再做 RSA）。 */
    private static final ThreadLocal<javax.crypto.spec.SecretKeySpec> SESSION_KEY = new ThreadLocal<>();

    /** 跨请求会话密钥缓存：按 appId + 稳定身份（设备ID）存最近一次信封会话密钥，带 TTL。
     *  用于让无 body 的 GET 响应也能被加密（方案 A：会话级加密判定）。 */
    private static final ConcurrentHashMap<String, SessionKeyEntry> SESSION_KEYS = new ConcurrentHashMap<>();
    private static final long SESSION_TTL_MS = 30L * 60 * 1000;

    /** 会话密钥缓存条目（带过期时间）。 */
    private static final class SessionKeyEntry {
        final javax.crypto.spec.SecretKeySpec key;
        final long expireAt;
        SessionKeyEntry(javax.crypto.spec.SecretKeySpec k) {
            this.key = k;
            this.expireAt = System.currentTimeMillis() + SESSION_TTL_MS;
        }
        boolean expired() { return System.currentTimeMillis() > expireAt; }
    }

    @Autowired
    public ClientCryptoAdvice(BodyCryptoService bodyCrypto, SystemConfigService configService,
                              ObjectMapper objectMapper) {
        this.bodyCrypto = bodyCrypto;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter methodParameter,
                           java.lang.reflect.Type targetType,
                           Class<? extends HttpMessageConverter<?>> converterType) {
        // 仅针对带 @RequestBody 的接口方法（即需要读取请求体的接口）
        return methodParameter.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class);
    }

    @Override
    public boolean supports(MethodParameter returnType,
                           Class<? extends HttpMessageConverter<?>> converterType) {
        // 响应侧：对所有接口生效，但仅在请求已加密时才实际加密（见 beforeBodyWrite）
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage,
                                          MethodParameter parameter,
                                          java.lang.reflect.Type targetType,
                                          Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        HttpServletRequest req = ((org.springframework.web.context.request.ServletRequestAttributes)
                RequestContextHolder.getRequestAttributes()).getRequest();
        String uri = req.getRequestURI();
        if (!isProtectedPath(uri)) {
            REQUEST_ENCRYPTED.set(false);
            return inputMessage;
        }
        String mode = configService.getValue(ConfigKeys.SECURITY_ENCRYPTION_MODE,
                ConfigKeys.DEFAULT_SECURITY_ENCRYPTION_MODE);
        byte[] raw = StreamUtils.copyToByteArray(inputMessage.getBody());
        String rawStr = new String(raw, StandardCharsets.UTF_8);

        if (bodyCrypto.isEnvelope(rawStr)) {
            if ("off".equals(mode)) {
                // 关闭模式下收到信封：按明文透传（交由后续校验，通常绑定失败返回明文错误）
                REQUEST_ENCRYPTED.set(false);
                // inputMessage.getBody() 已被 copyToByteArray 消费，必须用缓存内容重建输入流。
                return wrap(rawStr, inputMessage.getHeaders());
            }
            BodyCryptoService.DecryptResult result = bodyCrypto.decryptEnvelope(rawStr);
            REQUEST_ENCRYPTED.set(true);
            SESSION_KEY.set(result.aesKey);
            // 方案 A：把本次会话密钥按身份存入跨请求缓存，供后续 GET 响应加密使用
            SESSION_KEYS.put(resolveIdentity(req), new SessionKeyEntry(result.aesKey));
            return wrap(result.plainText, inputMessage.getHeaders());
        } else {
            if ("force".equals(mode)) {
                REQUEST_ENCRYPTED.set(false);
                throw new BusinessException(42900, "当前已强制启用协议加密，请使用支持加密的客户端");
            }
            REQUEST_ENCRYPTED.set(false);
            // 明文兼容模式同样已经读取过原始流；返回原对象会让 Jackson 收到空 body。
            return wrap(rawStr, inputMessage.getHeaders());
        }
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        String uri = request.getURI().getPath();
        Boolean encrypted = REQUEST_ENCRYPTED.get();
        REQUEST_ENCRYPTED.remove();
        javax.crypto.spec.SecretKeySpec sessionKey = SESSION_KEY.get();
        SESSION_KEY.remove();
        if (!isProtectedPath(uri)) {
            return body;
        }
        // 公开引导端点（如 /config/public）必须始终返回明文：客户端需要先拿到公钥与加密模式
        // 才能开始加密，绝不能用会话密钥加密它的响应（否则新客户端无法引导，报 42904）。
        if (isPublicBootstrapPath(uri)) {
            return body;
        }
        javax.crypto.spec.SecretKeySpec responseKey = null;
        if (Boolean.TRUE.equals(encrypted) && sessionKey != null) {
            responseKey = sessionKey; // 本次请求即信封：用本次会话密钥加密响应
            // 健壮性：登录类响应（POST 信封）同时按登录态身份缓存密钥，
            // 覆盖后续 GET 未携带 device-id 头时的加密判定
            cacheKeyByLoginId(sessionKey);
        } else {
            // GET / 无 body 请求：仅当客户端显式声明持有会话密钥（X-PDK-Crypto-Armed=1）
            // 且能按稳定身份（设备ID / 登录态）找到缓存密钥时才加密响应。
            // 这避免了“服务端缓存了别的会话密钥、但本客户端并没有该密钥”时把响应加密、
            // 导致客户端无法解密（42904）的问题；同时彻底封堵了基于共享 IP 串密钥的风险。
            String armed = request.getHeaders().getFirst("X-PDK-Crypto-Armed");
            if ("1".equals(armed)) {
                String id = resolveIdentityHttp(request);
                if (isStableIdentity(id)) {
                    SessionKeyEntry entry = SESSION_KEYS.get(id);
                    if (entry != null) {
                        if (entry.expired()) SESSION_KEYS.remove(id);
                        else responseKey = entry.key;
                    }
                }
                // 兜底：用登录态身份再查一次（同样仅限稳定身份）
                if (responseKey == null) {
                    HttpServletRequest cur = currentRequest();
                    if (cur != null) {
                        String id2 = resolveIdentity(cur);
                        if (isStableIdentity(id2)) {
                            SessionKeyEntry e2 = SESSION_KEYS.get(id2);
                            if (e2 != null && !e2.expired()) responseKey = e2.key;
                        }
                    }
                }
            }
        }
        if (responseKey == null) {
            return body;
        }
        try {
            String json = body instanceof String ? (String) body
                    : objectMapper.writeValueAsString(body);
            Map<String, Object> envelope = bodyCrypto.encryptResponse(json, responseKey);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return envelope;
        } catch (Exception e) {
            // 加密失败不应阻断业务：退回明文响应，保留原始 body。
            return body;
        }
    }

    /** 公开引导端点：必须明文返回，供新客户端引导（获取公钥 / 加密模式）。 */
    private boolean isPublicBootstrapPath(String uri) {
        return uri != null && uri.startsWith("/api/v1/client/config/public");
    }

    /** 仅设备ID（dev）或登录态（uid）身份可用于加密判定；IP 身份共享不可靠，禁止用于加密。 */
    private static boolean isStableIdentity(String id) {
        return id != null && (id.contains(":dev:") || id.contains(":uid:"));
    }

    /** 从 ServerHttpRequest 直接解析会话身份（优先 device-id，其次登录态，最后 IP）。
     *  不依赖 RequestContextHolder，避免在 ResponseBodyAdvice 阶段取不到原生 request 时静默跳过加密。 */
    private static String resolveIdentityHttp(ServerHttpRequest req) {
        String appId = req.getHeaders().getFirst("X-PDK-App-ID");
        String appScope = (appId == null || appId.isBlank()) ? "1" : appId.trim();
        String dev = req.getHeaders().getFirst("X-PDK-Device-ID");
        if (dev != null && !dev.isBlank()) return "app:" + appScope + ":dev:" + dev.trim();
        try {
            Object id = cn.dev33.satoken.stp.StpUtil.getLoginIdDefaultNull();
            if (id != null) return "app:" + appScope + ":uid:" + id;
        } catch (Exception ignored) {
            // Sa-Token 未启用或请求未登录时忽略，退化为 IP
        }
        return "app:" + appScope + ":ip:" + (req.getRemoteAddress() != null
                ? req.getRemoteAddress().toString() : "unknown");
    }

    /** 登录类响应（本次请求为信封）额外按登录态身份缓存会话密钥，
     *  供后续未携带 device-id 头的 GET 请求也能判定加密。 */
    private void cacheKeyByLoginId(javax.crypto.spec.SecretKeySpec key) {
        HttpServletRequest cur = currentRequest();
        if (cur == null) return;
        String id = resolveIdentity(cur);
        if (id.contains(":uid:")) {
            SESSION_KEYS.put(id, new SessionKeyEntry(key));
        }
    }

    private HttpInputMessage wrap(String plain, HttpHeaders headers) {
        byte[] data = plain.getBytes(StandardCharsets.UTF_8);
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(data);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    private static HttpServletRequest currentRequest() {
        org.springframework.web.context.request.RequestAttributes attrs =
                RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes) {
            return ((ServletRequestAttributes) attrs).getRequest();
        }
        return null;
    }

    private static String resolveIdentity(HttpServletRequest req) {
        String appId = req.getHeader("X-PDK-App-ID");
        String appScope = (appId == null || appId.isBlank()) ? "1" : appId.trim();
        String dev = req.getHeader("X-PDK-Device-ID");
        if (dev != null && !dev.isBlank()) return "app:" + appScope + ":dev:" + dev.trim();
        try {
            Object id = cn.dev33.satoken.stp.StpUtil.getLoginIdDefaultNull();
            if (id != null) return "app:" + appScope + ":uid:" + id;
        } catch (Exception ignored) {
            // Sa-Token 未启用或请求未登录时忽略，退化为 IP
        }
        return "app:" + appScope + ":ip:" + req.getRemoteAddr();
    }

    private boolean isProtectedPath(String uri) {
        return uri != null && (uri.startsWith("/api/v1/client")
                || uri.startsWith("/api/v1/dispatch")
                || uri.startsWith("/api/v1/card"));
    }

    @Override
    public Object afterBodyRead(Object body,
                                HttpInputMessage inputMessage,
                                MethodParameter parameter,
                                java.lang.reflect.Type targetType,
                                Class<? extends HttpMessageConverter<?>> converterType) {
        return body;
    }

    @Override
    public Object handleEmptyBody(Object body,
                                 HttpInputMessage inputMessage,
                                 MethodParameter parameter,
                                 java.lang.reflect.Type targetType,
                                 Class<? extends HttpMessageConverter<?>> converterType) {
        return body;
    }
}
