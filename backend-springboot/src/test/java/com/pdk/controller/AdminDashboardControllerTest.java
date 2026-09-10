package com.pdk.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pdk.common.api.CommonResult;
import com.pdk.domain.entity.User;
import com.pdk.domain.entity.UserDevice;
import com.pdk.mapper.CardKeyMapper;
import com.pdk.mapper.TokenPoolMapper;
import com.pdk.mapper.UserDeviceMapper;
import com.pdk.mapper.UserMapper;
import com.pdk.platform.business.BusinessService;
import com.pdk.security.AdminBusinessScope;
import com.pdk.security.AdminPrincipal;
import com.pdk.service.IFinancialService;
import com.pdk.business.zhibo.live.service.MediaServerNodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminDashboardControllerTest {

    @Mock private UserMapper userMapper;
    @Mock private UserDeviceMapper userDeviceMapper;
    @Mock private CardKeyMapper cardKeyMapper;
    @Mock private TokenPoolMapper tokenPoolMapper;
    @Mock private IFinancialService financialService;
    @Mock private AdminBusinessScope businessScope;
    @Mock private MediaServerNodeService mediaServerNodeService;
    @Mock private BusinessService businessService;

    @InjectMocks private AdminDashboardController controller;

    private AdminPrincipal superAdmin;

    @BeforeEach
    void setUp() {
        superAdmin = new AdminPrincipal(1L, "superadmin", "超级管理员", "SUPER_ADMIN", "SYSTEM", 1L, 0);
        when(businessScope.enforce(any(), any())).thenReturn(1L);
    }

    @Test
    @DisplayName("Dashboard Summary 成功聚合用户与设备 10/30/90 天活跃度")
    void summaryAggregatesActivity() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("pdkAdminPrincipal", superAdmin);

        when(userMapper.selectCount(any())).thenReturn(100L, 95L, 25L, 50L, 80L);
        when(userDeviceMapper.selectCount(any())).thenReturn(60L, 55L, 20L, 45L, 52L);

        com.pdk.domain.entity.Business liveBusiness = new com.pdk.domain.entity.Business();
        liveBusiness.setId(3L);
        when(businessService.requireByAppId(3)).thenReturn(liveBusiness);

        CommonResult<Map<String, Object>> result = controller.summary(request);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        Map<String, Object> data = result.getData();
        assertNotNull(data);

        assertEquals(100L, data.get("userCount"));
        assertEquals(95L, data.get("activeUserCount"));
        assertEquals(25L, data.get("activeUsers10d"));
        assertEquals(50L, data.get("activeUsers30d"));
        assertEquals(80L, data.get("activeUsers90d"));

        assertEquals(60L, data.get("deviceCount"));
        assertEquals(55L, data.get("activeDeviceCount"));
        assertEquals(20L, data.get("activeDevices10d"));
        assertEquals(45L, data.get("activeDevices30d"));
        assertEquals(52L, data.get("activeDevices90d"));

        assertTrue(data.containsKey("activity"));
        @SuppressWarnings("unchecked")
        Map<String, Object> activity = (Map<String, Object>) data.get("activity");
        assertEquals(25L, activity.get("activeUsers10d"));
        assertEquals(50L, activity.get("activeUsers30d"));
        assertEquals(80L, activity.get("activeUsers90d"));
        assertEquals(20L, activity.get("activeDevices10d"));
        assertEquals(45L, activity.get("activeDevices30d"));
        assertEquals(52L, activity.get("activeDevices90d"));
    }
}
