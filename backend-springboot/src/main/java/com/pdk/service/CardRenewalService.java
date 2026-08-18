package com.pdk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.RenewCardDTO;
import com.pdk.domain.entity.*;
import com.pdk.mapper.*;
import com.pdk.security.AdminPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CardRenewalService {
    private final CardKeyMapper cardKeyMapper;
    private final UserMapper userMapper;
    private final PackagePlanMapper packagePlanMapper;
    private final FinancialIncomeMapper incomeMapper;
    private final AccountAssignmentService assignmentService;

    @Transactional(rollbackFor = Exception.class)
    public FinancialIncome renew(String cardValue, RenewCardDTO dto, AdminPrincipal principal) {
        CardKey card = cardKeyMapper.selectOneForUpdate(cardValue);
        if (card == null || !"ACTIVATED".equals(card.getStatus())) throw new BusinessException(40040, "卡密不存在或未激活");
        assertOwner(card, principal);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, card.getActivatedByPhone()));
        if (user == null) throw new BusinessException(40402, "卡密绑定用户不存在");
        PackagePlan plan = packagePlanMapper.selectById(dto.getPackageId());
        if (plan == null || !"ACTIVE".equals(plan.getStatus())) throw new BusinessException(40020, "续费套餐不存在或已停用");
        if (!principal.isSuperAdmin() && plan.getOwnerUserId() != null && !principal.id().equals(plan.getOwnerUserId())) {
            throw new BusinessException(40310, "不能使用其他代理创建的套餐续费");
        }

        LocalDateTime base = user.getExpireTime() != null && user.getExpireTime().isAfter(LocalDateTime.now())
                ? user.getExpireTime() : LocalDateTime.now();
        user.setExpireTime(base.plusHours(plan.getDurationHours()));
        user.setStatus("ACTIVE");
        user.setCurrentPackageId(plan.getId());
        user.setCurrentPackageName(plan.getName());
        int addedCalls = plan.getAccountCount() * plan.getCallsPerAccount();
        user.setRemainingCalls((user.getRemainingCalls() == null ? 0 : user.getRemainingCalls()) + addedCalls);
        user.setDailyCallsLimit(addedCalls);
        user.setMaxAccounts(plan.getAccountCount());
        userMapper.updateById(user);
        card.setPackageId(plan.getId());
        cardKeyMapper.updateById(card);
        assignmentService.renew(user, plan, card.getId());

        FinancialIncome income = new FinancialIncome();
        income.setIncomeOrderNo("REN-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 9000 + 1000));
        income.setCardKeyId(card.getId());
        income.setCardKey(card.getCardKey());
        income.setUserPhone(user.getPhone());
        income.setPackageId(plan.getId());
        income.setPackageName(plan.getName());
        income.setFaceValue(plan.getListPrice());
        income.setAmount(plan.getSalePrice());
        income.setDiscountAmount(plan.getListPrice().subtract(plan.getSalePrice()).max(BigDecimal.ZERO));
        income.setOrderType("RENEWAL");
        income.setPaymentChannel("OFFLINE");
        income.setPaymentTxnNo(dto.getPaymentTxnNo());
        income.setAuditAdmin(principal.username());
        income.setActivatedAt(LocalDateTime.now());
        income.setAuditRemark(dto.getRemark() == null ? "原卡密后台续费" : dto.getRemark());
        incomeMapper.insert(income);
        return income;
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidCard(String cardValue, AdminPrincipal principal) {
        CardKey card = cardKeyMapper.selectOneForUpdate(cardValue);
        if (card == null) throw new BusinessException(40403, "卡密不存在");
        assertOwner(card, principal);
        if ("VOID".equals(card.getStatus())) return;
        card.setStatus("VOID");
        cardKeyMapper.updateById(card);
        if (card.getActivatedByPhone() != null) {
            User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, card.getActivatedByPhone()));
            if (user != null) {
                user.setExpireTime(LocalDateTime.now());
                user.setRemainingCalls(0);
                userMapper.updateById(user);
                assignmentService.terminate(user.getId());
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public int voidAllOwnedCards(AdminPrincipal principal) {
        List<CardKey> cards = cardKeyMapper.selectList(new LambdaQueryWrapper<CardKey>()
                .eq(CardKey::getGeneratedByAdmin, principal.username())
                .ne(CardKey::getStatus, "VOID"));
        cards.forEach(card -> voidCard(card.getCardKey(), principal));
        return cards.size();
    }

    private void assertOwner(CardKey card, AdminPrincipal principal) {
        if (!principal.isSuperAdmin() && !principal.username().equals(card.getGeneratedByAdmin())) {
            throw new BusinessException(40310, "只能操作自己生成的卡密");
        }
    }
}
