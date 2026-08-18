package com.pdk.interceptor;

import cn.dev33.satoken.stp.StpLogic;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.entity.AdminUser;
import com.pdk.mapper.AdminUserMapper;
import com.pdk.mapper.UserMapper;
import com.pdk.mapper.UserCredentialMapper;
import com.pdk.security.AdminPrincipal;
import com.pdk.security.RequirePermission;
import com.pdk.security.RolePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAuthInterceptorTest {
    @Mock private StpLogic stpLogic;
    @Mock private AdminUserMapper adminUserMapper;
    @Mock private UserMapper userMapper;
    @Mock private UserCredentialMapper credentialMapper;

    @Test
    void partnerIsRejectedFromFinancePermission() throws Exception {
        com.pdk.domain.entity.User user = new com.pdk.domain.entity.User();
        user.setId(1L); user.setPhone("13800138000"); user.setStatus("ACTIVE");
        com.pdk.domain.entity.UserCredential credential = new com.pdk.domain.entity.UserCredential();
        credential.setUserId(1L); credential.setRoleCode("PARTNER"); credential.setStatus("ACTIVE");
        when(stpLogic.getLoginIdAsString()).thenReturn("USER:1");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(credentialMapper.selectOne(any())).thenReturn(credential);
        HandlerMethod handler = new HandlerMethod(new SecuredHandlers(),
                SecuredHandlers.class.getMethod("finance"));

        BusinessException error = assertThrows(BusinessException.class, () -> interceptor().preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), handler));
        assertEquals(40310, error.getCode());
    }

    @Test
    void superAdminCanAccessFinancePermission() throws Exception {
        AdminUser finance = admin("SUPER_ADMIN");
        finance.setId(2L); finance.setUsername("13454118762");
        when(stpLogic.getLoginIdAsString()).thenReturn("ADMIN:2");
        when(adminUserMapper.selectById(2L)).thenReturn(finance);
        MockHttpServletRequest request = new MockHttpServletRequest();
        HandlerMethod handler = new HandlerMethod(new SecuredHandlers(),
                SecuredHandlers.class.getMethod("finance"));

        assertTrue(interceptor().preHandle(request, new MockHttpServletResponse(), handler));
        assertInstanceOf(AdminPrincipal.class, request.getAttribute("pdkAdminPrincipal"));
        verify(stpLogic).checkLogin();
    }

    @Test
    void disabledAdminIsLoggedOut() {
        AdminUser disabled = admin("SUPER_ADMIN");
        disabled.setStatus("DISABLED");
        disabled.setId(3L);
        when(stpLogic.getLoginIdAsString()).thenReturn("ADMIN:3");
        when(adminUserMapper.selectById(3L)).thenReturn(disabled);

        assertEquals(40110, assertThrows(BusinessException.class, () -> interceptor().preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object())).getCode());
        verify(stpLogic).logout();
    }

    private AdminAuthInterceptor interceptor() {
        return new AdminAuthInterceptor(stpLogic, adminUserMapper, userMapper, credentialMapper);
    }

    private AdminUser admin(String role) {
        AdminUser user = new AdminUser();
        user.setId(1L);
        user.setRoleCode(role);
        user.setStatus("ACTIVE");
        return user;
    }

    static class SecuredHandlers {
        @RequirePermission(RolePermissions.FINANCE_VIEW)
        public void finance() {
        }
    }
}
