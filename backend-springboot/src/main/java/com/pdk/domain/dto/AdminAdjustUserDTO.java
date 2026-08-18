package com.pdk.domain.dto;

import lombok.Data;

/**
 * 管理员手动调整客户端用户权益的请求体（对应「管理员把套餐绑定到用户表格」的例外操作）。
 *
 * <p>三个字段均为可选，按需组合，单次调用可同时生效：
 * <ul>
 *   <li>{@code packagePlanId}：绑定/更换套餐版本（须 ACTIVE）。绑定后会累加该套餐的调用次数、
 *       延长有效期、并按需提升并发账号上限，语义与卡密激活一致。</li>
 *   <li>{@code extraCalls}：在现有剩余次数上额外增减（可为负，最终下限为 0），用于人工补次数。</li>
 *   <li>{@code extendDays}：在现有到期时间上顺延天数（正数），用于人工延长期限。</li>
 * </ul>
 * 所有变更都会写入 {@code pdk_admin_audit_log} 留痕。
 */
@Data
public class AdminAdjustUserDTO {
    /** 绑定/更换的套餐版本 ID（pdk_package_plan.id），null 表示不改动套餐。 */
    private Integer packagePlanId;

    /** 额外增减的调用次数，默认 0。 */
    private Integer extraCalls = 0;

    /** 额外顺延的有效天数，默认 0。 */
    private Integer extendDays = 0;
}
