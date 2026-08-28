package com.pdk.controller;

import cn.dev33.satoken.stp.StpLogic;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pdk.common.api.CommonResult;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.ClientLoginDTO;
import com.pdk.domain.dto.ClientRegisterDTO;
import com.pdk.domain.entity.User;
import com.pdk.domain.entity.UserCredential;
import com.pdk.mapper.UserMapper;
import com.pdk.mapper.UserCredentialMapper;
import com.pdk.platform.business.BusinessRequestResolver;
import com.pdk.platform.business.BusinessContext;
import com.pdk.service.AccountAssignmentService;
import com.pdk.service.DeviceBindingService;
import com.pdk.service.InvitationService;
import com.pdk.service.LoginLogService;
import com.pdk.service.SmsCodeService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ClientAuthScenariosTest {

    @Mock private UserMapper userMapper;
    @Mock private UserCredentialMapper credentialMapper;
    @Mock private DeviceBindingService deviceBindingService;
    @Mock private SmsCodeService smsCodeService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AccountAssignmentService assignmentService;
    @Mock private InvitationService invitationService;
    @Mock private LoginLogService loginLogService;
    @Mock private StpLogic clientStpLogic;
    @Mock private BusinessRequestResolver businessRequestResolver;

    @InjectMocks private ClientAuthController controller;

    @BeforeEach
    void setUp() {
        lenient().when(clientStpLogic.getTokenName()).thenReturn("satoken");
        lenient().when(clientStpLogic.getTokenValue()).thenReturn("mock-client-token");
        lenient().when(businessRequestResolver.resolveContextAndBind(any(HttpServletRequest.class), nullable(Long.class)))
                .thenReturn(context());
        lenient().when(userMapper.insert(any(User.class))).thenAnswer(inv -> {
            User user = inv.getArgument(0); if (user.getId() == null) user.setId(1L); return 1;
        });
    }

    @Test
    @DisplayName("S1: 正常注册 -> 开通试用并下发客户端会话")
    void registerSuccessIssuesClientSession() {
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(invitationService.findUsable(eq(1L), isNull())).thenReturn(null);
        when(assignmentService.allocateTrial(any(User.class), eq(1), eq(20))).thenReturn(true);
        when(passwordEncoder.encode("test123456")).thenReturn("enc");

        ClientRegisterDTO dto = new ClientRegisterDTO();
        dto.setPhone("13800138000");
        dto.setSmsCode("123456");
        dto.setPassword("test123456");
        dto.setDeviceId("MAC-A");
        dto.setAppId(1L);

        CommonResult<Map<String, Object>> res = controller.register(dto, request(1));

        assertEquals(200, res.getCode());
        Map<String, Object> data = res.getData();
        assertEquals("satoken", data.get("tokenName"));
        assertEquals("mock-client-token", data.get("tokenValue"));
        assertEquals("TRIAL", data.get("status"));
        assertEquals(1L, data.get("appId"));
        assertEquals(20, data.get("remainingCalls"));
        assertTrue((Boolean) data.get("resourceAllocated"));

        verify(userMapper).insert(any(User.class));
        verify(credentialMapper).insert(any(UserCredential.class));
        verify(clientStpLogic).login(any());
        verify(deviceBindingService).bind(1L, 1L, "MAC-A");
    }

    @Test
    @DisplayName("S1: 手机号已注册 -> 拒绝")
    void registerDuplicatePhoneRejected() {
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        ClientRegisterDTO dto = new ClientRegisterDTO();
        dto.setPhone("13800138000");
        dto.setSmsCode("123456");
        dto.setPassword("test123456");
        dto.setDeviceId("MAC-A");

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.register(dto, request(1)));
        assertEquals(40010, ex.getCode());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    @DisplayName("S2: 正常登录 -> 绑定设备并下发会话")
    void loginSuccessBindsDevice() {
        User user = new User();
        user.setId(1L); user.setBizId(1L); user.setPhone("13800138000"); user.setStatus("ACTIVE"); user.setDeviceId(null);
        UserCredential cred = new UserCredential();
        cred.setStatus("ACTIVE"); cred.setPasswordHash("enc"); cred.setRoleCode("CUSTOMER");

        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);
        when(credentialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cred);
        when(passwordEncoder.matches("test123456", "enc")).thenReturn(true);

        ClientLoginDTO dto = new ClientLoginDTO();
        dto.setPhone("13800138000"); dto.setDeviceId("MAC-A"); dto.setPassword("test123456");

        CommonResult<Map<String, Object>> res = controller.login(dto, request(1));

        assertEquals(200, res.getCode());
        assertEquals("mock-client-token", res.getData().get("tokenValue"));
        verify(clientStpLogic).login(1L);
        verify(deviceBindingService).bind(1L, 1L, "MAC-A");
    }

    @Test
    @DisplayName("S2: 已绑定其他电脑 -> 40103 拒绝互踢")
    void loginFromOtherDeviceRejected() {
        User user = new User();
        user.setId(1L); user.setBizId(1L); user.setPhone("13800138000"); user.setStatus("ACTIVE"); user.setDeviceId("MAC-OLD");
        UserCredential cred = new UserCredential();
        cred.setStatus("ACTIVE"); cred.setPasswordHash("enc"); cred.setRoleCode("CUSTOMER");

        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(user);
        when(credentialMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cred);
        when(passwordEncoder.matches("test123456", "enc")).thenReturn(true);

        ClientLoginDTO dto = new ClientLoginDTO();
        dto.setPhone("13800138000"); dto.setDeviceId("MAC-NEW"); dto.setPassword("test123456");

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.login(dto, request(1)));
        assertEquals(40103, ex.getCode());
        verify(clientStpLogic, never()).login(any());
    }

    @Test
    @DisplayName("S8: 解绑设备 -> 清空服务端 deviceId 并注销会话")
    void unbindDeviceClearsAndLogsOut() {
        User user = new User();
        user.setId(7L); user.setBizId(1L); user.setPhone("13800138000"); user.setDeviceId("MAC-A");
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("pdkClientUser")).thenReturn(user);

        CommonResult<String> res = controller.unbindDevice(req);

        assertEquals(200, res.getCode());
        assertTrue(res.getData().contains("新电脑"));
        verify(userMapper).update(isNull(), any());
        verify(deviceBindingService).unbind(1L, 7L);
        verify(clientStpLogic).logout();
    }

    private MockHttpServletRequest request(long appId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(BusinessRequestResolver.APP_ID_HEADER, String.valueOf(appId));
        return request;
    }

    private BusinessContext context() {
        return new BusinessContext(1, 1, "PDD", "拼多多", "desc", "SELF_SERVICE",
                true, 24, 1, 20, false);
    }
}
