package com.pdk.service.sms;

import com.pdk.common.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "pdk.sms.provider", havingValue = "aliyun")
public class AliyunSmsSender implements SmsSender {
    @Override
    public void sendVerificationCode(String phone, String code, int expireMinutes) {
        throw new BusinessException(50320, "阿里云短信适配器已预留，但尚未安装阿里云 SDK；请先保持 PDK_SMS_PROVIDER=local");
    }
}
