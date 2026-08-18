package com.pdk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.ActivateCardDTO;
import com.pdk.domain.dto.CreateCardBatchDTO;
import com.pdk.domain.dto.TrialRegisterDTO;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class CardKeyActivationServiceImpl implements ICardKeyActivationService {

    private final CardKeyMapper cardKeyMapper;
    private final FinancialIncomeMapper financialIncomeMapper;
    private final UserMapper userMapper;
    private final PackageTemplateMapper packageTemplateMapper;
    private final AdminAuditLogMapper auditLogMapper;

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRED)
    public ActivationResultVO activateCardKeyAtomic(ActivateCardDTO dto) {
        log.info("开始处理卡密核销原子事务: cardKey={}, phone={}, deviceId={}", dto.getCardKey(), dto.getUserPhone(), dto.getDeviceId());

        // 1. 悲观行锁锁定卡密记录
        CardKey cardKey = cardKeyMapper.selectOneForUpdate(dto.getCardKey());
        if (cardKey == null) {
            throw new BusinessException(40001, "卡密不存在或输入有误");
        }
        if (!"UNUSED".equals(cardKey.getStatus())) {
            throw new BusinessException(40002, "该卡密已被核销使用或已被作废，核销时间: " + cardKey.getActivatedAt());
        }

        // 2. 查询套餐模版
        PackageTemplate pkg = packageTemplateMapper.selectById(cardKey.getPackageId());
        if (pkg == null) {
            throw new BusinessException(40003, "卡密绑定的套餐模版不存在");
        }

        // 3. 查询或初始化用户
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, dto.getUserPhone()));
        if (user == null) {
            user = new User();
            user.setPhone(dto.getUserPhone());
            user.setStatus("ACTIVE");
            user.setDeviceId(dto.getDeviceId());
            user.setCurrentPackageId(pkg.getId());
            user.setCurrentPackageName(pkg.getName());
            user.setExpireTime(LocalDateTime.now().plusDays(pkg.getDurationDays()));
            user.setRemainingCalls(pkg.getAccountCountX() * pkg.getCallsPerAccountY());
            user.setDailyCallsLimit(pkg.getAccountCountX() * pkg.getCallsPerAccountY());
            user.setMaxAccounts(pkg.getAccountCountX());
            user.setIsTrialClaimed(0);
            userMapper.insert(user);
        } else {
            // 检查单设备绑定
            user.setDeviceId(dto.getDeviceId());
            LocalDateTime baseExpire = user.getExpireTime() != null && user.getExpireTime().isAfter(LocalDateTime.now())
                    ? user.getExpireTime()
                    : LocalDateTime.now();

            user.setExpireTime(baseExpire.plusDays(pkg.getDurationDays()));
            user.setCurrentPackageId(pkg.getId());
            user.setCurrentPackageName(pkg.getName());
            int addedCalls = pkg.getAccountCountX() * pkg.getCallsPerAccountY();
            user.setRemainingCalls((user.getRemainingCalls() != null ? user.getRemainingCalls() : 0) + addedCalls);
            user.setDailyCallsLimit(Math.max(user.getDailyCallsLimit() != null ? user.getDailyCallsLimit() : 0, addedCalls));
            user.setMaxAccounts(Math.max(user.getMaxAccounts() != null ? user.getMaxAccounts() : 1, pkg.getAccountCountX()));
            userMapper.updateById(user);
        }

        // 4. CAS 乐观更新卡密状态为 ACTIVATED
        int updatedRows = cardKeyMapper.update(null, new LambdaUpdateWrapper<CardKey>()
                .eq(CardKey::getId, cardKey.getId())
                .eq(CardKey::getStatus, "UNUSED")
                .set(CardKey::getStatus, "ACTIVATED")
                .set(CardKey::getActivatedByPhone, dto.getUserPhone())
                .set(CardKey::getActivatedDeviceId, dto.getDeviceId())
                .set(CardKey::getActivatedAt, LocalDateTime.now()));

        if (updatedRows == 0) {
            throw new BusinessException(40004, "并发冲突: 卡密已被其他线程优先核销");
        }

        // 5. 动作二: 严格向独立财务收入表写入记账流水 (物理双表解耦)
        String incomeOrderNo = "INC-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 9000 + 1000);
        BigDecimal actualAmount = dto.getActualAmount() != null ? dto.getActualAmount() : pkg.getPrice();
        BigDecimal discount = pkg.getPrice().subtract(actualAmount).max(BigDecimal.ZERO);

        FinancialIncome income = new FinancialIncome();
        income.setIncomeOrderNo(incomeOrderNo);
        income.setCardKeyId(cardKey.getId());
        income.setCardKey(cardKey.getCardKey());
        income.setUserPhone(dto.getUserPhone());
        income.setPackageId(pkg.getId());
        income.setPackageName(pkg.getName());
        income.setFaceValue(pkg.getPrice());
        income.setAmount(actualAmount);
        income.setDiscountAmount(discount);
        income.setOrderType(dto.getOrderType());
        income.setPaymentChannel(dto.getPaymentChannel());
        income.setPaymentTxnNo(dto.getPaymentTxnNo());
        income.setAuditAdmin(cardKey.getGeneratedByAdmin());
        income.setActivatedAt(LocalDateTime.now());
        income.setAuditRemark("核销入账: 用户 " + dto.getUserPhone() + " 激活 " + pkg.getName());
        financialIncomeMapper.insert(income);

        // 6. 写入审计日志
        AdminAuditLog auditLog = new AdminAuditLog();
        auditLog.setOperatorUsername(cardKey.getGeneratedByAdmin());
        auditLog.setOperatorRole("AGENT");
        auditLog.setActionType("ACTIVATE_CARD");
        auditLog.setTargetIdentifier(dto.getCardKey());
        auditLog.setDetailsJson("{\"phone\":\"" + dto.getUserPhone() + "\",\"orderNo\":\"" + incomeOrderNo + "\"}");
        auditLog.setClientIp(dto.getClientIp());
        auditLogMapper.insert(auditLog);

        log.info("卡密原子核销完成: orderNo={}, phone={}, pkg={}", incomeOrderNo, dto.getUserPhone(), pkg.getName());

        return ActivationResultVO.builder()
                .userPhone(dto.getUserPhone())
                .cardKey(dto.getCardKey())
                .packageName(pkg.getName())
                .newExpireTime(user.getExpireTime())
                .extendedDays(pkg.getDurationDays())
                .totalRemainingCalls(user.getRemainingCalls())
                .totalAddedCalls(pkg.getAccountCountX() * pkg.getCallsPerAccountY())
                .incomeOrderNo(incomeOrderNo)
                .queueActionType("DIRECT_EXTEND")
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActivationResultVO registerTrialAccount(TrialRegisterDTO dto) {
        User existUser = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, dto.getPhone()));
        if (existUser != null && existUser.getIsTrialClaimed() != null && existUser.getIsTrialClaimed() == 1) {
            throw new BusinessException(40010, "该手机号已领取过新人1天20次试用体验，不可重复领取");
        }

        if (existUser == null) {
            existUser = new User();
            existUser.setPhone(dto.getPhone());
            existUser.setStatus("TRIAL");
            existUser.setDeviceId(dto.getDeviceId());
            existUser.setCurrentPackageId(0);
            existUser.setCurrentPackageName("新人1天体验版 (1账号×20次/天)");
            existUser.setExpireTime(LocalDateTime.now().plusDays(1));
            existUser.setRemainingCalls(20);
            existUser.setDailyCallsLimit(20);
            existUser.setMaxAccounts(1);
            existUser.setIsTrialClaimed(1);
            userMapper.insert(existUser);
        } else {
            existUser.setStatus("TRIAL");
            existUser.setDeviceId(dto.getDeviceId());
            existUser.setCurrentPackageName("新人1天体验版 (1账号×20次/天)");
            existUser.setExpireTime(LocalDateTime.now().plusDays(1));
            existUser.setRemainingCalls(20);
            existUser.setDailyCallsLimit(20);
            existUser.setMaxAccounts(1);
            existUser.setIsTrialClaimed(1);
            userMapper.updateById(existUser);
        }

        return ActivationResultVO.builder()
                .userPhone(dto.getPhone())
                .packageName("新人1天体验版 (1账号×20次/天)")
                .newExpireTime(existUser.getExpireTime())
                .extendedDays(1)
                .totalRemainingCalls(20)
                .totalAddedCalls(20)
                .queueActionType("TRIAL_CLAIMED")
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<String> createCardKeyBatch(CreateCardBatchDTO dto, String operatorAdmin) {
        List<String> keys = new ArrayList<>();
        PackageTemplate pkg = packageTemplateMapper.selectById(dto.getPackageId());
        if (pkg == null) {
            throw new BusinessException(40020, "套餐模版不存在");
        }

        for (int i = 0; i < dto.getCount(); i++) {
            String randomKey = "PDK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            String formattedKey = randomKey.substring(0, 8) + "-" + randomKey.substring(8, 12) + "-" + randomKey.substring(12, 16);

            CardKey ck = new CardKey();
            ck.setCardKey(formattedKey);
            ck.setPackageId(dto.getPackageId());
            ck.setStatus("UNUSED");
            ck.setGeneratedByAdmin(operatorAdmin);
            cardKeyMapper.insert(ck);
            keys.add(formattedKey);
        }

        return keys;
    }
}
