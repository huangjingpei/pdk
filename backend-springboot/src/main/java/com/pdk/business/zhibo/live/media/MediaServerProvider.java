package com.pdk.business.zhibo.live.media;

import com.pdk.business.zhibo.live.entity.MediaServerNode;

public interface MediaServerProvider {
    String providerType();

    void validate(MediaServerNode node);

    MediaNodeSnapshotData snapshot(MediaServerNode node);

    String buildPublishUrl(MediaServerNode node, String path, String ticket);

    void kickPublisher(MediaServerNode node, String providerConnectionId, String protocol);
}
