package com.pdk.business.zhibo.live.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SrsCallbackRequest(
        String action,
        @JsonProperty("client_id") String clientId,
        String ip,
        String vhost,
        String app,
        String stream,
        String param,
        @JsonProperty("server_id") String serverId,
        @JsonProperty("stream_url") String streamUrl,
        @JsonProperty("stream_id") String streamId) {

    public String path() {
        String appPart = app == null ? "" : app.replaceAll("^/+|/+$", "");
        String streamPart = stream == null ? "" : stream.replaceAll("^/+|/+$", "");
        return appPart + "/" + streamPart;
    }
}
