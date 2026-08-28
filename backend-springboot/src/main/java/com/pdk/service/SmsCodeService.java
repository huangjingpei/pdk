package com.pdk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.entity.SmsVerification;
import com.pdk.mapper.SmsVerificationMapper;
import com.pdk.service.sms.SmsSender;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class SmsCodeService {
    private final SmsVerificationMapper verificationMapper;
    private final SmsSender smsSender;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${pdk.sms.code-expire-minutes:5}") private int expireMinutes;
    @Value("${pdk.sms.local.fixed-code-enabled:false}") private boolean fixedCodeEnabled;
    @Value("${pdk.sms.local.fixed-code:}") private String fixedCode;

    @Transactional
    public String send(Long bizId, String phone, String purpose) {
        SmsVerification latest = verificationMapper.selectOne(new LambdaQueryWrapper<SmsVerification>()
                .eq(SmsVerification::getBizId, bizId)
                .eq(SmsVerification::getPhone, phone)
                .eq(SmsVerification::getPurpose, purpose)
                .orderByDesc(SmsVerification::getCreatedAt)
                .last("LIMIT 1"));
        if (latest != null && latest.getCreatedAt() != null && latest.getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(60))) {
            throw new BusinessException(42901, "短信发送过于频繁，请60秒后重试");
        }
        String code = fixedCodeEnabled ? fixedCode : String.format("%06d", secureRandom.nextInt(1_000_000));
        SmsVerification record = new SmsVerification();
        record.setBizId(bizId);
        record.setPhone(phone);
        record.setPurpose(purpose);
        record.setCodeHash(hash(bizId, phone, purpose, code));
        record.setStatus("PENDING");
        record.setExpireAt(LocalDateTime.now().plusMinutes(expireMinutes));
        verificationMapper.insert(record);
        smsSender.sendVerificationCode(phone, code, expireMinutes);
        return fixedCodeEnabled ? code : null;
    }

    @Transactional
    public void verify(Long bizId, String phone, String purpose, String code) {
        SmsVerification record = verificationMapper.selectOne(new LambdaQueryWrapper<SmsVerification>()
                .eq(SmsVerification::getBizId, bizId)
                .eq(SmsVerification::getPhone, phone)
                .eq(SmsVerification::getPurpose, purpose)
                .eq(SmsVerification::getStatus, "PENDING")
                .orderByDesc(SmsVerification::getCreatedAt)
                .last("LIMIT 1 FOR UPDATE"));
        if (record == null || record.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(40011, "短信验证码不存在或已过期");
        }
        if (!MessageDigest.isEqual(record.getCodeHash().getBytes(StandardCharsets.UTF_8),
                hash(bizId, phone, purpose, code).getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(40012, "短信验证码错误");
        }
        record.setStatus("USED");
        record.setUsedAt(LocalDateTime.now());
        verificationMapper.updateById(record);
    }

    private String hash(Long bizId, String phone, String purpose, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((bizId + ":" + phone + ":" + purpose + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
