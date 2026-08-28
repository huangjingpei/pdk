package com.pdk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.entity.InvitationCode;
import com.pdk.domain.entity.UserCredential;
import com.pdk.domain.entity.UserReferral;
import com.pdk.mapper.InvitationCodeMapper;
import com.pdk.mapper.UserCredentialMapper;
import com.pdk.mapper.UserReferralMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Service
@RequiredArgsConstructor
public class InvitationService {
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final InvitationCodeMapper codeMapper;
    private final UserReferralMapper referralMapper;
    private final UserCredentialMapper credentialMapper;
    private final SecureRandom random = new SecureRandom();

    public InvitationCode findUsable(Long bizId, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) return null;
        InvitationCode code = codeMapper.selectOne(new LambdaQueryWrapper<InvitationCode>()
                .eq(InvitationCode::getBizId, bizId)
                .eq(InvitationCode::getCode, rawCode.trim().toUpperCase()));
        if (code == null || !"ACTIVE".equals(code.getStatus())
                || (code.getMaxUses() != null && code.getUsedCount() >= code.getMaxUses())) {
            throw new BusinessException(40018, "邀请码不存在、已停用或已达到使用上限");
        }
        UserCredential owner = credentialMapper.selectOne(new LambdaQueryWrapper<UserCredential>()
                .eq(UserCredential::getUserId, code.getOwnerUserId()));
        if (owner == null || !"PARTNER".equals(owner.getRoleCode()) || !"ACTIVE".equals(owner.getStatus())) {
            throw new BusinessException(40018, "邀请码所属代理当前不可用");
        }
        return code;
    }

    public void bind(Long bizId, Long userId, InvitationCode code) {
        if (code == null) return;
        UserReferral referral = new UserReferral();
        referral.setBizId(bizId);
        referral.setUserId(userId);
        referral.setInvitationCodeId(code.getId());
        referral.setPartnerUserId(code.getOwnerUserId());
        referralMapper.insert(referral);
        code.setUsedCount(code.getUsedCount() + 1);
        codeMapper.updateById(code);
    }

    public InvitationCode ensurePartnerCode(Long bizId, Long partnerUserId) {
        InvitationCode existing = codeMapper.selectOne(new LambdaQueryWrapper<InvitationCode>()
                .eq(InvitationCode::getBizId, bizId)
                .eq(InvitationCode::getOwnerUserId, partnerUserId));
        if (existing != null) return existing;
        InvitationCode code = new InvitationCode();
        code.setBizId(bizId);
        code.setOwnerUserId(partnerUserId);
        code.setCode(nextCode(bizId));
        code.setStatus("ACTIVE");
        code.setUsedCount(0);
        codeMapper.insert(code);
        return code;
    }

    private String nextCode(Long bizId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder value = new StringBuilder("P");
            for (int i = 0; i < 7; i++) value.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            if (codeMapper.selectCount(new LambdaQueryWrapper<InvitationCode>()
                    .eq(InvitationCode::getBizId, bizId)
                    .eq(InvitationCode::getCode, value.toString())) == 0) return value.toString();
        }
        throw new BusinessException(50018, "邀请码生成失败，请重试");
    }
}
