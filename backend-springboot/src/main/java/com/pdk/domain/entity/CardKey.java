package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("pdk_card_key")
public class CardKey implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long bizId;
    private String cardKey;
    private Integer packageId;
    private String status; // UNUSED, ASSIGNED, ACTIVATED, VOID
    private String generatedByAdmin;
    private Long agentId;
    private Long assignedUserId;
    private String assignedPhone;
    private LocalDateTime assignedAt;
    private String activatedByPhone;
    private Long activatedByUserId;
    private LocalDateTime activatedAt;
    private String activatedDeviceId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
