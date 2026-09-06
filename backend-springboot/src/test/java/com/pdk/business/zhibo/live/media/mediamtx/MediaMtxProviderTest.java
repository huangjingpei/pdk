package com.pdk.business.zhibo.live.media.mediamtx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdk.business.zhibo.live.entity.MediaServerNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MediaMtxProviderTest {

    @Test
    void kickTreatsAlreadyDisconnectedPublisherAsStopped() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/rtmpconns/kick/conn-gone", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            MediaServerNode node = new MediaServerNode();
            node.setInternalApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

            assertDoesNotThrow(() -> new MediaMtxProvider(new ObjectMapper())
                    .kickPublisher(node, "conn-gone", "RTMP"));
        } finally {
            server.stop(0);
        }
    }
}
