package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("pdk_user_referral")
public class UserReferral {
    @TableId(type = IdType.AUTO) private Long id;
    private Long userId;
    private Long invitationCodeId;
    private Long partnerUserId;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
}
