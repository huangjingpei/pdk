package com.pdk.interceptor;

import cn.dev33.satoken.stp.StpLogic;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.entity.User;
import com.pdk.mapper.UserMapper;
import com.pdk.platform.business.BusinessRequestResolver;
import com.pdk.platform.business.BusinessContext;
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
    @Mock private BusinessRequestResolver businessRequestResolver;

    @Test
    void matchingPhoneAndDevicePasses() {
        User user = user();
        when(stpLogic.getLoginIdAsLong()).thenReturn(1L);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(bindingService.get(1L, 1L)).thenReturn("DEVICE-A");
        MockHttpServletRequest request = request("13800138000", "DEVICE-A");

        assertTrue(interceptor().preHandle(request, new MockHttpServletResponse(), new Object()));
        assertSame(user, request.getAttribute("pdkClientUser"));
        assertEquals(1L, request.getAttribute(BusinessRequestResolver.ATTR_APP_ID));
        verify(bindingService).bind(1L, 1L, "DEVICE-A");
    }

    @Test
    void mismatchedPhoneOrDeviceIsRejected() {
        User user = user();
        when(stpLogic.getLoginIdAsLong()).thenReturn(1L);
        when(userMapper.selectById(1L)).thenReturn(user);

        assertEquals(40102, assertThrows(BusinessException.class, () -> interceptor().preHandle(
                request("13900000000", "DEVICE-A"), new MockHttpServletResponse(), new Object())).getCode());

        when(bindingService.get(1L, 1L)).thenReturn("DEVICE-A");
        assertEquals(40103, assertThrows(BusinessException.class, () -> interceptor().preHandle(
                request(user.getPhone(), "DEVICE-B"), new MockHttpServletResponse(), new Object())).getCode());
    }

    private DeviceSecurityInterceptor interceptor() {
        doAnswer(inv -> {
            MockHttpServletRequest req = inv.getArgument(0);
            BusinessContext context = context();
            req.setAttribute(BusinessRequestResolver.ATTR_APP_ID, 1L);
            req.setAttribute(BusinessRequestResolver.ATTR_BIZ_ID, 1L);
            req.setAttribute(BusinessRequestResolver.ATTR_BUSINESS_CONTEXT, context);
            return context;
        }).when(businessRequestResolver).resolveContextAndBind(any(), isNull());
        return new DeviceSecurityInterceptor(stpLogic, userMapper, bindingService, businessRequestResolver);
    }

    private MockHttpServletRequest request(String phone, String device) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-PDK-Phone", phone);
        request.addHeader("X-PDK-Device-ID", device);
        request.addHeader(BusinessRequestResolver.APP_ID_HEADER, "1");
        return request;
    }

    private User user() {
        User user = new User();
        user.setId(1L);
        user.setBizId(1L);
        user.setPhone("13800138000");
        user.setDeviceId("DEVICE-A");
        user.setStatus("ACTIVE");
        return user;
    }

    private BusinessContext context() {
        return new BusinessContext(1, 1, "PDD", "拼多多", "desc", "SELF_SERVICE",
                true, 24, 1, 20, false);
    }
}
