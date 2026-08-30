package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备硬件指纹，用于克隆检测。
 * 与 pdk_user_device 1:1 关联（user_device_id 唯一），独立成表以不改动既有 pdk_user_device 列，
 * 避免影响线上设备许可证页面查询。
 *
 * 关键设计（应对“硬件指纹在极端情况下仍可能不唯一”）：
 * - fp_hash 为 null 表示「退化指纹」（三个组件全部为空/默认值），此时不做指纹克隆判定，仅以 deviceId 令牌为主键。
 * - 跨设备指纹碰撞仅在 confidence>=2（至少两个组件可读）时启用，避免单组件弱指纹偶发碰撞误杀合法用户。
 * - deviceId 令牌始终是绑定主轴，指纹只是辅助信号；克隆判定只“暂停/存疑”，不会让拥有唯一令牌的合法用户被错杀。
 */
@Data
@TableName("pdk_device_fingerprint")
public class DeviceFingerprint {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long bizId;
    private Long userId;
    private Long userDeviceId;
    private String deviceIdHash;
    private String fpJson;
    private String fpHash;
    private String fpVersion;
    private Integer fpConfidence;
    private String fpStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
