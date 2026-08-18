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
    private String cardKey;
    private Integer packageId;
    private String status; // UNUSED, ACTIVATED, VOID
    private String generatedByAdmin;
    private Long agentId;
    private String activatedByPhone;
    private LocalDateTime activatedAt;
    private String activatedDeviceId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
