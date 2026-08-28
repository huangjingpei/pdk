package com.pdk.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 单个用户最近一次登录成功的聚合结果，用于列表页批量回填，避免逐行回查。 */
@Data
public class LastLoginView {
    private Long actorId;
    private LocalDateTime lastLoginAt;
    private String lastLoginIp;
}
