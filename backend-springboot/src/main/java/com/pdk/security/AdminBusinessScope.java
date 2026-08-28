package com.pdk.security;

import com.pdk.common.exception.BusinessException;
import org.springframework.stereotype.Component;

/** 后台业务数据范围的服务端强制边界。 */
@Component
public class AdminBusinessScope {
    public Long enforce(AdminPrincipal principal, Long requestedBizId) {
        if (principal == null) throw new BusinessException(40110, "管理员会话不存在");
        if (principal.isSuperAdmin()) return requestedBizId;
        if (principal.bizId() == null) throw new BusinessException(40311, "代理账号尚未绑定业务");
        if (requestedBizId != null && !principal.bizId().equals(requestedBizId)) {
            throw new BusinessException(40311, "代理无权访问其他业务数据");
        }
        return principal.bizId();
    }
}
