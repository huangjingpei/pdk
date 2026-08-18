package com.pdk.service;

import com.pdk.domain.entity.AdminUser;
import com.pdk.domain.entity.PdkAdminAuditLog;
import com.pdk.mapper.PdkAdminAuditLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminAuditServiceTest {
    @Mock private PdkAdminAuditLogMapper mapper;

    @Test
    void recordsOperatorRoleTargetAndForwardedIp() {
        AdminUser admin = new AdminUser();
        admin.setUsername("operations");
        admin.setRoleCode("OPERATIONS");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.8, 10.0.0.1");

        new AdminAuditService(mapper).record(admin, "UNBIND_DEVICE", "USER", "13800138000",
                "before", "after", "客服处理", request);

        ArgumentCaptor<PdkAdminAuditLog> captor = ArgumentCaptor.forClass(PdkAdminAuditLog.class);
        verify(mapper).insert(captor.capture());
        assertEquals("operations", captor.getValue().getAdminName());
        assertEquals("OPERATIONS", captor.getValue().getAdminRole());
        assertEquals("203.0.113.8", captor.getValue().getIpAddress());
        assertNotNull(captor.getValue().getCreatedAt());
    }
}
