package com.pdk.service;

import cn.dev33.satoken.stp.StpLogic;
import com.pdk.business.zhibo.live.service.LiveStreamSessionService;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.ClientLoginDTO;
import com.pdk.domain.dto.RenewDeviceLicenseDTO;
import com.pdk.domain.entity.*;
import com.pdk.mapper.*;
import com.pdk.platform.business.BusinessContext;
import com.pdk.security.AdminPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeviceLicenseServiceTest {
    private UserDeviceMapper deviceMapper;
    private DeviceLicenseMapper licenseMapper;
    private LicenseRenewalMapper renewalMapper;
    private CardKeyMapper cardMapper;
    private PackagePlanMapper packageMapper;
    private LiveStreamSessionService liveStreamService;
    private StpLogic stpLogic;
    private LicenseExportStubMapper stubMapper;
    private DeviceLicenseService service;

    @BeforeEach
    void setUp() {
        deviceMapper = mock(UserDeviceMapper.class);
        licenseMapper = mock(DeviceLicenseMapper.class);
        renewalMapper = mock(LicenseRenewalMapper.class);
        cardMapper = mock(CardKeyMapper.class);
        packageMapper = mock(PackagePlanMapper.class);
        liveStreamService = mock(LiveStreamSessionService.class);
        stpLogic = mock(StpLogic.class);
        stubMapper = mock(LicenseExportStubMapper.class);
        service = new DeviceLicenseService(deviceMapper, licenseMapper, renewalMapper,
                cardMapper, packageMapper, mock(UserMapper.class), mock(FinancialIncomeMapper.class),
                liveStreamService, stpLogic, stubMapper);
    }

    @Test
    void knownDeviceUsesItsOwnLicenseWithoutCard() {
        User user = user();
        UserDevice device = device(51L, "PC-01");
        DeviceLicense license = activeLicense(101L, 51L);
        when(deviceMapper.selectOne(any())).thenReturn(device);
        when(licenseMapper.selectOne(any())).thenReturn(license);

        ClientLicenseContext result = service.authenticateAndBind(business(), user, login("PC-01", null));

        assertEquals("license:101", result.loginId());
        verify(cardMapper, never()).selectOneForUpdate(any(), any());
    }

    @Test
    void eleventhDeviceWithoutNewCardIsRejected() {
        when(deviceMapper.selectOne(any())).thenReturn(null);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.authenticateAndBind(business(), user(), login("PC-11", null)));
        assertEquals(40380, error.getCode());
    }

    @Test
    void cardAssignedToAnotherPhoneIsRejected() {
        when(deviceMapper.selectOne(any())).thenReturn(null);
        CardKey card = card(300L, 999L, "ASSIGNED");
        when(cardMapper.selectOneForUpdate(3L, "CARD-OTHER")).thenReturn(card);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.authenticateAndBind(business(), user(), login("PC-11", "CARD-OTHER")));
        assertEquals(40382, error.getCode());
    }

    @Test
    void cardAlreadyBoundToAnotherDeviceIsRejected() {
        when(deviceMapper.selectOne(any())).thenReturn(null);
        CardKey card = card(300L, 10L, "ACTIVATED");
        DeviceLicense license = activeLicense(101L, 88L);
        when(cardMapper.selectOneForUpdate(3L, "CARD-USED")).thenReturn(card);
        when(licenseMapper.selectByCardForUpdate(300L)).thenReturn(license);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.authenticateAndBind(business(), user(), login("PC-11", "CARD-USED")));
        assertEquals(40383, error.getCode());
    }

    @Test
    void firstBindingStartsIndependentExpiryAndPerSeatCalls() {
        when(deviceMapper.selectOne(any())).thenReturn(null);
        CardKey card = card(300L, 10L, "ASSIGNED");
        DeviceLicense license = new DeviceLicense();
        license.setId(101L); license.setBizId(3L); license.setUserId(10L); license.setCardKeyId(300L);
        license.setPackageId(7L); license.setStatus("UNBOUND"); license.setVersion(0);
        PackagePlan plan = new PackagePlan();
        plan.setId(7); plan.setBizId(3L); plan.setName("直播月卡"); plan.setStatus("ACTIVE");
        plan.setDurationHours(720); plan.setCallsPerAccount(25); plan.setAccountCount(10);
        plan.setListPrice(BigDecimal.TEN); plan.setSalePrice(BigDecimal.TEN);
        when(cardMapper.selectOneForUpdate(3L, "CARD-NEW")).thenReturn(card);
        when(licenseMapper.selectByCardForUpdate(300L)).thenReturn(license);
        when(packageMapper.selectById(7)).thenReturn(plan);
        doAnswer(invocation -> { ((UserDevice) invocation.getArgument(0)).setId(51L); return 1; })
                .when(deviceMapper).insert(any(UserDevice.class));

        LocalDateTime before = LocalDateTime.now();
        ClientLicenseContext result = service.authenticateAndBind(business(), user(), login("PC-01", "CARD-NEW"));

        assertEquals("ACTIVE", result.license().getStatus());
        assertEquals(25, result.license().getRemainingCalls());
        assertEquals(51L, result.license().getUserDeviceId());
        assertTrue(result.license().getExpireAt().isAfter(before.plusHours(719)));
        assertEquals("ACTIVATED", card.getStatus());
    }

    @Test
    void expiryJobRechecksRenewedExpiryUnderLock() {
        DeviceLicense renewed = activeLicense(101L, 51L);
        renewed.setExpireAt(LocalDateTime.now().plusHours(2));
        when(licenseMapper.selectByIdForUpdate(101L)).thenReturn(renewed);

        assertFalse(service.expireIfDue(101L));
        verify(liveStreamService, never()).revokeLicenseSessions(anyLong(), anyLong(), anyString());
        verify(stpLogic, never()).kickout(any());
    }

    @Test
    void dueLicenseIsExpiredAndItsOwnSessionIsKicked() {
        DeviceLicense expired = activeLicense(101L, 51L);
        expired.setExpireAt(LocalDateTime.now().minusSeconds(1));
        when(licenseMapper.selectByIdForUpdate(101L)).thenReturn(expired);

        assertTrue(service.expireIfDue(101L));
        assertEquals("EXPIRED", expired.getStatus());
        verify(liveStreamService).revokeLicenseSessions(3L, 101L, "LICENSE_EXPIRED");
        verify(stpLogic).kickout("license:101");
    }

    @Test
    void renewalOrderCannotBeReusedAcrossLicenses() {
        DeviceLicense license = activeLicense(101L, 51L);
        CardKey card = card(300L, 10L, "ACTIVATED");
        card.setGeneratedByAdmin("superadmin");
        LicenseRenewal existing = new LicenseRenewal();
        existing.setLicenseId(202L);
        existing.setRenewalOrderNo("RENEW-001");
        when(licenseMapper.selectByIdForUpdate(101L)).thenReturn(license);
        when(cardMapper.selectById(300L)).thenReturn(card);
        when(renewalMapper.selectOne(any())).thenReturn(existing);
        RenewDeviceLicenseDTO dto = new RenewDeviceLicenseDTO();
        dto.setPackageId(7); dto.setRenewalOrderNo("RENEW-001");

        BusinessException error = assertThrows(BusinessException.class, () -> service.renew(101L, dto,
                new AdminPrincipal(1L, "superadmin", "超级管理员", "SUPER_ADMIN", "SYSTEM")));

        assertEquals(40982, error.getCode());
        verify(packageMapper, never()).selectById(any());
    }

    @Test
    void revokedLicenseCannotBeRestored() {
        DeviceLicense revoked = activeLicense(101L, 51L);
        revoked.setStatus("REVOKED");
        when(licenseMapper.selectByIdForUpdate(101L)).thenReturn(revoked);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.setStatus(101L, "ACTIVE", "尝试恢复"));

        assertEquals(40384, error.getCode());
        verify(licenseMapper, never()).updateById(any(DeviceLicense.class));
    }

    private static BusinessContext business() {
        return new BusinessContext(3, 3, "ZHIBO_LIVE", "直播矩阵", "", "ADMIN_ONLY",
                "DEVICE_LICENSE", false, 0, 0, 0, true);
    }
    private static User user() {
        User user = new User(); user.setId(10L); user.setBizId(3L); user.setPhone("13454118763"); user.setStatus("ACTIVE");
        return user;
    }
    private static ClientLoginDTO login(String deviceId, String cardKey) {
        ClientLoginDTO dto = new ClientLoginDTO(); dto.setAppId(3L); dto.setPhone("13454118763");
        dto.setPassword("Password123"); dto.setDeviceId(deviceId); dto.setCardKey(cardKey); dto.setDeviceName(deviceId);
        return dto;
    }
    private static UserDevice device(long id, String value) {
        UserDevice device = new UserDevice(); device.setId(id); device.setBizId(3L); device.setUserId(10L);
        device.setDeviceId(value); device.setDeviceIdHash(DeviceLicenseService.sha256(value)); device.setStatus("ACTIVE");
        return device;
    }
    private static DeviceLicense activeLicense(long id, long deviceId) {
        DeviceLicense license = new DeviceLicense(); license.setId(id); license.setBizId(3L); license.setUserId(10L);
        license.setCardKeyId(300L); license.setUserDeviceId(deviceId); license.setPackageId(7L);
        license.setStatus("ACTIVE"); license.setExpireAt(LocalDateTime.now().plusDays(1)); license.setRemainingCalls(20); license.setVersion(0);
        return license;
    }
    private static CardKey card(long id, long userId, String status) {
        CardKey card = new CardKey(); card.setId(id); card.setBizId(3L); card.setAssignedUserId(userId);
        card.setAssignedPhone("13454118763"); card.setStatus(status); card.setPackageId(7); card.setCardKey("CARD");
        return card;
    }
}
