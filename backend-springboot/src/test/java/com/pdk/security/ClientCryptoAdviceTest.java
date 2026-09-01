package com.pdk.security;

import com.pdk.service.SystemConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientCryptoAdviceTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void optionalModeMustRestorePlaintextBodyAfterInspectingIt() throws Exception {
        BodyCryptoService crypto = mock(BodyCryptoService.class);
        SystemConfigService config = mock(SystemConfigService.class);
        when(crypto.isEnvelope(anyString())).thenReturn(false);
        when(config.getValue(anyString(), anyString())).thenReturn("optional");
        ClientCryptoAdvice advice = new ClientCryptoAdvice(crypto, config, new ObjectMapper());

        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/api/v1/client/auth/login");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
        byte[] body = "{\"appId\":3,\"phone\":\"13900000003\"}".getBytes(StandardCharsets.UTF_8);
        HttpInputMessage input = message(body);

        HttpInputMessage restored = advice.beforeBodyRead(input, null, Object.class, null);

        assertThat(restored.getBody().readAllBytes()).isEqualTo(body);
        assertThat(input.getBody().readAllBytes()).isEmpty();
    }

    private static HttpInputMessage message(byte[] bytes) {
        return new HttpInputMessage() {
            private final InputStream body = new ByteArrayInputStream(bytes);

            @Override
            public InputStream getBody() {
                return body;
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }
        };
    }
}
