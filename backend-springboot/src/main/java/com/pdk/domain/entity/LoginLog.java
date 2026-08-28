package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录与敏感动作日志。客户端用户与后台管理员共用，按 actorType 区分。
 * 只做留痕与审计，不参与任何业务判断。
 */
@Data
@TableName("pdk_login_log")
public class LoginLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 客户端用户所属业务；管理员登录为 null。 */
    private Long bizId;

    /** CLIENT 客户端用户, ADMIN 后台管理员。 */
    private String actorType;

    /** pdk_user.id 或 pdk_admin_user.id；账号不存在时为 null。 */
    private Long actorId;

    /** 登录账号：手机号或管理账号名。 */
    private String actorAccount;

    /** LOGIN, LOGOUT, PASSWORD_RESET, FORCE_CHANGE, DEVICE_UNBIND。 */
    private String eventType;

    /** SUCCESS, FAIL。 */
    private String result;

    /** 失败原因，取业务异常文案。 */
    private String failReason;

    private String ipAddress;

    /** 客户端登录携带的设备指纹。 */
    private String deviceId;

    private String userAgent;

    private LocalDateTime createdAt;
}
