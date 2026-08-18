package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("pdk_sms_verification")
public class SmsVerification {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String phone;
    private String purpose;
    private String codeHash;
    private String status;
    private LocalDateTime expireAt;
    private LocalDateTime usedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
