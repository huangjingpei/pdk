package com.pdk.service.sms;

public interface SmsSender {
    void sendVerificationCode(String phone, String code, int expireMinutes);
}
