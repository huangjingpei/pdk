package com.pdk.service;

import cn.dev33.satoken.stp.StpLogic;
import com.pdk.business.zhibo.live.service.LiveStreamSessionService;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.ClientFingerprintDTO;
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
    private DeviceFingerprintMapper fpMapper;
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
        fpMapper = mock(DeviceFingerprintMapper.class);
        service = new DeviceLicenseService(deviceMapper, licenseMapper, renewalMapper,
                cardMapper, packageMapper, mock(UserMapper.class), mock(FinancialIncomeMapper.class),
                liveStreamService, stpLogic, stubMapper, fpMapper);
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
    // ============ 设备指纹克隆检测单元测试 ============

    /** 跨设备指纹碰撞：同一硬件指纹被不同设备令牌声明 -> 抛 40386（模型“一台机器伪装成多台设备”）。 */
    @Test
    void cloneDetectedWhenSameHardwareBoundToDifferentDevice() {
        when(deviceMapper.selectOne(any())).thenReturn(null); // 新设备
        when(cardMapper.selectOneForUpdate(3L, "CARD-NEW")).thenReturn(card(300L, 10L, "ASSIGNED"));
        DeviceLicense license = new DeviceLicense();
        license.setId(101L); license.setBizId(3L); license.setUserId(10L); license.setCardKeyId(300L);
        license.setPackageId(7L); license.setStatus("UNBOUND"); license.setVersion(0);
        when(licenseMapper.selectByCardForUpdate(300L)).thenReturn(license);
        PackagePlan plan = new PackagePlan();
        plan.setId(7); plan.setBizId(3L); plan.setName("直播月卡"); plan.setStatus("ACTIVE");
        plan.setDurationHours(720); plan.setCallsPerAccount(25); plan.setAccountCount(10);
        plan.setListPrice(BigDecimal.TEN); plan.setSalePrice(BigDecimal.TEN);
        when(packageMapper.selectById(7)).thenReturn(plan);
        doAnswer(invocation -> { ((UserDevice) invocation.getArgument(0)).setId(51L); return 1; })
                .when(deviceMapper).insert(any(UserDevice.class));
        when(licenseMapper.updateById(any(DeviceLicense.class))).thenReturn(1);
        when(cardMapper.updateById(any(CardKey.class))).thenReturn(1);
        // 关键：与已落库的其他设备指纹相同（碰撞计数>0），触发 40386
        when(fpMapper.selectCount(any())).thenReturn(1L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.authenticateAndBind(business(), user(),
                        loginWithFp("PC-CLONE", "CARD-NEW", fp("MB-A", "DISK-A", "CPU-A"))));
        assertEquals(40386, error.getCode());
    }

    /** 不同硬件指纹（不同机器）-> 不触发克隆，正常激活绑定成功。 */
    @Test
    void noCloneWhenDistinctHardware() {
        when(deviceMapper.selectOne(any())).thenReturn(null);
        when(cardMapper.selectOneForUpdate(3L, "CARD-NEW")).thenReturn(card(300L, 10L, "ASSIGNED"));
        DeviceLicense license = new DeviceLicense();
        license.setId(101L); license.setBizId(3L); license.setUserId(10L); license.setCardKeyId(300L);
        license.setPackageId(7L); license.setStatus("UNBOUND"); license.setVersion(0);
        when(licenseMapper.selectByCardForUpdate(300L)).thenReturn(license);
        PackagePlan plan = new PackagePlan();
        plan.setId(7); plan.setBizId(3L); plan.setName("直播月卡"); plan.setStatus("ACTIVE");
        plan.setDurationHours(720); plan.setCallsPerAccount(25); plan.setAccountCount(10);
        plan.setListPrice(BigDecimal.TEN); plan.setSalePrice(BigDecimal.TEN);
        when(packageMapper.selectById(7)).thenReturn(plan);
        doAnswer(invocation -> { ((UserDevice) invocation.getArgument(0)).setId(51L); return 1; })
                .when(deviceMapper).insert(any(UserDevice.class));
        when(licenseMapper.updateById(any(DeviceLicense.class))).thenReturn(1);
        when(cardMapper.updateById(any(CardKey.class))).thenReturn(1);
        when(fpMapper.selectCount(any())).thenReturn(0L);   // 无碰撞
        when(fpMapper.selectOne(any())).thenReturn(null);   // 无已存指纹行
        when(fpMapper.insert(any(DeviceFingerprint.class))).thenReturn(1);

        ClientLicenseContext result = service.authenticateAndBind(business(), user(),
                loginWithFp("PC-REAL", "CARD-NEW", fp("MB-REAL", "DISK-REAL", "CPU-REAL")));
        assertEquals("license:101", result.loginId());
    }

    /** 退化指纹（全部组件为空/默认值）-> 不上报哈希，跳过克隆判定，正常激活。 */
    @Test
    void degradedFingerprintSkipsCloneCheck() {
        when(deviceMapper.selectOne(any())).thenReturn(null);
        when(cardMapper.selectOneForUpdate(3L, "CARD-NEW")).thenReturn(card(300L, 10L, "ASSIGNED"));
        DeviceLicense license = new DeviceLicense();
        license.setId(101L); license.setBizId(3L); license.setUserId(10L); license.setCardKeyId(300L);
        license.setPackageId(7L); license.setStatus("UNBOUND"); license.setVersion(0);
        when(licenseMapper.selectByCardForUpdate(300L)).thenReturn(license);
        PackagePlan plan = new PackagePlan();
        plan.setId(7); plan.setBizId(3L); plan.setName("直播月卡"); plan.setStatus("ACTIVE");
        plan.setDurationHours(720); plan.setCallsPerAccount(25); plan.setAccountCount(10);
        plan.setListPrice(BigDecimal.TEN); plan.setSalePrice(BigDecimal.TEN);
        when(packageMapper.selectById(7)).thenReturn(plan);
        doAnswer(invocation -> { ((UserDevice) invocation.getArgument(0)).setId(51L); return 1; })
                .when(deviceMapper).insert(any(UserDevice.class));
        when(licenseMapper.updateById(any(DeviceLicense.class))).thenReturn(1);
        when(cardMapper.updateById(any(CardKey.class))).thenReturn(1);
        when(fpMapper.selectOne(any())).thenReturn(null);
        when(fpMapper.insert(any(DeviceFingerprint.class))).thenReturn(1);
        // 不设置 selectCount：退化指纹（ev.hash=null）根本不会进入碰撞查询

        ClientLicenseContext result = service.authenticateAndBind(business(), user(),
                loginWithFp("PC-DEGRADED", "CARD-NEW", fp("", "", "")));
        assertEquals("license:101", result.loginId());
    }

    /** 低置信度指纹（仅 1 个可读组件，confidence<2）-> 即便存在碰撞记录也跳过克隆判定。 */
    @Test
    void lowConfidenceSkipsCollisionEvenIfCollisionExists() {
        when(deviceMapper.selectOne(any())).thenReturn(null);
        when(cardMapper.selectOneForUpdate(3L, "CARD-NEW")).thenReturn(card(300L, 10L, "ASSIGNED"));
        DeviceLicense license = new DeviceLicense();
        license.setId(101L); license.setBizId(3L); license.setUserId(10L); license.setCardKeyId(300L);
        license.setPackageId(7L); license.setStatus("UNBOUND"); license.setVersion(0);
        when(licenseMapper.selectByCardForUpdate(300L)).thenReturn(license);
        PackagePlan plan = new PackagePlan();
        plan.setId(7); plan.setBizId(3L); plan.setName("直播月卡"); plan.setStatus("ACTIVE");
        plan.setDurationHours(720); plan.setCallsPerAccount(25); plan.setAccountCount(10);
        plan.setListPrice(BigDecimal.TEN); plan.setSalePrice(BigDecimal.TEN);
        when(packageMapper.selectById(7)).thenReturn(plan);
        doAnswer(invocation -> { ((UserDevice) invocation.getArgument(0)).setId(51L); return 1; })
                .when(deviceMapper).insert(any(UserDevice.class));
        when(licenseMapper.updateById(any(DeviceLicense.class))).thenReturn(1);
        when(cardMapper.updateById(any(CardKey.class))).thenReturn(1);
        when(fpMapper.selectCount(any())).thenReturn(1L);   // 即便有碰撞记录也忽略（置信度不足）
        when(fpMapper.selectOne(any())).thenReturn(null);
        when(fpMapper.insert(any(DeviceFingerprint.class))).thenReturn(1);

        ClientLicenseContext result = service.authenticateAndBind(business(), user(),
                loginWithFp("PC-LOWCONF", "CARD-NEW", fp("MB-ONLY", "", "")));
        assertEquals("license:101", result.loginId());
    }

    /** 设备内指纹剧变（同一令牌下硬件大幅变化）-> 抛 40386（与跨设备碰撞同一错误码）。 */
    @Test
    void intraDeviceMutationTriggersClone() {
        UserDevice device = device(51L, "PC-EXISTING");
        when(deviceMapper.selectOne(any())).thenReturn(device);  // 已存在设备 -> 立即检测指纹
        DeviceFingerprint old = new DeviceFingerprint();
        old.setUserDeviceId(51L);
        old.setFpHash("OLD-HASH");
        old.setFpJson("{\"mb\":\"sha256(oldmb|salt)\",\"disk\":\"sha256(olddisk|salt)\"}");
        when(fpMapper.selectCount(any())).thenReturn(0L);        // 跨设备无碰撞
        when(fpMapper.selectOne(any())).thenReturn(old);        // 已存指纹（与本次差异极大）
        // 不触达 license/upsert，因为会在检测处抛 40386

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.authenticateAndBind(business(), user(),
                        loginWithFp("PC-EXISTING", null, fp("BRANDNEW-MB", "BRANDNEW-DISK", "BRANDNEW-CPU"))));
        assertEquals(40386, error.getCode());
    }

    private static ClientLoginDTO loginWithFp(String deviceId, String cardKey, ClientFingerprintDTO fp) {
        ClientLoginDTO dto = login(deviceId, cardKey);
        dto.setFingerprint(fp);
        return dto;
    }

    private static ClientFingerprintDTO fp(String mb, String disk, String cpu) {
        ClientFingerprintDTO f = new ClientFingerprintDTO();
        f.setMotherboardSerial(mb);
        f.setDiskSerial(disk);
        f.setCpuid(cpu);
        return f;
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
