package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("pdk_invitation_code")
public class InvitationCode {
    @TableId(type = IdType.AUTO) private Long id;
    private String code;
    private Long ownerUserId;
    private String status;
    private Integer maxUses;
    private Integer usedCount;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
}
