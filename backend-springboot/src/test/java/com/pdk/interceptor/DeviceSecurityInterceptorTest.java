package com.pdk.interceptor;

import cn.dev33.satoken.stp.StpLogic;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.entity.User;
import com.pdk.mapper.UserMapper;
import com.pdk.service.DeviceBindingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceSecurityInterceptorTest {
    @Mock private StpLogic stpLogic;
    @Mock private UserMapper userMapper;
    @Mock private DeviceBindingService bindingService;

    @Test
    void matchingPhoneAndDevicePasses() {
        User user = user();
        when(stpLogic.getLoginIdAsLong()).thenReturn(1L);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(bindingService.get(user.getPhone())).thenReturn("DEVICE-A");
        MockHttpServletRequest request = request("13800138000", "DEVICE-A");

        assertTrue(interceptor().preHandle(request, new MockHttpServletResponse(), new Object()));
        assertSame(user, request.getAttribute("pdkClientUser"));
        verify(bindingService).bind(user.getPhone(), "DEVICE-A");
    }

    @Test
    void mismatchedPhoneOrDeviceIsRejected() {
        User user = user();
        when(stpLogic.getLoginIdAsLong()).thenReturn(1L);
        when(userMapper.selectById(1L)).thenReturn(user);

        assertEquals(40102, assertThrows(BusinessException.class, () -> interceptor().preHandle(
                request("13900000000", "DEVICE-A"), new MockHttpServletResponse(), new Object())).getCode());

        when(bindingService.get(user.getPhone())).thenReturn("DEVICE-A");
        assertEquals(40103, assertThrows(BusinessException.class, () -> interceptor().preHandle(
                request(user.getPhone(), "DEVICE-B"), new MockHttpServletResponse(), new Object())).getCode());
    }

    private DeviceSecurityInterceptor interceptor() {
        return new DeviceSecurityInterceptor(stpLogic, userMapper, bindingService);
    }

    private MockHttpServletRequest request(String phone, String device) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-PDK-Phone", phone);
        request.addHeader("X-PDK-Device-ID", device);
        return request;
    }

    private User user() {
        User user = new User();
        user.setId(1L);
        user.setPhone("13800138000");
        user.setDeviceId("DEVICE-A");
        user.setStatus("ACTIVE");
        return user;
    }
}
