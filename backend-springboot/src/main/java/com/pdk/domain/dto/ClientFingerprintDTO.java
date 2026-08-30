package com.pdk.domain.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 客户端上报的硬件指纹原始组件（明文由服务端做 salted hash，绝不落库明文）。
 * 仅采集主板序列、磁盘序列、CPUID 三项；任一为空/默认值时视为不可读，不计入置信度。
 */
@Data
public class ClientFingerprintDTO {
    @Size(max = 256, message = "主板序列号过长")
    private String motherboardSerial;

    @Size(max = 256, message = "磁盘序列号过长")
    private String diskSerial;

    @Size(max = 256, message = "CPUID 过长")
    private String cpuid;
}
