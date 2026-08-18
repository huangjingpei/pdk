package com.pdk.service.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "pdk.sms.provider", havingValue = "local", matchIfMissing = true)
public class LocalSmsSender implements SmsSender {
    @Override
    public void sendVerificationCode(String phone, String code, int expireMinutes) {
        log.warn("本地短信模式: phone={}, code={}, {}分钟内有效。生产环境禁止使用本模式", phone, code, expireMinutes);
    }
}
