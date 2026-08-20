package com.pdk.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理后台「客户当前套餐使用详情」视图对象。
 * 汇总层：套餐名 / 剩余总次数 / 已分配总次数 / 已用总次数 / 到期时间；
 * 明细层：该客户名下每个底层小号槽位（account assignment）的使用情况。
 */
@Data
public class UserAssignmentDetail {
    private Long userId;
    private String phone;
    private String currentPackageName;
    private LocalDateTime expireTime;
    /** 用户级剩余总次数（由 assignment 槽位派生的权威值） */
    private Integer remainingCalls;
    /** 所有 ACTIVE 槽位的 allocated_calls 之和 */
    private Integer totalAllocated;
    /** 所有 ACTIVE 槽位的 used_calls 之和 */
    private Integer totalUsed;
    private List<AssignmentItem> accounts;

    @Data
    public static class AssignmentItem {
        private Integer slotIndex;
        private String uuid;
        private String accountAlias;
        private String healthStatus; // HEALTHY, BUSY, FAULT_BLACK, EXPIRED
        private Integer allocatedCalls;
        private Integer usedCalls;
        private Integer remaining; // allocated - used
        private String status;     // ACTIVE, RELEASED, REPLACED ...
        private LocalDateTime expireAt;
    }
}
