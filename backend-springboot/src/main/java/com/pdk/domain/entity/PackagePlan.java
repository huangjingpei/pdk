package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("pdk_package_plan")
public class PackagePlan {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private Long bizId;
    private Long ownerUserId;
    private String name;
    private Integer versionNo;
    private BigDecimal listPrice;
    private BigDecimal discountRate;
    private BigDecimal salePrice;
    private Integer durationHours;
    private Integer accountCount;
    private Integer callsPerAccount;
    private String status;
    private String description;
    private String createdBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    private LocalDateTime disabledAt;
}
