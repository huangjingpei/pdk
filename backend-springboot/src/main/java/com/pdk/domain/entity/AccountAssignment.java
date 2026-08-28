package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("pdk_account_assignment")
public class AccountAssignment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long bizId;
    private Long userId;
    private Long tokenId;
    private Integer packagePlanId;
    private Long cardKeyId;
    private Integer slotIndex;
    private Integer allocatedCalls;
    private Integer usedCalls;
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime assignedAt;
    private LocalDateTime expireAt;
    private LocalDateTime releasedAt;
    private Long replacedByAssignmentId;
}
