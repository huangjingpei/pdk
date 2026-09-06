package com.pdk.business.zhibo.live.media;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class MediaHttpClientFactory {
    private MediaHttpClientFactory() {
    }

    public static RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2000);
        requestFactory.setReadTimeout(3000);
        return RestClient.builder()
                .baseUrl(trim(baseUrl))
                .requestFactory(requestFactory)
                .build();
    }

    public static String trim(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }
}
