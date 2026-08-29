package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("pdk_license_renewal")
public class LicenseRenewal {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long bizId;
    private Long licenseId;
    private Long cardKeyId;
    private Long userId;
    private String renewalOrderNo;
    private LocalDateTime beforeExpireAt;
    private Integer durationHours;
    private LocalDateTime afterExpireAt;
    private Integer addedCalls;
    private BigDecimal amount;
    private String paymentChannel;
    private String operatorId;
    private String remark;
    private LocalDateTime createdAt;
}
