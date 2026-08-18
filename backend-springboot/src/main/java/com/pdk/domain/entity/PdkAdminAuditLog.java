package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员核心操作不可逆审计日志表 (永久留痕)
 */
@Data
@TableName("pdk_admin_audit_log")
public class PdkAdminAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作管理员账号 */
    private String adminName;

    /** 管理员角色: SUPER_ADMIN, FINANCE, AGENT */
    private String adminRole;

    /** 操作类型: MANUAL_ADJUST_QUOTA, EXTEND_EXPIRE, VOID_CARD, BLOCK_USER, GENERATE_CARD */
    private String actionType;

    /** 目标对象类型: USER, CARD, ACCOUNT, PACKAGE */
    private String targetType;

    /** 目标对象标识 (如手机号或卡密序列号) */
    private String targetId;

    /** 修改前状态快照 JSON */
    private String beforeState;

    /** 修改后状态快照 JSON */
    private String afterState;

    /** 人工操作必须填写的原因备注 */
    private String reason;

    /** 操作人客户端IP */
    private String ipAddress;

    /** 审计记录时间 */
    private LocalDateTime createdAt;
}
