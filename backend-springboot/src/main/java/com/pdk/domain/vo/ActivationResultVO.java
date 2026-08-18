package com.pdk.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivationResultVO {
    private String userPhone;
    private String cardKey;
    private String packageName;
    private LocalDateTime newExpireTime;
    private Integer extendedDays;
    private Integer totalRemainingCalls;
    private Integer totalAddedCalls;
    private String incomeOrderNo;
    private String queueActionType; // DIRECT_EXTEND (同套餐直接顺延) / QUEUED (不同套餐排队生效)
}
