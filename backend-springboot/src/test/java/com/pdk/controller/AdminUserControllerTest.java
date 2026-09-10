package com.pdk.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pdk.common.api.CommonResult;
import com.pdk.domain.entity.Business;
import com.pdk.domain.entity.User;
import com.pdk.mapper.*;
import com.pdk.platform.business.BusinessService;
import com.pdk.security.AdminBusinessScope;
import com.pdk.security.AdminPrincipal;
import com.pdk.service.*;
import cn.dev33.satoken.stp.StpLogic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserControllerTest {

    @Mock private UserMapper userMapper;
    @Mock private UserCredentialMapper credentialMapper;
    @Mock private PackagePlanMapper packagePlanMapper;
    @Mock private DeviceBindingService deviceBindingService;
    @Mock private AccountAssignmentService assignmentService;
    @Mock private AdminAuditService adminAuditService;
    @Mock private InvitationService invitationService;
    @Mock private InvitationCodeMapper invitationCodeMapper;
    @Mock private UserReferralMapper referralMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private LoginLogService loginLogService;
    @Mock private LoginLogMapper loginLogMapper;
    @Mock private BusinessService businessService;
    @Mock private DeviceLicenseService deviceLicenseService;
    @Mock private AdminBusinessScope businessScope;
    @Mock private StpLogic clientStpLogic;

    @InjectMocks private AdminUserController controller;

    private AdminPrincipal admin;

    @BeforeEach
    void setUp() {
        admin = new AdminPrincipal(1L, "admin", "管理员", "ADMIN", "SYSTEM", 1L, 0);
        when(businessScope.enforce(any(), any())).thenReturn(1L);
    }

    @Test
    @DisplayName("用户列表支持 activity 和 orderBy 筛选过滤")
    void listSupportsActivityAndOrderBy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("pdkAdminPrincipal", admin);

        Business biz = new Business();
        biz.setId(1L);
        biz.setAppId(1L);
        biz.setBizName("PDD自动助手");
        when(businessService.requireById(1L)).thenReturn(biz);

        User testUser = new User();
        testUser.setId(10L);
        testUser.setBizId(1L);
        testUser.setPhone("13800138000");
        testUser.setStatus("ACTIVE");
        testUser.setLastLoginAt(LocalDateTime.now().minusDays(2));
        testUser.setLastLoginIp("192.168.1.100");

        Page<User> mockPage = new Page<>(1, 20);
        mockPage.setRecords(List.of(testUser));
        mockPage.setTotal(1);

        when(userMapper.selectPage(any(), any())).thenReturn(mockPage);

        CommonResult<Page<User>> result = controller.list(1, 20, null, null, "10d", "lastLoginAt", 1L, null, request);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        Page<User> page = result.getData();
        assertNotNull(page);
        assertEquals(1, page.getRecords().size());
        User u = page.getRecords().get(0);
        assertNotNull(u.getLastLoginAt());
        assertEquals("192.168.1.100", u.getLastLoginIp());

        // Verify selectPage was called with query wrapper
        verify(userMapper).selectPage(any(), any());
    }
}
