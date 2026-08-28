package com.pdk.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.pdk.common.exception.BusinessException;
import com.pdk.business.pdd.PddBusinessHandler;
import com.pdk.business.spi.BusinessHandlerRegistry;
import com.pdk.domain.dto.AcquireTokenRequestDTO;
import com.pdk.domain.dto.ReportResultDTO;
import com.pdk.domain.entity.PdkDispatchLog;
import com.pdk.domain.entity.TokenPool;
import com.pdk.domain.entity.User;
import com.pdk.domain.entity.AccountAssignment;
import com.pdk.domain.vo.EncryptedTokenPayloadVO;
import com.pdk.mapper.PdkDispatchLogMapper;
import com.pdk.mapper.TokenPoolMapper;
import com.pdk.mapper.UserMapper;
import com.pdk.service.impl.DispatchGatewayServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import com.pdk.platform.business.BusinessContext;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DispatchGatewayServiceImplTest {
    @Mock private TokenPoolMapper tokenPoolMapper;
    @Mock private UserMapper userMapper;
    @Mock private PdkDispatchLogMapper dispatchLogMapper;
    @Mock private DeviceBindingService deviceBindingService;
    @Mock private ResourceLeaseService resourceLeaseService;
    @Mock private AccountAssignmentService assignmentService;
    @InjectMocks private DispatchGatewayServiceImpl service;

    private User user;
    private TokenPool token;
    private AcquireTokenRequestDTO acquire;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), TokenPool.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PdkDispatchLog.class);
        ReflectionTestUtils.setField(service, "leaseSeconds", 300L);
        ReflectionTestUtils.setField(service, "businessRegistry",
                new BusinessHandlerRegistry(List.of(new PddBusinessHandler(
                        new com.pdk.business.pdd.PddActionValidator(),
                        new com.pdk.business.pdd.PddCredentialCodec(),
                        new com.pdk.business.pdd.PddFailureClassifier()))));

        user = new User();
        user.setId(1L);
        user.setBizId(1L);
        user.setPhone("13800138000");
        user.setDeviceId("DEVICE-A");
        user.setExpireTime(LocalDateTime.now().plusDays(1));
        user.setRemainingCalls(10);
        user.setDailyCallsLimit(20);

        token = new TokenPool();
        token.setId(8L);
        token.setBizId(1L);
        token.setTokenVal("secret-token");
        token.setAccountAlias("slot-8");
        token.setHealthStatus("HEALTHY");
        token.setDailyCallsCount(0);
        token.setDailyMaxCapacity(500);

        acquire = new AcquireTokenRequestDTO();
        acquire.setActionType("GOODS_COLLECT");
        acquire.setGoodsId("1001");
        acquire.setTimestamp(System.currentTimeMillis());
    }

    @Test
    @DisplayName("领取资源会校验用户并把底层资源标记为 BUSY")
    void acquireMarksTokenBusyAndCreatesLease() {
        when(deviceBindingService.get(1L, 1L)).thenReturn("DEVICE-A");
        AccountAssignment assignment = new AccountAssignment();
        assignment.setId(9L); assignment.setSlotIndex(1);
        when(assignmentService.acquire(user)).thenReturn(new AccountAssignmentService.AssignedResource(assignment, token));

        EncryptedTokenPayloadVO result = service.acquireEncryptedToken(acquire, context(), user, "DEVICE-A");

        assertNotNull(result.getEncryptedPayload());
        assertEquals(10, result.getRemainingUserQuota());
        assertEquals("BUSY", token.getHealthStatus());
        assertEquals(user.getPhone(), token.getLeaseClientPhone());
        verify(tokenPoolMapper).updateById(token);
        verify(resourceLeaseService).create(eq(result.getLeaseTraceId()), any(ResourceLeaseService.LeaseInfo.class));
    }

    @Test
    @DisplayName("过期、无配额和客户端时钟漂移均拒绝领取")
    void acquireRejectsInvalidEntitlementAndClock() {
        acquire.setTimestamp(System.currentTimeMillis() - 6 * 60 * 1000L);
        assertEquals(40012, assertThrows(BusinessException.class,
                () -> service.acquireEncryptedToken(acquire, context(), user, "DEVICE-A")).getCode());

        acquire.setTimestamp(System.currentTimeMillis());
        user.setExpireTime(LocalDateTime.now().minusSeconds(1));
        assertEquals(40301, assertThrows(BusinessException.class,
                () -> service.acquireEncryptedToken(acquire, context(), user, "DEVICE-A")).getCode());

        user.setExpireTime(LocalDateTime.now().plusDays(1));
        user.setRemainingCalls(0);
        assertEquals(40302, assertThrows(BusinessException.class,
                () -> service.acquireEncryptedToken(acquire, context(), user, "DEVICE-A")).getCode());
    }

    @Test
    @DisplayName("成功上报只扣一次并记录成功流水")
    void successfulReportDeductsAndWritesLog() {
        ReportResultDTO report = report("SUCCESS");
        when(dispatchLogMapper.selectCount(any())).thenReturn(0L);
        when(resourceLeaseService.consume(1L, report.getLeaseTraceId(), 1L))
                .thenReturn(lease("GOODS_COLLECT"));
        when(userMapper.update(any(), any())).thenReturn(1);

        service.reportAndDeductQuota(report, context(), user);

        ArgumentCaptor<PdkDispatchLog> log = ArgumentCaptor.forClass(PdkDispatchLog.class);
        verify(dispatchLogMapper).insert(log.capture());
        assertEquals("SUCCESS", log.getValue().getExecStatus());
        assertEquals(1, log.getValue().getDeductCount());
        verify(tokenPoolMapper, times(2)).update(any(), any());
    }

    @Test
    @DisplayName("网络失败免责不扣次数并释放资源")
    void networkFailureDoesNotDeduct() {
        ReportResultDTO report = report("FAIL_NETWORK");
        when(dispatchLogMapper.selectCount(any())).thenReturn(0L);
        when(resourceLeaseService.consume(1L, report.getLeaseTraceId(), 1L))
                .thenReturn(lease("DETAIL_QUERY"));

        service.reportAndDeductQuota(report, context(), user);

        verify(userMapper, never()).update(any(), any());
        ArgumentCaptor<PdkDispatchLog> log = ArgumentCaptor.forClass(PdkDispatchLog.class);
        verify(dispatchLogMapper).insert(log.capture());
        assertEquals("NET_TIMEOUT", log.getValue().getExecStatus());
        assertEquals(0, log.getValue().getDeductCount());
        verify(tokenPoolMapper).update(any(), any());
    }

    @Test
    @DisplayName("底层账号封禁免责并拉黑资源")
    void bannedAccountIsBlacklistedWithoutDeduction() {
        ReportResultDTO report = report("FAIL_ACCOUNT_BANNED");
        when(dispatchLogMapper.selectCount(any())).thenReturn(0L);
        when(resourceLeaseService.consume(1L, report.getLeaseTraceId(), 1L))
                .thenReturn(lease("ORDER_PULL"));

        service.reportAndDeductQuota(report, context(), user);

        verify(userMapper, never()).update(any(), any());
        verify(tokenPoolMapper).markTokenFaultStatus(8L, "FAULT_BLACK");
    }

    @Test
    @DisplayName("重复上报命中流水后直接幂等返回")
    void duplicateReportIsIdempotent() {
        ReportResultDTO report = report("SUCCESS");
        when(dispatchLogMapper.selectCount(any())).thenReturn(1L);

        service.reportAndDeductQuota(report, context(), user);

        verify(resourceLeaseService, never()).consume(anyLong(), anyString(), anyLong());
        verify(userMapper, never()).update(any(), any());
        verify(dispatchLogMapper, never()).insert(any(PdkDispatchLog.class));
    }

    @Test
    @DisplayName("不存在的租约明确返回过期错误")
    void missingLeaseIsRejected() {
        ReportResultDTO report = report("SUCCESS");
        when(dispatchLogMapper.selectCount(any())).thenReturn(0L);
        when(resourceLeaseService.consume(1L, report.getLeaseTraceId(), 1L)).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.reportAndDeductQuota(report, context(), user));
        assertEquals(41001, error.getCode());
    }

    private ReportResultDTO report(String status) {
        ReportResultDTO dto = new ReportResultDTO();
        dto.setLeaseTraceId("TRACE-UNIT-1");
        dto.setStatus(status);
        dto.setResponseDurationMs(88L);
        return dto;
    }

    private BusinessContext context() {
        return new BusinessContext(1, 1, "PDD", "拼多多", "desc", "SELF_SERVICE",
                true, 24, 1, 20, false);
    }

    private ResourceLeaseService.LeaseInfo lease(String action) {
        return new ResourceLeaseService.LeaseInfo(1L, 1L, 8L, user.getPhone(), "slot-8",
                action, null, 1);
    }
}
