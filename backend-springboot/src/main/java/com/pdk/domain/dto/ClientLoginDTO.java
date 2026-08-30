package com.pdk.domain.dto;

import com.pdk.domain.dto.ClientFingerprintDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class ClientLoginDTO {
    @Schema(description = "业务ID（appId），与请求头 X-PDK-App-ID 一致，用于隔离不同业务的设备/许可证")
    @Positive(message = "appId 必须为正整数")
    private Long appId;

    @Schema(description = "注册/分配的手机号，作为账号主体")
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号码格式错误")
    private String phone;

    @Schema(description = "设备唯一ID（机器级稳定标识）。Windows 存于 %ProgramData%\\PDK\\{appId}\\device_id，非Windows 存于 ~/.pdk_client/{appId}/device_id；用于设备绑定与克隆检测")
    @NotBlank(message = "设备ID不能为空")
    private String deviceId;

    @Schema(description = "登录密码（SHA-256(pepper+密码) 单向存储，不可逆向查看明文）")
    @NotBlank(message = "登录密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度必须为8到64位")
    private String password;

    @Schema(description = "卡密（可选）。仅 DEVICE_LICENSE 业务：本设备未绑定许可证时必填，用于完成激活（卡密↔设备↔手机号绑定）；"
            + "已绑定设备时携带会被忽略，直接按已激活设备登录；非 DEVICE_LICENSE 业务忽略此字段")
    @Size(max = 64, message = "卡密长度不能超过64位")
    private String cardKey;

    @Schema(description = "设备名称（可选）。不上报则由客户端用主机名兜底，回显在设备许可证列表")
    @Size(max = 128, message = "设备名称不能超过128位")
    private String deviceName;

    @Schema(description = "客户端版本号（可选，≤32位）")
    @Size(max = 32, message = "客户端版本不能超过32位")
    private String clientVersion;

    @Schema(description = "设备平台标识（可选，如 windows/python，≤32位）")
    @Size(max = 32, message = "设备平台不能超过32位")
    private String platform;

    @Schema(description = "设备硬件指纹（可选）。上报后服务端做 salted hash 用于克隆/换机检测；不上报则跳过，向后兼容老客户端")
    @Valid
    private ClientFingerprintDTO fingerprint;
}
