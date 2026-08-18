package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务调度与扣费流水明细表 (不可物理删除)
 */
@Data
@TableName("pdk_dispatch_log")
public class PdkDispatchLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 客户端请求幂等唯一UUID (防重试重复扣费) */
    private String reqUuid;

    /** 调用用户手机号 */
    private String userPhone;

    /** 消耗的逻辑账号槽位 (1~X) */
    private Integer slotIndex;

    /** 实际承载调度的底层公司账号ID */
    private String realPddAccountId;

    /** 业务操作类型 (如: QUERY_ORDER, GET_GOODS) */
    private String actionType;

    /** 本次扣减次数 (成功扣1, 账号异常/免责扣0) */
    private Integer deductCount;

    /** 执行状态: SUCCESS, TOKEN_FAIL, PARAM_ERROR, NET_TIMEOUT, FAULT_HEALED */
    private String execStatus;

    /** 网关处理耗时 (ms) */
    private Integer responseTimeMs;

    /** 调度发生时间 */
    private LocalDateTime createdAt;
}
