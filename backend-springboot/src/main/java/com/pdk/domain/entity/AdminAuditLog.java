package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("pdk_admin_audit_log")
public class AdminAuditLog implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String operatorUsername;
    private String operatorRole; // SUPER_ADMIN, AGENT
    private String actionType; // ACTIVATE_CARD, CREATE_CARD, PURCHASE_TOKEN, FREE_GIFT
    private String targetIdentifier;
    private String detailsJson;
    private String clientIp;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
