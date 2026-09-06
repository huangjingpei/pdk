package com.pdk.business.zhibo.live.service;

import com.pdk.business.zhibo.live.config.MediaMtxProperties;
import com.pdk.business.zhibo.live.entity.LiveStreamSession;
import com.pdk.business.zhibo.live.mapper.LiveStreamSessionMapper;
import com.pdk.mapper.DeviceLicenseMapper;
import com.pdk.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MediaMtxEventServiceTest {
    @Test
    void hookMovesTheExistingPushRowAndNeverCreatesAnotherOne() {
        LiveStreamSessionMapper streamMapper = mock(LiveStreamSessionMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        DeviceLicenseMapper licenseMapper = mock(DeviceLicenseMapper.class);
        LivePlaySessionService playService = mock(LivePlaySessionService.class);
        MediaServerNodeService nodeService = mock(MediaServerNodeService.class);
        MediaMtxProperties properties = new MediaMtxProperties();
        properties.setNodeCode("mediamtx-local");
        LiveStreamSession existing = new LiveStreamSession();
        existing.setId(10L);
        existing.setBizId(3L);
        existing.setUserId(20L);
        existing.setStatus("AUTHORIZED");
        existing.setPath("zhibo-live/ls_1234567890abcdef");
        when(streamMapper.selectOne(any())).thenReturn(existing);
        when(streamMapper.update(any(), any())).thenReturn(1);
        when(userMapper.update(any(), any())).thenReturn(1);
        MediaMtxEventService service = new MediaMtxEventService(streamMapper, userMapper, licenseMapper,
                properties, playService, nodeService);

        assertTrue(service.available("mediamtx-local", existing.getPath(), "source-1"));
        verify(streamMapper, times(1)).update(any(), any());
        verify(streamMapper, never()).insert(any(LiveStreamSession.class));
    }

    @Test
    void repeatedLiveHookIsIdempotentAndDoesNotBillAgain() {
        LiveStreamSessionMapper streamMapper = mock(LiveStreamSessionMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        DeviceLicenseMapper licenseMapper = mock(DeviceLicenseMapper.class);
        MediaMtxProperties properties = new MediaMtxProperties();
        LiveStreamSession existing = new LiveStreamSession();
        existing.setStatus("LIVE");
        existing.setPath("zhibo-live/ls_1234567890abcdef");
        when(streamMapper.selectOne(any())).thenReturn(existing);
        MediaMtxEventService service = new MediaMtxEventService(streamMapper, userMapper, licenseMapper,
                properties, mock(LivePlaySessionService.class), mock(MediaServerNodeService.class));

        assertTrue(service.available("mediamtx-local", existing.getPath(), "source-1"));
        verify(streamMapper, never()).update(any(), any());
        verifyNoInteractions(userMapper, licenseMapper);
    }
}
