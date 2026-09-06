package com.pdk.business.zhibo.live.service;

import com.pdk.business.zhibo.live.entity.LivePlaySession;
import com.pdk.business.zhibo.live.entity.LiveStreamSession;
import com.pdk.business.zhibo.live.mapper.LivePlaySessionMapper;
import com.pdk.business.zhibo.live.mapper.LiveStreamSessionMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LivePlaySessionServiceTest {
    @Test
    void repeatedReadHookCreatesOnlyOnePlayRow() {
        LivePlaySessionMapper playMapper = mock(LivePlaySessionMapper.class);
        LiveStreamSessionMapper streamMapper = mock(LiveStreamSessionMapper.class);
        LiveStreamSession stream = new LiveStreamSession();
        stream.setId(1L);
        stream.setBizId(3L);
        stream.setStatus("LIVE");
        when(streamMapper.selectOne(any())).thenReturn(stream);
        when(playMapper.selectOne(any())).thenReturn(null, playing());
        when(playMapper.insert(any(LivePlaySession.class))).thenReturn(1);
        LivePlaySessionService service = new LivePlaySessionService(playMapper, streamMapper);

        assertTrue(service.started("mediamtx-local", "zhibo-live/ls_1234567890abcdef", "reader-1", "hls", "127.0.0.1"));
        assertTrue(service.started("mediamtx-local", "zhibo-live/ls_1234567890abcdef", "reader-1", "hls", "127.0.0.1"));
        verify(playMapper, times(1)).insert(any(LivePlaySession.class));
    }

    private static LivePlaySession playing() {
        LivePlaySession value = new LivePlaySession();
        value.setStatus("PLAYING");
        return value;
    }
}
