package com.pdk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.ActivateCardDTO;
import com.pdk.domain.dto.CreateCardBatchDTO;
import com.pdk.domain.entity.*;
import com.pdk.domain.vo.ActivationResultVO;
import com.pdk.mapper.*;
import com.pdk.service.ICardKeyActivationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.pdk.platform.business.BusinessContext;
import com.pdk.security.AdminPrincipal;

@Slf4j
@Service
@RequiredArgsConstructor
public class CardKeyActivationServiceImpl implements ICardKeyActivationService {

    private final CardKeyMapper cardKeyMapper;
    private final FinancialIncomeMapper financialIncomeMapper;
    private final UserMapper userMapper;
    private final PackagePlanMapper packagePlanMapper;
    private final PdkAdminAuditLogMapper auditLogMapper;
    private final com.pdk.service.AccountAssignmentService assignmentService;

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRED)
    public ActivationResultVO activateCardKeyAtomic(ActivateCardDTO dto, BusinessContext business) {
        if (business.usesDeviceLicense()) {
            throw new BusinessException(40008, "当前业务使用设备许可证；请在登录接口携带卡密完成设备绑定");
        }
        log.info("开始处理卡密核销原子事务: cardKey={}, phone={}, deviceId={}", dto.getCardKey(), dto.getUserPhone(), dto.getDeviceId());

        // 1. 悲观行锁锁定卡密记录
        CardKey cardKey = cardKeyMapper.selectOneForUpdate(business.bizId(), dto.getCardKey());
        if (cardKey == null) {
            throw new BusinessException(40001, "卡密不存在或输入有误");
        }
        if (!"UNUSED".equals(cardKey.getStatus())) {
            throw new BusinessException(40002, "该卡密已被核销使用或已被作废，核销时间: " + cardKey.getActivatedAt());
        }

        // 2. 查询套餐模版
        PackagePlan pkg = packagePlanMapper.selectById(cardKey.getPackageId());
        if (pkg == null || !business.bizIdEquals(pkg.getBizId())) {
            throw new BusinessException(40003, "卡密绑定的套餐模版不存在");
        }
        if (!"ACTIVE".equals(pkg.getStatus())) {
            throw new BusinessException(40007, "卡密绑定的套餐已停用，请联系管理员");
        }
        if (dto.getActualAmount() != null && dto.getActualAmount().compareTo(pkg.getSalePrice()) != 0) {
            throw new BusinessException(40005, "客户端无权修改卡密面值，实收金额必须与套餐面值一致");
        }
        if (dto.getOrderType() != null && !"NORMAL_SALE".equals(dto.getOrderType())) {
            throw new BusinessException(40006, "客户端卡密激活仅允许 NORMAL_SALE 类型");
        }

        // 3. 查询或初始化用户
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getBizId, business.bizId()).eq(User::getPhone, dto.getUserPhone()));
        long activatedCardCount = cardKeyMapper.selectCount(new LambdaQueryWrapper<CardKey>()
                .eq(CardKey::getBizId, business.bizId())
                .eq(CardKey::getActivatedByPhone, dto.getUserPhone())
                .eq(CardKey::getStatus, "ACTIVATED"));
        if (activatedCardCount > 0) {
            throw new BusinessException(40007, "该用户已有生效卡密；续费必须由代理在后台对原卡密办理");
        }
        if (user == null) {
            throw new BusinessException(40100, "账号不存在；请先注册，或使用管理员提供的账号登录后再激活");
        } else {
            // 检查单设备绑定
            if (user.getDeviceId() != null && !user.getDeviceId().equals(dto.getDeviceId())) {
                throw new BusinessException(40103, "账号已绑定其他电脑，请先解绑后再激活");
            }
            if (user.getDeviceId() == null) {
                user.setDeviceId(dto.getDeviceId());
            }
            boolean convertingTrial = "TRIAL".equals(user.getStatus());
            LocalDateTime baseExpire = !convertingTrial && user.getExpireTime() != null && user.getExpireTime().isAfter(LocalDateTime.now())
                    ? user.getExpireTime()
                    : LocalDateTime.now();

            user.setStatus("ACTIVE");
            user.setExpireTime(baseExpire.plusHours(pkg.getDurationHours()));
            user.setCurrentPackageId(pkg.getId());
            user.setCurrentPackageName(pkg.getName());
            int addedCalls = pkg.getAccountCount() * pkg.getCallsPerAccount();
            user.setRemainingCalls(convertingTrial ? addedCalls : (user.getRemainingCalls() != null ? user.getRemainingCalls() : 0) + addedCalls);
            user.setDailyCallsLimit(Math.max(user.getDailyCallsLimit() != null ? user.getDailyCallsLimit() : 0, addedCalls));
            user.setMaxAccounts(Math.max(user.getMaxAccounts() != null ? user.getMaxAccounts() : 1, pkg.getAccountCount()));
            userMapper.updateById(user);
        }

        assignmentService.activatePaid(user, pkg, cardKey.getId());

        // 4. CAS 乐观更新卡密状态为 ACTIVATED
        int updatedRows = cardKeyMapper.update(null, new LambdaUpdateWrapper<CardKey>()
                .eq(CardKey::getId, cardKey.getId())
                .eq(CardKey::getStatus, "UNUSED")
                .set(CardKey::getStatus, "ACTIVATED")
                .set(CardKey::getActivatedByPhone, dto.getUserPhone())
                .set(CardKey::getActivatedByUserId, user.getId())
                .set(CardKey::getActivatedDeviceId, dto.getDeviceId())
                .set(CardKey::getActivatedAt, LocalDateTime.now()));

        if (updatedRows == 0) {
            throw new BusinessException(40004, "并发冲突: 卡密已被其他线程优先核销");
        }

        // 5. 动作二: 严格向独立财务收入表写入记账流水 (物理双表解耦)
        String incomeOrderNo = "INC-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 9000 + 1000);
        BigDecimal actualAmount = pkg.getSalePrice();
        BigDecimal discount = pkg.getListPrice().subtract(actualAmount).max(BigDecimal.ZERO);

        FinancialIncome income = new FinancialIncome();
        income.setBizId(business.bizId());
        income.setUserId(user.getId());
        income.setIncomeOrderNo(incomeOrderNo);
        income.setCardKeyId(cardKey.getId());
        income.setCardKey(cardKey.getCardKey());
        income.setUserPhone(dto.getUserPhone());
        income.setPackageId(pkg.getId());
        income.setPackageName(pkg.getName());
        income.setFaceValue(pkg.getListPrice());
        income.setAmount(actualAmount);
        income.setDiscountAmount(discount);
        income.setOrderType("NORMAL_SALE");
        income.setPaymentChannel(dto.getPaymentChannel());
        income.setPaymentTxnNo(dto.getPaymentTxnNo());
        income.setAuditAdmin(cardKey.getGeneratedByAdmin());
        income.setActivatedAt(LocalDateTime.now());
        income.setAuditRemark("核销入账: 用户 " + dto.getUserPhone() + " 激活 " + pkg.getName());
        financialIncomeMapper.insert(income);

        // 6. 写入审计日志
        PdkAdminAuditLog auditLog = new PdkAdminAuditLog();
        auditLog.setBizId(business.bizId());
        auditLog.setAdminName(cardKey.getGeneratedByAdmin());
        auditLog.setAdminRole("AGENT");
        auditLog.setActionType("ACTIVATE_CARD");
        auditLog.setTargetType("CARD");
        auditLog.setTargetId(dto.getCardKey());
        auditLog.setBeforeState("{\"status\":\"UNUSED\"}");
        auditLog.setAfterState("{\"status\":\"ACTIVATED\",\"phone\":\"" + dto.getUserPhone() + "\",\"orderNo\":\"" + incomeOrderNo + "\"}");
        auditLog.setReason("客户端卡密激活自动核销");
        auditLog.setIpAddress(dto.getClientIp() == null ? "127.0.0.1" : dto.getClientIp());
        auditLog.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(auditLog);

        log.info("卡密原子核销完成: orderNo={}, phone={}, pkg={}", incomeOrderNo, dto.getUserPhone(), pkg.getName());

        return ActivationResultVO.builder()
                .userPhone(dto.getUserPhone())
                .cardKey(dto.getCardKey())
                .packageName(pkg.getName())
                .newExpireTime(user.getExpireTime())
                .extendedDays((pkg.getDurationHours() + 23) / 24)
                .totalRemainingCalls(user.getRemainingCalls())
                .totalAddedCalls(pkg.getAccountCount() * pkg.getCallsPerAccount())
                .incomeOrderNo(incomeOrderNo)
                .queueActionType("DIRECT_EXTEND")
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<String> createCardKeyBatch(CreateCardBatchDTO dto, AdminPrincipal operator) {
        List<String> keys = new ArrayList<>();
        PackagePlan pkg = packagePlanMapper.selectById(dto.getPackageId());
        if (pkg == null) {
            throw new BusinessException(40020, "套餐模版不存在");
        }
        if (!"ACTIVE".equals(pkg.getStatus())) {
            throw new BusinessException(40021, "套餐模版已停用");
        }
        if (!operator.isSuperAdmin() && !pkg.getBizId().equals(operator.bizId())) {
            throw new BusinessException(40311, "代理不能为其他业务制卡");
        }
        if (!operator.isSuperAdmin() && pkg.getOwnerUserId() != null
                && !pkg.getOwnerUserId().equals(operator.id())) {
            throw new BusinessException(40310, "不能使用其他代理创建的套餐制卡");
        }

        for (int i = 0; i < dto.getCount(); i++) {
            String randomKey = "PDK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            String formattedKey = randomKey.substring(0, 8) + "-" + randomKey.substring(8, 12) + "-" + randomKey.substring(12, 16);

            CardKey ck = new CardKey();
            ck.setBizId(pkg.getBizId());
            ck.setCardKey(formattedKey);
            ck.setPackageId(dto.getPackageId());
            ck.setStatus("UNUSED");
            ck.setGeneratedByAdmin(operator.username());
            cardKeyMapper.insert(ck);
            keys.add(formattedKey);
        }

        return keys;
    }
}
