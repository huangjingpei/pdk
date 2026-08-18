package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("pdk_token_pool")
public class TokenPool implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tokenVal;
    private String accountAlias;
    private String healthStatus; // HEALTHY, BUSY, FAULT_BLACK, EXPIRED
    private Integer dailyCallsCount;
    private Integer dailyMaxCapacity;
    private Integer riskScore;
    private String leaseClientPhone;
    private LocalDateTime leasedAt;
    private LocalDateTime lastFaultTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
