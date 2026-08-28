package com.pdk.business.zhibo.live.service;

import com.pdk.business.zhibo.live.config.MediaMtxProperties;
import com.pdk.business.zhibo.live.dto.MediaMtxAuthRequest;
import com.pdk.business.zhibo.live.entity.LiveStreamSession;
import com.pdk.business.zhibo.live.mapper.LiveStreamSessionMapper;
import com.pdk.domain.entity.User;
import com.pdk.mapper.UserMapper;
import com.pdk.platform.business.BusinessService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaMtxAuthServiceTest {
    private static final String SERVICE_TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String PUBLISH_TICKET = "valid-opaque-publish-ticket";

    @Mock LiveStreamSessionMapper sessionMapper;
    @Mock UserMapper userMapper;
    @Mock BusinessService businessService;
    MediaMtxProperties properties;
    MediaMtxAuthService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                LiveStreamSession.class);
    }

    @BeforeEach
    void setUp() {
        properties = new MediaMtxProperties();
        properties.setEnabled(true);
        properties.setInternalServiceToken(SERVICE_TOKEN);
        service = new MediaMtxAuthService(sessionMapper, userMapper, businessService, properties);
    }

    @Test
    void ffmpegWithoutLoginTicketIsRejectedWithHttp401() {
        MediaMtxAuthResult result = service.authorize(SERVICE_TOKEN,
                request(null, "zhibo-live/ls_1234567890abcdef", "CONN-1"));
        assertEquals(HttpStatus.UNAUTHORIZED, result.status());
        verifyNoInteractions(sessionMapper, userMapper, businessService);
    }

    @Test
    void forgedTicketIsRejectedWithHttp401() {
        when(sessionMapper.selectOne(any())).thenReturn(null);
        MediaMtxAuthResult result = service.authorize(SERVICE_TOKEN,
                request("forged", "zhibo-live/ls_1234567890abcdef", "CONN-1"));
        assertEquals(HttpStatus.UNAUTHORIZED, result.status());
    }

    @Test
    void validTicketFromLoggedInUserAllowsPublishWithHttp204() {
        LiveStreamSession session = session("ISSUED", null);
        User user = LiveStreamSessionServiceTest.entitledUser();
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(businessService.requireAvailableByAppId(3)).thenReturn(LiveStreamSessionServiceTest.context());
        when(userMapper.selectById(31L)).thenReturn(user);
        when(sessionMapper.update(any(), any())).thenReturn(1);

        MediaMtxAuthResult result = service.authorize(SERVICE_TOKEN,
                request(PUBLISH_TICKET, session.getPath(), "CONN-OK"));

        assertEquals(HttpStatus.NO_CONTENT, result.status());
        verify(sessionMapper).update(any(), any());
    }

    @Test
    void sameTicketCannotBeReplayedByAnotherConnection() {
        LiveStreamSession session = session("AUTHORIZED", "CONN-ORIGINAL");
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(businessService.requireAvailableByAppId(3)).thenReturn(LiveStreamSessionServiceTest.context());
        when(userMapper.selectById(31L)).thenReturn(LiveStreamSessionServiceTest.entitledUser());

        MediaMtxAuthResult result = service.authorize(SERVICE_TOKEN,
                request(PUBLISH_TICKET, session.getPath(), "CONN-ATTACKER"));
        assertEquals(HttpStatus.CONFLICT, result.status());
    }

    @Test
    void callerWithoutMediaMtxServiceSecretIsRejectedBeforeTicketLookup() {
        MediaMtxAuthResult result = service.authorize("wrong", request(PUBLISH_TICKET,
                "zhibo-live/ls_1234567890abcdef", "CONN-1"));
        assertEquals(HttpStatus.FORBIDDEN, result.status());
        verifyNoInteractions(sessionMapper);
    }

    private static MediaMtxAuthRequest request(String token, String path, String id) {
        return new MediaMtxAuthRequest("", "", token, "127.0.0.1", "publish", path,
                "rtmp", id, "", "ffmpeg");
    }

    private static LiveStreamSession session(String status, String connectionId) {
        LiveStreamSession session = new LiveStreamSession();
        session.setId(91L);
        session.setBizId(3L);
        session.setUserId(31L);
        session.setPath("zhibo-live/ls_1234567890abcdef");
        session.setTicketHash(LiveStreamSecurity.sha256(PUBLISH_TICKET));
        session.setTicketExpiresAt(LocalDateTime.now().plusMinutes(1));
        session.setDeviceIdHash(LiveStreamSecurity.sha256("DEVICE-LIVE-1"));
        session.setStatus(status);
        session.setMediamtxConnectionId(connectionId);
        return session;
    }
}
