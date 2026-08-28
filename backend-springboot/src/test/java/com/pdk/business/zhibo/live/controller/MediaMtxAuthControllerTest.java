package com.pdk.business.zhibo.live.controller;

import com.pdk.business.zhibo.live.service.MediaMtxAuthResult;
import com.pdk.business.zhibo.live.service.MediaMtxAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MediaMtxAuthControllerTest {
    @Test
    void deniedBusinessDecisionMustRemainNon2xxAtHttpLayer() throws Exception {
        MediaMtxAuthService service = mock(MediaMtxAuthService.class);
        when(service.authorize(eq("internal"), any()))
                .thenReturn(MediaMtxAuthResult.denied(HttpStatus.UNAUTHORIZED, "INVALID_PUBLISH_TICKET"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MediaMtxAuthController(service)).build();

        mvc.perform(post("/api/v1/internal/mediamtx/auth?serviceToken=internal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"publish\",\"protocol\":\"rtmp\",\"path\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-PDK-MediaMTX-Reason", "INVALID_PUBLISH_TICKET"))
                .andExpect(content().string(""));
    }

    @Test
    void allowedDecisionUsesHttp204InsteadOfCommonResultEnvelope() throws Exception {
        MediaMtxAuthService service = mock(MediaMtxAuthService.class);
        when(service.authorize(eq("internal"), any())).thenReturn(MediaMtxAuthResult.allowed());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MediaMtxAuthController(service)).build();

        mvc.perform(post("/api/v1/internal/mediamtx/auth?serviceToken=internal")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }
}
