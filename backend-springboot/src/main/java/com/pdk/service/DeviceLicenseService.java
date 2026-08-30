package com.pdk.service;

import cn.dev33.satoken.stp.StpLogic;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdk.business.zhibo.live.service.LiveStreamSessionService;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.BatchAssignLicenseDTO;
import com.pdk.domain.dto.ClientFingerprintDTO;
import com.pdk.domain.dto.ClientLoginDTO;
import com.pdk.domain.dto.RenewDeviceLicenseDTO;
import com.pdk.domain.entity.*;
import com.pdk.domain.vo.DeviceLicenseVO;
import com.pdk.domain.vo.LicenseExportResult;
import com.pdk.mapper.*;
import com.pdk.platform.business.BusinessContext;
import com.pdk.security.AdminPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeviceLicenseService {
    private final UserDeviceMapper deviceMapper;
    private final DeviceLicenseMapper licenseMapper;
    private final LicenseRenewalMapper renewalMapper;
    private final CardKeyMapper cardMapper;
    private final PackagePlanMapper packageMapper;
    private final UserMapper userMapper;
    private final FinancialIncomeMapper incomeMapper;
    private final LiveStreamSessionService liveStreamService;
    @Qualifier("clientStpLogic") private final StpLogic clientStpLogic;
    private final LicenseExportStubMapper stubMapper;
    private final DeviceFingerprintMapper fpMapper;
    @Value("${pdk.fingerprint.salt:change-me-fingerprint-salt-please-rotate}") private String fpSalt;
    @Value("${pdk.fingerprint.version:1}") private String fpVersion;

    /**
     * 设备许可证按授权到期时间计费，次数不再作为售卖口径。
     * 这里不改成「无限」的语义开关、也不动推流扣减逻辑，而是在分配/激活时把剩余次数直接置为
     * Integer.MAX_VALUE —— MediaMtxEventService 照常 remaining_calls - 1，只是永远扣不完，
     * 从而零改动地兼容所有既有的次数校验与扣减代码。
     */
    public static final int UNLIMITED_CALLS = Integer.MAX_VALUE;

    private static boolean unlimited(int calls) { return calls >= UNLIMITED_CALLS; }

    @Transactional(rollbackFor = Exception.class)
    public ClientLicenseContext authenticateAndBind(BusinessContext business, User user, ClientLoginDTO dto) {
        if (!business.usesDeviceLicense()) throw new IllegalArgumentException("当前业务不是设备许可证模式");
        String hash = sha256(dto.getDeviceId());
        UserDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getBizId, business.bizId()).eq(UserDevice::getUserId, user.getId())
                .eq(UserDevice::getDeviceIdHash, hash).last("LIMIT 1"));
        boolean firstBind = device == null;
        // 设备指纹克隆检测（仅客户端上报指纹时；老客户端不上报则跳过，向后兼容）。
        // 设备已存在（重登/激活）立即校验；首次绑定的情况延后到设备落库后再校验。
        if (device != null && dto.getFingerprint() != null) {
            checkAndStoreFingerprint(business, user, device, dto.getFingerprint());
        }
        DeviceLicense existing = device == null ? null : licenseMapper.selectOne(new LambdaQueryWrapper<DeviceLicense>()
                .eq(DeviceLicense::getBizId, business.bizId()).eq(DeviceLicense::getUserId, user.getId())
                .eq(DeviceLicense::getUserDeviceId, device.getId())
                .in(DeviceLicense::getStatus, "ACTIVE", "EXPIRED", "SUSPENDED", "REVOKED").last("LIMIT 1"));
        if (existing != null) {
            if ("SUSPENDED".equals(existing.getStatus()) || "REVOKED".equals(existing.getStatus())) {
                throw new BusinessException(40384, "当前设备许可证已暂停或作废");
            }
            touch(device, dto);
            refreshExpired(existing);
            return new ClientLicenseContext(existing, device);
        }
        if (dto.getCardKey() == null || dto.getCardKey().isBlank()) {
            throw new BusinessException(40380, "当前电脑尚未绑定许可证，请输入分配给您的卡密");
        }

        CardKey card = cardMapper.selectOneForUpdate(business.bizId(), dto.getCardKey().trim());
        if (card == null || "VOID".equals(card.getStatus())) {
            throw new BusinessException(40382, "卡密不存在、已作废或不属于当前业务");
        }
        if (card.getAssignedUserId() == null || !card.getAssignedUserId().equals(user.getId())) {
            throw new BusinessException(40382, "卡密未分配给当前手机号");
        }
        DeviceLicense license = licenseMapper.selectByCardForUpdate(card.getId());
        if (license == null) throw new BusinessException(40382, "卡密尚未生成设备许可证，请联系管理员");
        if ("REVOKED".equals(license.getStatus())) throw new BusinessException(40384, "许可证已作废");
        if (license.getUserDeviceId() != null && (device == null || !license.getUserDeviceId().equals(device.getId()))) {
            throw new BusinessException(40383, "该卡密已绑定其他设备，请先在原设备或管理后台解绑");
        }
        if (device != null) {
            long occupied = licenseMapper.selectCount(new LambdaQueryWrapper<DeviceLicense>()
                    .eq(DeviceLicense::getBizId, business.bizId()).eq(DeviceLicense::getUserDeviceId, device.getId())
                    .in(DeviceLicense::getStatus, "ACTIVE", "SUSPENDED"));
            if (occupied > 0) throw new BusinessException(40980, "当前设备已绑定另一张卡密");
        } else {
            device = new UserDevice();
            device.setBizId(business.bizId()); device.setUserId(user.getId());
            device.setDeviceId(dto.getDeviceId()); device.setDeviceIdHash(hash);
            device.setFirstBoundAt(LocalDateTime.now()); device.setCreatedAt(LocalDateTime.now());
        }
        device.setStatus("ACTIVE"); device.setUnboundAt(null);
        device.setDeviceName(dto.getDeviceName()); device.setPlatform(dto.getPlatform());
        device.setClientVersion(dto.getClientVersion()); device.setLastLoginAt(LocalDateTime.now());
        device.setLastSeenAt(LocalDateTime.now()); device.setUpdatedAt(LocalDateTime.now());
        try {
            if (device.getId() == null) deviceMapper.insert(device); else deviceMapper.updateById(device);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(40981, "设备并发绑定冲突，请刷新后重试");
        }

        if (firstBind && dto.getFingerprint() != null) {
            checkAndStoreFingerprint(business, user, device, dto.getFingerprint());
        }

        LocalDateTime now = LocalDateTime.now();
        if (license.getActivatedAt() == null) {
            PackagePlan plan = requirePlan(license.getPackageId().intValue(), business.bizId());
            license.setActivatedAt(now); license.setEffectiveAt(now);
            if (license.getExpireAt() == null) license.setExpireAt(now.plusHours(plan.getDurationHours()));
            if (value(license.getTotalCalls()) <= 0) {
                // 按到期时间计费，次数直接给到上限，避免老数据激活后仍受套餐次数限制
                license.setRemainingCalls(UNLIMITED_CALLS); license.setTotalCalls(UNLIMITED_CALLS);
            }
        }
        license.setUserDeviceId(device.getId());
        license.setStatus(license.getExpireAt() != null && !license.getExpireAt().isAfter(now) ? "EXPIRED" : "ACTIVE");
        license.setVersion(value(license.getVersion()) + 1);
        try {
            licenseMapper.updateById(license);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(40981, "卡密并发绑定冲突，请刷新后重试");
        }
        card.setStatus("ACTIVATED"); card.setActivatedByUserId(user.getId()); card.setActivatedByPhone(user.getPhone());
        card.setActivatedDeviceId(dto.getDeviceId());
        if (card.getActivatedAt() == null) card.setActivatedAt(now);
        cardMapper.updateById(card);
        return new ClientLicenseContext(license, device);
    }

    public ClientLicenseContext requireSubject(Object loginId, BusinessContext business, String deviceId,
                                                String requestFpHash, boolean requireActive) {
        String subject = String.valueOf(loginId);
        if (!subject.startsWith("license:")) throw new BusinessException(40106, "当前业务要求设备许可证登录会话");
        Long id;
        try { id = Long.valueOf(subject.substring("license:".length())); }
        catch (NumberFormatException e) { throw new BusinessException(40100, "无效的许可证登录会话"); }
        DeviceLicense license = licenseMapper.selectById(id);
        if (license == null || !business.bizIdEquals(license.getBizId())) {
            throw new BusinessException(40106, "许可证登录会话不属于当前业务");
        }
        UserDevice device = license.getUserDeviceId() == null ? null : deviceMapper.selectById(license.getUserDeviceId());
        if (device == null || !"ACTIVE".equals(device.getStatus()) || !device.getDeviceId().equals(deviceId)) {
            throw new BusinessException(40103, "许可证绑定设备已变化，本设备会话失效");
        }
        // 不做心跳的克隆/换机检测：每次受保护请求比对指纹哈希（仅当客户端上报且库中有指纹时）。
        if (requestFpHash != null && device.getId() != null) {
            DeviceFingerprint fpRow = fpMapper.selectOne(new LambdaQueryWrapper<DeviceFingerprint>()
                    .eq(DeviceFingerprint::getUserDeviceId, device.getId()));
            if (fpRow != null && fpRow.getFpHash() != null) {
                if ("CLONE_CONFIRMED".equals(fpRow.getFpStatus())) {
                    throw new BusinessException(40386, "设备指纹异常，许可证已被标记为克隆，请联系管理员");
                }
                if (!requestFpHash.equals(fpRow.getFpHash())) {
                    throw new BusinessException(40103, "许可证绑定设备已变化，本设备会话失效");
                }
            }
        }
        refreshExpired(license);
        if (requireActive) requireActive(license, false);
        device.setLastSeenAt(LocalDateTime.now()); deviceMapper.updateById(device);
        return new ClientLicenseContext(license, device);
    }

    public void requireActive(DeviceLicense license, boolean requireCalls) {
        if ("EXPIRED".equals(license.getStatus()) || license.getExpireAt() == null
                || !license.getExpireAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(40381, "当前设备许可证已到期");
        }
        if (!"ACTIVE".equals(license.getStatus())) throw new BusinessException(40384, "许可证已暂停或作废");
        if (requireCalls && value(license.getRemainingCalls()) <= 0) {
            throw new BusinessException(40374, "当前设备许可证可用次数不足");
        }
    }

    public DeviceLicenseVO view(DeviceLicense license) {
        UserDevice device = license.getUserDeviceId() == null ? null : deviceMapper.selectById(license.getUserDeviceId());
        return DeviceLicenseVO.from(license, device, cardMapper.selectById(license.getCardKeyId()));
    }

    public List<DeviceLicenseVO> listByUser(long bizId, long userId) {
        return licenseMapper.selectList(new LambdaQueryWrapper<DeviceLicense>()
                .eq(DeviceLicense::getBizId, bizId).eq(DeviceLicense::getUserId, userId)
                .orderByDesc(DeviceLicense::getId)).stream().map(this::view).toList();
    }

    public List<DeviceLicenseVO> listByUserScoped(long bizId, long userId, AdminPrincipal operator) {
        return licenseMapper.selectList(new LambdaQueryWrapper<DeviceLicense>()
                        .eq(DeviceLicense::getBizId, bizId).eq(DeviceLicense::getUserId, userId)
                        .orderByDesc(DeviceLicense::getId)).stream()
                .filter(value -> operator.isSuperAdmin() || ownedBy(value, operator))
                .map(this::view).toList();
    }

    public DeviceLicense requireAdminAccess(long licenseId, AdminPrincipal operator) {
        DeviceLicense license = licenseMapper.selectById(licenseId);
        if (license == null) throw new BusinessException(40480, "设备许可证不存在");
        CardKey card = cardMapper.selectById(license.getCardKeyId());
        if (card == null) throw new BusinessException(40403, "许可证关联卡密不存在");
        assertOwner(card, operator);
        return license;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<String> batchAssign(BusinessContext business, User user, BatchAssignLicenseDTO dto,
                                    AdminPrincipal operator) {
        if (!business.usesDeviceLicense()) throw new BusinessException(40058, "当前业务不是设备许可证授权模式");
        PackagePlan plan = requirePlan(dto.getPackageId(), business.bizId());
        if (!operator.isSuperAdmin() && !business.bizIdEquals(operator.bizId())) throw new BusinessException(40311, "不能跨业务分配许可证");
        if (!operator.isSuperAdmin() && plan.getOwnerUserId() != null && !plan.getOwnerUserId().equals(operator.id())) {
            throw new BusinessException(40310, "不能使用其他代理创建的套餐");
        }
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < dto.getCount(); i++) {
            CardKey card = new CardKey();
            card.setBizId(business.bizId()); card.setCardKey(randomCard()); card.setPackageId(plan.getId());
            card.setStatus("ASSIGNED"); card.setGeneratedByAdmin(operator.username()); card.setAgentId(operator.id());
            card.setAssignedUserId(user.getId()); card.setAssignedPhone(user.getPhone()); card.setAssignedAt(LocalDateTime.now());
            cardMapper.insert(card);

            DeviceLicense license = new DeviceLicense();
            license.setBizId(business.bizId()); license.setUserId(user.getId()); license.setCardKeyId(card.getId());
            license.setPackageId(plan.getId().longValue()); license.setPackageNameSnapshot(plan.getName());
            license.setStatus("UNBOUND"); license.setVersion(0);
            license.setRemainingCalls(UNLIMITED_CALLS); license.setTotalCalls(UNLIMITED_CALLS);
            license.setCreatedAt(LocalDateTime.now()); license.setUpdatedAt(LocalDateTime.now());
            licenseMapper.insert(license);
            createIncome(card, user, plan, operator, "NORMAL_SALE", null, dto.getRemark());
            keys.add(card.getCardKey());
        }
        return keys;
    }

    @Transactional(rollbackFor = Exception.class)
    public LicenseRenewal renew(long licenseId, RenewDeviceLicenseDTO dto, AdminPrincipal operator) {
        DeviceLicense license = licenseMapper.selectByIdForUpdate(licenseId);
        if (license == null) throw new BusinessException(40480, "设备许可证不存在");
        CardKey card = cardMapper.selectById(license.getCardKeyId());
        if (card == null) throw new BusinessException(40403, "许可证关联卡密不存在");
        assertOwner(card, operator);
        if (dto.getRenewalOrderNo() != null && !dto.getRenewalOrderNo().isBlank()) {
            LicenseRenewal existing = renewalMapper.selectOne(new LambdaQueryWrapper<LicenseRenewal>()
                    .eq(LicenseRenewal::getRenewalOrderNo, dto.getRenewalOrderNo()).last("LIMIT 1"));
            if (existing != null) {
                if (!Long.valueOf(licenseId).equals(existing.getLicenseId())) {
                    throw new BusinessException(40982, "renewalOrderNo 已被其他许可证使用，请生成新的续费请求号");
                }
                return existing;
            }
        }
        if ("REVOKED".equals(license.getStatus())) throw new BusinessException(40384, "已作废许可证不能续费");
        PackagePlan plan = requirePlan(dto.getPackageId(), license.getBizId());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime before = license.getExpireAt();
        LocalDateTime base = before != null && before.isAfter(now) ? before : now;
        LocalDateTime after = base.plusHours(plan.getDurationHours());
        // 次数已是「按到期时间计费」的无限值，续费只延长时间。继续累加会整数溢出成负数，
        // 反而让 remaining_calls <= 0 的校验把许可证锁死，必须跳过；此时续费记录里的 addedCalls 记为 0。
        boolean counted = !unlimited(value(license.getTotalCalls()));
        int addedCalls = counted ? plan.getCallsPerAccount() : 0;
        license.setPackageId(plan.getId().longValue()); license.setPackageNameSnapshot(plan.getName());
        license.setExpireAt(after);
        if (counted) {
            license.setRemainingCalls(value(license.getRemainingCalls()) + addedCalls);
            license.setTotalCalls(value(license.getTotalCalls()) + addedCalls);
        }
        if (!"SUSPENDED".equals(license.getStatus())) license.setStatus(license.getUserDeviceId() == null ? "UNBOUND" : "ACTIVE");
        license.setVersion(value(license.getVersion()) + 1); licenseMapper.updateById(license);
        card.setPackageId(plan.getId());
        cardMapper.updateById(card);

        LicenseRenewal renewal = new LicenseRenewal();
        renewal.setBizId(license.getBizId()); renewal.setLicenseId(license.getId()); renewal.setCardKeyId(card.getId());
        renewal.setUserId(license.getUserId()); renewal.setRenewalOrderNo(dto.getRenewalOrderNo() == null || dto.getRenewalOrderNo().isBlank()
                ? "LRN-" + UUID.randomUUID().toString().replace("-", "") : dto.getRenewalOrderNo().trim());
        renewal.setBeforeExpireAt(before); renewal.setDurationHours(plan.getDurationHours()); renewal.setAfterExpireAt(after);
        renewal.setAddedCalls(addedCalls); renewal.setAmount(plan.getSalePrice()); renewal.setPaymentChannel("OFFLINE");
        renewal.setOperatorId(operator.username()); renewal.setRemark(dto.getRemark()); renewal.setCreatedAt(now);
        renewalMapper.insert(renewal);
        User user = userMapper.selectById(license.getUserId());
        createIncome(card, user, plan, operator, "RENEWAL", dto.getPaymentTxnNo(), dto.getRemark());
        return renewal;
    }

    /**
     * 导出某用户在某业务下的全部设备许可证卡密（明文），并留存服务器存根。
     * 导出本就是管理员发给客户的动作，故仅需 CARD_VIEW；存根 + 审计双重留痕便于追溯。
     */
    public LicenseExportResult exportCards(long bizId, long userId, String bizName, AdminPrincipal operator) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(40402, "用户不存在");
        List<DeviceLicense> licenses = licenseMapper.selectList(new LambdaQueryWrapper<DeviceLicense>()
                .eq(DeviceLicense::getBizId, bizId).eq(DeviceLicense::getUserId, userId)
                .orderByDesc(DeviceLicense::getId));
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF'); // Excel 打开 UTF-8 CSV 需要 BOM
        sb.append(csvLine("手机号", "卡密"));
        int count = 0;
        for (DeviceLicense lic : licenses) {
            CardKey card = cardMapper.selectById(lic.getCardKeyId());
            String cardKey = card == null ? "" : card.getCardKey();
            sb.append(csvLine(user.getPhone(), cardKey));
            count++;
        }
        String csv = sb.toString();
        String safeName = bizName == null ? "" : bizName.replaceAll("[\\\\/:*?\"<>|]", "_");
        String fileName = String.format("%s_%s_%s.csv", user.getPhone(), safeName, LocalDateTime.now().format(CSV_TS));
        LicenseExportStub stub = new LicenseExportStub();
        stub.setBizId(bizId); stub.setUserId(userId); stub.setPhone(user.getPhone());
        stub.setOperator(operator.username()); stub.setFileName(fileName);
        stub.setRecordCount(count); stub.setContent(csv);
        stubMapper.insert(stub);
        LicenseExportResult result = new LicenseExportResult();
        result.setFileName(fileName); result.setCsv(csv); result.setRecordCount(count);
        return result;
    }

    /**
     * 删除某客户在某业务下的全部授权数据（硬删除）：设备许可证、对应卡密、绑定设备一并从数据库物理删除。
     * 删除后该客户在本业务下既无许可证也无卡密，DEVICE_LICENSE 模式登录依赖 authenticateAndBind，
     * 无证无卡将直接抛 40380/40382，无法登录、无法使用；操作不可逆。
     * 注意：不删除 User/UserCredential，仅清除授权数据，保留客户账号记录与审计可追溯。
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteUserBusiness(long bizId, long userId, String reason, AdminPrincipal operator) {
        // 1. 先踢掉该用户在本业务下的所有在线会话（基于 license 会话），确保即时失效
        kickUserLicenses(bizId, userId, "USER_BUSINESS_DELETED");
        // 2. 收集许可证关联的卡密
        List<DeviceLicense> licenses = licenseMapper.selectList(new LambdaQueryWrapper<DeviceLicense>()
                .eq(DeviceLicense::getBizId, bizId).eq(DeviceLicense::getUserId, userId));
        List<Long> cardKeyIds = licenses.stream().map(DeviceLicense::getCardKeyId)
                .filter(Objects::nonNull).collect(Collectors.toList());
        // 3. 删除设备许可证
        if (!licenses.isEmpty()) {
            licenseMapper.delete(new LambdaQueryWrapper<DeviceLicense>()
                    .eq(DeviceLicense::getBizId, bizId).eq(DeviceLicense::getUserId, userId));
        }
        // 4. 删除卡密：许可证关联的 + 该用户在本业务下已分配（含尚未生成许可证的预分配卡）
        if (!cardKeyIds.isEmpty()) {
            cardMapper.delete(new LambdaQueryWrapper<CardKey>().in(CardKey::getId, cardKeyIds));
        }
        cardMapper.delete(new LambdaQueryWrapper<CardKey>()
                .eq(CardKey::getBizId, bizId).eq(CardKey::getAssignedUserId, userId));
        // 5. 删除绑定设备记录（无外键约束，可直接删）
        deviceMapper.delete(new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getBizId, bizId).eq(UserDevice::getUserId, userId));
        return licenses.size();
    }

    @Transactional(rollbackFor = Exception.class)
    public void unbind(long licenseId, String reason) {
        DeviceLicense license = licenseMapper.selectByIdForUpdate(licenseId);
        if (license == null) throw new BusinessException(40480, "设备许可证不存在");
        liveStreamService.revokeLicenseSessions(license.getBizId(), license.getId(), reason);
        if (license.getUserDeviceId() != null) {
            UserDevice device = deviceMapper.selectById(license.getUserDeviceId());
            if (device != null) { device.setStatus("UNBOUND"); device.setUnboundAt(LocalDateTime.now()); deviceMapper.updateById(device); }
        }
        String nextStatus = license.getExpireAt() != null && !license.getExpireAt().isAfter(LocalDateTime.now()) ? "EXPIRED" : "UNBOUND";
        int nextVersion = value(license.getVersion()) + 1;
        // 解绑即结束当前绑定周期：清空激活/生效/到期时间，避免客户端重绑后仍沿用旧周期时间，
        // 也避免管理后台解绑后页面继续展示无效的旧激活信息。重绑时 activated_at 为 null 会自动重算。
        licenseMapper.update(null, new LambdaUpdateWrapper<DeviceLicense>()
                .eq(DeviceLicense::getId, licenseId)
                .set(DeviceLicense::getUserDeviceId, null)
                .set(DeviceLicense::getStatus, nextStatus)
                .set(DeviceLicense::getActivatedAt, null)
                .set(DeviceLicense::getEffectiveAt, null)
                .set(DeviceLicense::getExpireAt, null)
                .set(DeviceLicense::getVersion, nextVersion));
        cardMapper.update(null, new LambdaUpdateWrapper<CardKey>()
                .eq(CardKey::getId, license.getCardKeyId()).set(CardKey::getActivatedDeviceId, null));
        license.setUserDeviceId(null); license.setStatus(nextStatus); license.setVersion(nextVersion);
        clientStpLogic.kickout("license:" + licenseId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void setStatus(long licenseId, String status, String reason) {
        DeviceLicense license = licenseMapper.selectByIdForUpdate(licenseId);
        if (license == null) throw new BusinessException(40480, "设备许可证不存在");
        if ("REVOKED".equals(license.getStatus()) && !"REVOKED".equals(status)) {
            throw new BusinessException(40384, "已作废许可证不可恢复，请重新分配新卡密");
        }
        if ("REVOKED".equals(status)) {
            liveStreamService.revokeLicenseSessions(license.getBizId(), licenseId, "LICENSE_REVOKED");
            CardKey card = cardMapper.selectById(license.getCardKeyId());
            if (card != null) { card.setStatus("VOID"); cardMapper.updateById(card); }
        }
        if ("SUSPENDED".equals(status)) liveStreamService.revokeLicenseSessions(license.getBizId(), licenseId, "LICENSE_SUSPENDED");
        if ("ACTIVE".equals(status) && (license.getExpireAt() == null || !license.getExpireAt().isAfter(LocalDateTime.now()))) {
            throw new BusinessException(40381, "过期许可证不能直接恢复，请先续费");
        }
        license.setStatus(status); license.setVersion(value(license.getVersion()) + 1); licenseMapper.updateById(license);
        if (!"ACTIVE".equals(status)) clientStpLogic.kickout("license:" + licenseId);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean expireIfDue(long licenseId) {
        DeviceLicense license = licenseMapper.selectByIdForUpdate(licenseId);
        if (license == null || !"ACTIVE".equals(license.getStatus()) || license.getExpireAt() == null
                || license.getExpireAt().isAfter(LocalDateTime.now())) return false;
        liveStreamService.revokeLicenseSessions(license.getBizId(), licenseId, "LICENSE_EXPIRED");
        license.setStatus("EXPIRED"); license.setVersion(value(license.getVersion()) + 1);
        licenseMapper.updateById(license);
        clientStpLogic.kickout("license:" + licenseId);
        return true;
    }

    public List<LicenseRenewal> renewalHistory(long licenseId) {
        return renewalMapper.selectList(new LambdaQueryWrapper<LicenseRenewal>()
                .eq(LicenseRenewal::getLicenseId, licenseId).orderByDesc(LicenseRenewal::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public List<LicenseRenewal> batchRenew(List<Long> ids, RenewDeviceLicenseDTO dto, AdminPrincipal operator) {
        List<LicenseRenewal> values = new ArrayList<>();
        for (Long id : ids) {
            RenewDeviceLicenseDTO item = new RenewDeviceLicenseDTO();
            item.setPackageId(dto.getPackageId()); item.setPaymentTxnNo(dto.getPaymentTxnNo()); item.setRemark(dto.getRemark());
            item.setRenewalOrderNo(dto.getRenewalOrderNo() == null ? null : dto.getRenewalOrderNo() + "-" + id);
            values.add(renew(id, item, operator));
        }
        return values;
    }

    public void kickUserLicenses(long bizId, long userId, String reason) {
        List<DeviceLicense> values = licenseMapper.selectList(new LambdaQueryWrapper<DeviceLicense>()
                .eq(DeviceLicense::getBizId, bizId).eq(DeviceLicense::getUserId, userId)
                .isNotNull(DeviceLicense::getUserDeviceId));
        for (DeviceLicense license : values) {
            liveStreamService.revokeLicenseSessions(bizId, license.getId(), reason);
            clientStpLogic.kickout("license:" + license.getId());
        }
    }

    private void refreshExpired(DeviceLicense license) {
        if (("ACTIVE".equals(license.getStatus()) || "UNBOUND".equals(license.getStatus()))
                && license.getExpireAt() != null && !license.getExpireAt().isAfter(LocalDateTime.now())) {
            license.setStatus("EXPIRED"); license.setVersion(value(license.getVersion()) + 1); licenseMapper.updateById(license);
        }
    }

    private void touch(UserDevice device, ClientLoginDTO dto) {
        device.setStatus("ACTIVE"); device.setDeviceName(dto.getDeviceName()); device.setPlatform(dto.getPlatform());
        device.setClientVersion(dto.getClientVersion()); device.setLastLoginAt(LocalDateTime.now());
        device.setLastSeenAt(LocalDateTime.now()); deviceMapper.updateById(device);
    }

    private PackagePlan requirePlan(int packageId, long bizId) {
        PackagePlan plan = packageMapper.selectById(packageId);
        if (plan == null || !Long.valueOf(bizId).equals(plan.getBizId()) || !"ACTIVE".equals(plan.getStatus())) {
            throw new BusinessException(40020, "套餐不存在、业务不匹配或已停用");
        }
        return plan;
    }

    private void createIncome(CardKey card, User user, PackagePlan plan, AdminPrincipal operator,
                              String type, String paymentTxnNo, String remark) {
        FinancialIncome income = new FinancialIncome();
        income.setBizId(card.getBizId()); income.setUserId(user.getId());
        income.setIncomeOrderNo(("RENEWAL".equals(type) ? "REN-" : "LIC-") + UUID.randomUUID().toString().replace("-", ""));
        income.setCardKeyId(card.getId()); income.setCardKey(card.getCardKey()); income.setUserPhone(user.getPhone());
        income.setPackageId(plan.getId()); income.setPackageName(plan.getName()); income.setFaceValue(plan.getListPrice());
        income.setAmount(plan.getSalePrice()); income.setDiscountAmount(plan.getListPrice().subtract(plan.getSalePrice()).max(BigDecimal.ZERO));
        income.setOrderType(type); income.setPaymentChannel("OFFLINE"); income.setPaymentTxnNo(paymentTxnNo);
        income.setAuditAdmin(operator.username());
        income.setActivatedAt(LocalDateTime.now()); income.setAuditRemark(remark == null ? "设备许可证" + type : remark);
        incomeMapper.insert(income);
    }

    private void assertOwner(CardKey card, AdminPrincipal operator) {
        if (!operator.isSuperAdmin() && (!card.getBizId().equals(operator.bizId())
                || !operator.username().equals(card.getGeneratedByAdmin()))) {
            throw new BusinessException(40310, "只能操作本业务且由自己分配的许可证");
        }
    }

    private boolean ownedBy(DeviceLicense license, AdminPrincipal operator) {
        CardKey card = cardMapper.selectById(license.getCardKeyId());
        return card != null && operator.username().equals(card.getGeneratedByAdmin());
    }

    private static int value(Integer v) { return v == null ? 0 : v; }
    private static final DateTimeFormatter CSV_DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter CSV_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static String csvCell(String v) {
        if (v == null) v = "";
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
    private static String csvLine(String... cells) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) b.append(',');
            b.append(csvCell(cells[i]));
        }
        return b.append('\n').toString();
    }
    private static String randomCard() {
        String raw = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return "PDK-" + raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8);
    }
    private static final org.slf4j.Logger fpLog = org.slf4j.LoggerFactory.getLogger(DeviceLicenseService.class);
    // 归一化时视为“不可读/默认值”的硬件串，命中则不算有效组件（避免云/VM 返回空串导致所有人指纹相同）
    private static final java.util.List<String> FP_DEFAULTS = java.util.List.of("", "none", "null", "unknown",
            "default string", "to be filled by o.e.m.", "to be filled by oe.m.", "system serial number",
            "system serialnumber", "0", "0000000000000000", "00000000", "na", "n/a");
    private static final ObjectMapper FP_MAPPER = new ObjectMapper();

    /** 派生每租户盐：sha256(全局盐 + ":" + bizId)，下发给客户端用于计算指纹哈希，避免跨租户关联与预计算。 */
    public String fingerprintSalt(long bizId) {
        return sha256(fpSalt + ":" + bizId);
    }

    /** 当前设备已落库的指纹哈希（服务端对原始组件做 salted hash 得到），用于登录响应下发给客户端回传。 */
    public String currentFingerprintHash(BusinessContext business, User user, UserDevice device) {
        if (device == null || device.getId() == null) return null;
        DeviceFingerprint row = fpMapper.selectOne(new LambdaQueryWrapper<DeviceFingerprint>()
                .eq(DeviceFingerprint::getUserDeviceId, device.getId()));
        return row == null ? null : row.getFpHash();
    }

    private static final class FpEval {
        final String json; final String hash; final int confidence;
        FpEval(String json, String hash, int confidence) { this.json = json; this.hash = hash; this.confidence = confidence; }
    }

    private FpEval evaluateFingerprint(ClientFingerprintDTO fp, String tenantSalt) {
        if (fp == null) return new FpEval(null, null, 0);
        String mb = normalize(fp.getMotherboardSerial());
        String disk = normalize(fp.getDiskSerial());
        String cpu = normalize(fp.getCpuid());
        int confidence = 0;
        Map<String, String> comps = new LinkedHashMap<>();
        if (mb != null) { comps.put("mb", sha256(mb + "|" + tenantSalt)); confidence++; }
        if (disk != null) { comps.put("disk", sha256(disk + "|" + tenantSalt)); confidence++; }
        if (cpu != null) { comps.put("cpu", sha256(cpu + "|" + tenantSalt)); confidence++; }
        if (comps.isEmpty()) return new FpEval(null, null, 0);
        try {
            String json = FP_MAPPER.writeValueAsString(comps);
            return new FpEval(json, sha256(json + "|" + tenantSalt), confidence);
        } catch (Exception e) {
            fpLog.warn("设备指纹序列化失败，跳过克隆检测: {}", e.getMessage());
            return new FpEval(null, null, 0);
        }
    }

    private static String normalize(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        return FP_DEFAULTS.contains(t.toLowerCase()) ? null : t;
    }

    private double fingerprintSimilarity(String storedJson, String newJson) {
        if (storedJson == null || newJson == null) return 0.0;
        try {
            Map<String, String> a = FP_MAPPER.readValue(storedJson, new TypeReference<Map<String, String>>() {});
            Map<String, String> b = FP_MAPPER.readValue(newJson, new TypeReference<Map<String, String>>() {});
            if (a.isEmpty() && b.isEmpty()) return 1.0;
            int inter = 0;
            for (Map.Entry<String, String> e : a.entrySet()) {
                if (e.getValue() != null && e.getValue().equals(b.get(e.getKey()))) inter++;
            }
            int union = a.size() + b.size() - inter;
            return union == 0 ? 1.0 : (double) inter / union;
        } catch (Exception e) {
            return 0.0;
        }
    }

    /** 评估并落库设备指纹，同时执行克隆检测（跨设备碰撞 + 设备内剧变）。device 必须非 null。 */
    private void checkAndStoreFingerprint(BusinessContext business, User user, UserDevice device, ClientFingerprintDTO fp) {
        String tenantSalt = fingerprintSalt(business.bizId());
        FpEval ev = evaluateFingerprint(fp, tenantSalt);
        if (ev.hash == null) {
            // 退化指纹：不依赖指纹做克隆判定，仅确保有一行（confidence=0、fp_hash=null），避免 per-request 误判
            upsertFingerprint(business, user, device, null, null, 0, "OK");
            return;
        }
        // 跨设备指纹碰撞：同业务内相同硬件指纹被“不同设备令牌”声明 -> 强克隆信号。
        // 仅在 confidence>=2 时启用，规避单组件弱指纹偶发碰撞误杀合法用户（应对“指纹仍可能不唯一”的极端情况）。
        if (ev.confidence >= 2) {
            long collisions = fpMapper.selectCount(new LambdaQueryWrapper<DeviceFingerprint>()
                    .eq(DeviceFingerprint::getBizId, business.bizId())
                    .eq(DeviceFingerprint::getFpHash, ev.hash)
                    .ne(DeviceFingerprint::getUserDeviceId, device.getId()));
            if (collisions > 0) {
                fpLog.warn("设备指纹跨设备碰撞(疑似克隆): bizId={}, deviceIdHash={}, fpHash={}",
                        business.bizId(), device.getDeviceIdHash(), ev.hash);
                throw new BusinessException(40386, "设备指纹异常，疑似克隆环境，请联系管理员");
            }
        }
        // 设备内指纹剧变：同一令牌下硬件指纹大幅变化 -> 可能换机或克隆
        DeviceFingerprint existing = device.getId() == null ? null :
                fpMapper.selectOne(new LambdaQueryWrapper<DeviceFingerprint>().eq(DeviceFingerprint::getUserDeviceId, device.getId()));
        String status = "OK";
        if (existing != null && existing.getFpHash() != null && !existing.getFpHash().equals(ev.hash)) {
            double ratio = fingerprintSimilarity(existing.getFpJson(), ev.json);
            if (ratio < 0.5) {
                fpLog.warn("设备内指纹剧变(疑似克隆): bizId={}, deviceIdHash={}, ratio={}", business.bizId(), device.getDeviceIdHash(), ratio);
                throw new BusinessException(40386, "设备指纹异常，疑似克隆环境，请联系管理员");
            } else if (ratio < 0.85) {
                status = "CLONE_SUSPECT";
                fpLog.warn("设备内指纹小幅变化(可疑): bizId={}, deviceIdHash={}, ratio={}", business.bizId(), device.getDeviceIdHash(), ratio);
            }
        }
        upsertFingerprint(business, user, device, ev.json, ev.hash, ev.confidence, status);
    }

    private void upsertFingerprint(BusinessContext business, User user, UserDevice device, String json, String hash,
                                   int confidence, String status) {
        DeviceFingerprint row = device.getId() == null ? null :
                fpMapper.selectOne(new LambdaQueryWrapper<DeviceFingerprint>().eq(DeviceFingerprint::getUserDeviceId, device.getId()));
        if (row == null) {
            row = new DeviceFingerprint();
            row.setBizId(business.bizId()); row.setUserId(user.getId());
            row.setUserDeviceId(device.getId()); row.setDeviceIdHash(device.getDeviceIdHash());
            row.setCreatedAt(LocalDateTime.now());
        }
        row.setFpJson(json); row.setFpHash(hash); row.setFpVersion(fpVersion);
        row.setFpConfidence(confidence); row.setFpStatus(status); row.setUpdatedAt(LocalDateTime.now());
        if (row.getId() == null) fpMapper.insert(row); else fpMapper.updateById(row);
    }

    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
