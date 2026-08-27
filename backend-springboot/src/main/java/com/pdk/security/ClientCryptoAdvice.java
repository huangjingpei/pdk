package com.pdk.security;

import com.pdk.common.exception.BusinessException;
import com.pdk.config.ConfigKeys;
import com.pdk.service.SystemConfigService;
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

/**
 * 客户端协议加密拦截：对 /api/v1/client 与 /api/v1/dispatch 下的接口，
 * 在请求侧解密信封、响应侧按"请求是否加密"决定是否加密返回。对业务 Controller 零侵入。
 */
@RestControllerAdvice
public class ClientCryptoAdvice implements RequestBodyAdvice, ResponseBodyAdvice<Object> {

    private final BodyCryptoService bodyCrypto;
    private final SystemConfigService configService;

    /** 请求线程内共享：本次请求是否以加密信封形式到达（用于响应侧决定是否加密）。 */
    private static final ThreadLocal<Boolean> REQUEST_ENCRYPTED = new ThreadLocal<>();
    /** 请求线程内共享：本次会话 AES 密钥（响应复用，避免再做 RSA）。 */
    private static final ThreadLocal<javax.crypto.spec.SecretKeySpec> SESSION_KEY = new ThreadLocal<>();

    @Autowired
    public ClientCryptoAdvice(BodyCryptoService bodyCrypto, SystemConfigService configService) {
        this.bodyCrypto = bodyCrypto;
        this.configService = configService;
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
                return inputMessage;
            }
            BodyCryptoService.DecryptResult result = bodyCrypto.decryptEnvelope(rawStr);
            REQUEST_ENCRYPTED.set(true);
            SESSION_KEY.set(result.aesKey);
            return wrap(result.plainText, inputMessage.getHeaders());
        } else {
            if ("force".equals(mode)) {
                REQUEST_ENCRYPTED.set(false);
                throw new BusinessException(42900, "当前已强制启用协议加密，请使用支持加密的客户端");
            }
            REQUEST_ENCRYPTED.set(false);
            return inputMessage;
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
        if (!isProtectedPath(uri) || !Boolean.TRUE.equals(encrypted) || sessionKey == null) {
            return body;
        }
        try {
            String json = body instanceof String ? (String) body
                    : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
            String envelope = bodyCrypto.encryptResponse(json, sessionKey);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return envelope;
        } catch (Exception e) {
            // 加密失败：退回明文，避免客户端完全收不到响应
            return body;
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

    private boolean isProtectedPath(String uri) {
        return uri != null && (uri.startsWith("/api/v1/client") || uri.startsWith("/api/v1/dispatch"));
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
