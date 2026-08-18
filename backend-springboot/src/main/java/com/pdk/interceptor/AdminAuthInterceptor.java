package com.pdk.interceptor;

import cn.dev33.satoken.stp.StpLogic;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.entity.AdminUser;
import com.pdk.domain.entity.User;
import com.pdk.domain.entity.UserCredential;
import com.pdk.mapper.AdminUserMapper;
import com.pdk.mapper.UserMapper;
import com.pdk.mapper.UserCredentialMapper;
import com.pdk.security.AdminPrincipal;
import com.pdk.security.RequirePermission;
import com.pdk.security.RolePermissions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {
    @Qualifier("adminStpLogic")
    private final StpLogic adminStpLogic;
    private final AdminUserMapper adminUserMapper;
    private final UserMapper userMapper;
    private final UserCredentialMapper credentialMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        adminStpLogic.checkLogin();
        String loginId = adminStpLogic.getLoginIdAsString();
        AdminPrincipal principal = resolve(loginId);
        if (principal == null) {
            adminStpLogic.logout();
            throw new BusinessException(40110, "管理员账号不存在或已停用");
        }

        if (handler instanceof HandlerMethod handlerMethod) {
            RequirePermission requirement = handlerMethod.getMethodAnnotation(RequirePermission.class);
            if (requirement == null) {
                requirement = handlerMethod.getBeanType().getAnnotation(RequirePermission.class);
            }
            if (requirement != null && !RolePermissions.has(principal.roleCode(), requirement.value())) {
                throw new BusinessException(40310, "当前角色无权执行该管理任务: " + requirement.value());
            }
        }

        request.setAttribute("pdkAdminPrincipal", principal);
        return true;
    }

    private AdminPrincipal resolve(String loginId) {
        if (loginId.startsWith("ADMIN:")) {
            AdminUser admin = adminUserMapper.selectById(Long.parseLong(loginId.substring(6)));
            if (admin == null || !"ACTIVE".equals(admin.getStatus()) || !"SUPER_ADMIN".equals(admin.getRoleCode())) return null;
            return new AdminPrincipal(admin.getId(), admin.getUsername(), admin.getDisplayName(), admin.getRoleCode(), "ADMIN");
        }
        if (loginId.startsWith("USER:")) {
            Long userId = Long.parseLong(loginId.substring(5));
            User user = userMapper.selectById(userId);
            UserCredential credential = credentialMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserCredential>()
                    .eq(UserCredential::getUserId, userId));
            if (user == null || credential == null || !"ACTIVE".equals(credential.getStatus()) || !"PARTNER".equals(credential.getRoleCode())) return null;
            return new AdminPrincipal(userId, user.getPhone(), user.getPhone(), "PARTNER", "USER");
        }
        return null;
    }
}
