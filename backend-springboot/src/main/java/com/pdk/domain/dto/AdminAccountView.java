package com.pdk.domain.dto;

import java.time.LocalDateTime;

public record AdminAccountView(
        Long id,
        Long bizId,
        String username,
        String displayName,
        String roleCode,
        String status,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt) {
}
