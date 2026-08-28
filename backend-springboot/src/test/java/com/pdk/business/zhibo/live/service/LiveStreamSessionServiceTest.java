package com.pdk.business.zhibo.live.service;

import com.pdk.business.zhibo.live.config.MediaMtxProperties;
import com.pdk.business.zhibo.live.dto.CreatePublishTicketDTO;
import com.pdk.business.zhibo.live.entity.LiveStreamSession;
import com.pdk.business.zhibo.live.mapper.LiveStreamSessionMapper;
import com.pdk.business.zhibo.live.vo.PublishTicketVO;
import com.pdk.domain.entity.User;
import com.pdk.platform.business.BusinessContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LiveStreamSessionServiceTest {
    @Mock LiveStreamSessionMapper mapper;
    @Mock MediaMtxControlClient controlClient;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                LiveStreamSession.class);
    }

    @Test
    void loggedInLiveUserGetsShortOpaqueTicketWithoutPersistingPlaintext() {
        MediaMtxProperties props = properties();
        when(mapper.insert(any(LiveStreamSession.class))).thenReturn(1);
        LiveStreamSessionService service = new LiveStreamSessionService(mapper, props, controlClient);

        PublishTicketVO value = service.issue(context(), entitledUser(),
                new CreatePublishTicketDTO("REQ-1", "测试", "RTMP"), "127.0.0.1");

        assertTrue(value.publishUrl().startsWith("rtmp://localhost:1935/zhibo-live/ls_"));
        assertTrue(value.publishUrl().contains("?token="));
        assertEquals(90, value.ticketTtlSeconds());
        ArgumentCaptor<LiveStreamSession> captor = ArgumentCaptor.forClass(LiveStreamSession.class);
        verify(mapper).insert(captor.capture());
        LiveStreamSession saved = captor.getValue();
        String plaintext = value.publishUrl().substring(value.publishUrl().indexOf("?token=") + 7);
        assertNotEquals(plaintext, saved.getTicketHash());
        assertEquals(LiveStreamSecurity.sha256(plaintext), saved.getTicketHash());
        assertEquals("ISSUED", saved.getStatus());
        assertEquals("zhibo-live/" + value.streamSessionNo(), saved.getPath());
    }

    @Test
    void appIdOtherThanThreeIsRejectedBeforeTicketCreation() {
        LiveStreamSessionService service = new LiveStreamSessionService(mapper, properties(), controlClient);
        BusinessContext pdd = new BusinessContext(1, 1, "PDD", "PDD", "", "SELF_SERVICE",
                false, 0, 0, 0, false);
        assertThrows(com.pdk.common.exception.BusinessException.class, () -> service.issue(
                pdd, entitledUser(), new CreatePublishTicketDTO("REQ-2", "", "RTMP"), "127.0.0.1"));
        verify(mapper, never()).insert(any(LiveStreamSession.class));
    }

    private static MediaMtxProperties properties() {
        MediaMtxProperties props = new MediaMtxProperties();
        props.setEnabled(true);
        props.setPublicRtmpBaseUrl("rtmp://localhost:1935");
        props.setTicketTtlSeconds(90);
        props.setInternalServiceToken("0123456789abcdef0123456789abcdef");
        return props;
    }

    static BusinessContext context() {
        return new BusinessContext(3, 3, "ZHIBO_LIVE", "直播控制", "", "ADMIN_ONLY",
                false, 0, 0, 0, true);
    }

    static User entitledUser() {
        User user = new User();
        user.setId(31L);
        user.setBizId(3L);
        user.setStatus("ACTIVE");
        user.setDeviceId("DEVICE-LIVE-1");
        user.setExpireTime(LocalDateTime.now().plusDays(1));
        user.setRemainingCalls(10);
        return user;
    }
}
