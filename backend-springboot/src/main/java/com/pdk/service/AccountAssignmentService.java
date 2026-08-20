package com.pdk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.UserAssignmentDetail;
import com.pdk.domain.entity.AccountAssignment;
import com.pdk.domain.entity.PackagePlan;
import com.pdk.domain.entity.TokenPool;
import com.pdk.domain.entity.User;
import com.pdk.mapper.AccountAssignmentMapper;
import com.pdk.mapper.TokenPoolMapper;
import com.pdk.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AccountAssignmentService {
    private final AccountAssignmentMapper assignmentMapper;
    private final TokenPoolMapper tokenPoolMapper;
    private final UserMapper userMapper;

    public record AssignedResource(AccountAssignment assignment, TokenPool token) {}

    @Transactional(rollbackFor = Exception.class)
    public boolean allocateTrial(User user, int accountCount, int callsPerAccount) {
        List<TokenPool> tokens = tokenPoolMapper.selectUnassignedHealthyForUpdate(accountCount);
        if (tokens.size() < accountCount) {
            return false;
        }
        insertAssignments(user, 0, null, callsPerAccount, user.getExpireTime(), 1, tokens);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public void activatePaid(User user, PackagePlan plan, Long cardKeyId) {
        releaseActive(user.getId(), "RELEASED");
        allocateNew(user, plan.getId(), cardKeyId, plan.getAccountCount(), plan.getCallsPerAccount(), user.getExpireTime(), 1);
    }

    @Transactional(rollbackFor = Exception.class)
    public void renew(User user, PackagePlan plan, Long cardKeyId) {
        List<AccountAssignment> active = activeAssignments(user.getId());
        int keep = Math.min(active.size(), plan.getAccountCount());
        for (int i = 0; i < keep; i++) {
            AccountAssignment assignment = active.get(i);
            assignment.setPackagePlanId(plan.getId());
            assignment.setCardKeyId(cardKeyId);
            assignment.setAllocatedCalls(assignment.getAllocatedCalls() + plan.getCallsPerAccount());
            assignment.setExpireAt(user.getExpireTime());
            assignment.setSlotIndex(i + 1);
            assignmentMapper.updateById(assignment);
        }
        for (int i = keep; i < active.size(); i++) {
            AccountAssignment assignment = active.get(i);
            assignment.setStatus("RELEASED");
            assignment.setReleasedAt(LocalDateTime.now());
            assignmentMapper.updateById(assignment);
        }
        if (keep < plan.getAccountCount()) {
            allocateNew(user, plan.getId(), cardKeyId, plan.getAccountCount() - keep,
                    plan.getCallsPerAccount(), user.getExpireTime(), keep + 1);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public AssignedResource acquire(User user) {
        assignmentMapper.update(null, new LambdaUpdateWrapper<AccountAssignment>()
                .eq(AccountAssignment::getUserId, user.getId())
                .eq(AccountAssignment::getStatus, "ACTIVE")
                .le(AccountAssignment::getExpireAt, LocalDateTime.now())
                .set(AccountAssignment::getStatus, "RELEASED")
                .set(AccountAssignment::getReleasedAt, LocalDateTime.now()));
        AccountAssignment assignment = assignmentMapper.selectNextUsableForUpdate(user.getId());
        if (assignment == null) {
            throw new BusinessException(50301, "当前套餐没有可用的小号资源，请联系代理或平台处理");
        }
        TokenPool token = tokenPoolMapper.selectById(assignment.getTokenId());
        // 回收过期槽位后，以 assignment 槽位额度为权威重算用户总池，消除双计数错位
        recomputeUserRemainingCalls(user.getId());
        return new AssignedResource(assignment, token);
    }

    /**
     * 用户总池 remaining_calls 的【唯一权威来源】 = 所有 ACTIVE assignment 的 (allocated_calls - used_calls) 之和。
     * 只要槽位额度变化（扣费成功 / 过期回收 / 故障换号 / 管理员调额度），都应调用此方法同步，
     * 避免出现「总池仍有余额但无可用小号」的错位。
     */
    @Transactional(rollbackFor = Exception.class)
    public void recomputeUserRemainingCalls(Long userId) {
        Integer sum = assignmentMapper.selectSumRemaining(userId);
        User user = userMapper.selectById(userId);
        if (user != null) {
            user.setRemainingCalls(sum == null ? 0 : sum);
            userMapper.updateById(user);
        }
    }

    /**
     * 管理员「补次数」：调整为该用户所有 ACTIVE 小号槽位的 allocated_calls（而非直接改用户总池）。
     * 返回是否有可调整的 assignment；调用方在无 assignment 时应回退为直接调整用户级总池。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean adjustAllocatedCalls(Long userId, int extra) {
        List<AccountAssignment> acts = activeAssignments(userId);
        if (acts.isEmpty()) return false;
        for (AccountAssignment a : acts) {
            int newAlloc = a.getAllocatedCalls() + extra;
            a.setAllocatedCalls(Math.max(a.getUsedCalls(), newAlloc));
            assignmentMapper.updateById(a);
        }
        recomputeUserRemainingCalls(userId);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordSuccess(Long assignmentId) {
        assignmentMapper.update(null, new LambdaUpdateWrapper<AccountAssignment>()
                .eq(AccountAssignment::getId, assignmentId)
                .eq(AccountAssignment::getStatus, "ACTIVE")
                .apply("used_calls < allocated_calls")
                .setSql("used_calls = used_calls + 1"));
    }

    @Transactional(rollbackFor = Exception.class)
    public void replaceFault(Long assignmentId) {
        AccountAssignment old = assignmentMapper.selectById(assignmentId);
        if (old == null || !"ACTIVE".equals(old.getStatus())) return;
        List<TokenPool> replacements = tokenPoolMapper.selectUnassignedHealthyForUpdate(1);
        if (replacements.isEmpty()) {
            old.setStatus("RELEASED");
            old.setReleasedAt(LocalDateTime.now());
            assignmentMapper.updateById(old);
            return;
        }
        AccountAssignment replacement = new AccountAssignment();
        replacement.setUserId(old.getUserId());
        replacement.setTokenId(replacements.get(0).getId());
        replacement.setPackagePlanId(old.getPackagePlanId());
        replacement.setCardKeyId(old.getCardKeyId());
        replacement.setSlotIndex(old.getSlotIndex());
        replacement.setAllocatedCalls(old.getAllocatedCalls());
        replacement.setUsedCalls(old.getUsedCalls());
        replacement.setStatus("ACTIVE");
        replacement.setAssignedAt(LocalDateTime.now());
        replacement.setExpireAt(old.getExpireAt());
        assignmentMapper.insert(replacement);
        old.setStatus("REPLACED");
        old.setReleasedAt(LocalDateTime.now());
        old.setReplacedByAssignmentId(replacement.getId());
        assignmentMapper.updateById(old);
    }

    public List<AccountAssignment> activeAssignments(Long userId) {
        return assignmentMapper.selectList(new LambdaQueryWrapper<AccountAssignment>()
                .eq(AccountAssignment::getUserId, userId)
                .eq(AccountAssignment::getStatus, "ACTIVE")
                .orderByAsc(AccountAssignment::getSlotIndex));
    }

    /**
     * 管理后台「客户当前套餐使用详情」：汇总该用户套餐进度 + 名下每个底层小号槽位的使用明细。
     * 明细数据来自 pdk_account_assignment JOIN pdk_token_pool（uuid / 别名 / 健康状态）。
     */
    public UserAssignmentDetail detailByUser(Long userId) {
        User user = userMapper.selectById(userId);
        List<AccountAssignment> acts = activeAssignments(userId);

        UserAssignmentDetail detail = new UserAssignmentDetail();
        detail.setUserId(userId);
        if (user != null) {
            detail.setPhone(user.getPhone());
            detail.setCurrentPackageName(user.getCurrentPackageName());
            detail.setExpireTime(user.getExpireTime());
            detail.setRemainingCalls(user.getRemainingCalls() == null ? 0 : user.getRemainingCalls());
        }

        int totalAlloc = 0;
        int totalUsed = 0;
        List<UserAssignmentDetail.AssignmentItem> items = new ArrayList<>();
        for (AccountAssignment a : acts) {
            TokenPool t = tokenPoolMapper.selectById(a.getTokenId());
            UserAssignmentDetail.AssignmentItem item = new UserAssignmentDetail.AssignmentItem();
            int alloc = a.getAllocatedCalls() == null ? 0 : a.getAllocatedCalls();
            int used = a.getUsedCalls() == null ? 0 : a.getUsedCalls();
            item.setSlotIndex(a.getSlotIndex());
            item.setAllocatedCalls(alloc);
            item.setUsedCalls(used);
            item.setRemaining(alloc - used);
            item.setStatus(a.getStatus());
            item.setExpireAt(a.getExpireAt());
            if (t != null) {
                item.setUuid(t.getUuid());
                item.setAccountAlias(t.getAccountAlias());
                item.setHealthStatus(t.getHealthStatus());
            }
            totalAlloc += alloc;
            totalUsed += used;
            items.add(item);
        }
        detail.setTotalAllocated(totalAlloc);
        detail.setTotalUsed(totalUsed);
        detail.setAccounts(items);
        return detail;
    }

    @Transactional(rollbackFor = Exception.class)
    public void terminate(Long userId) {
        releaseActive(userId, "RELEASED");
    }

    private void releaseActive(Long userId, String status) {
        assignmentMapper.update(null, new LambdaUpdateWrapper<AccountAssignment>()
                .eq(AccountAssignment::getUserId, userId)
                .eq(AccountAssignment::getStatus, "ACTIVE")
                .set(AccountAssignment::getStatus, status)
                .set(AccountAssignment::getReleasedAt, LocalDateTime.now()));
    }

    private void allocateNew(User user, Integer packageId, Long cardKeyId, int count, int calls,
                             LocalDateTime expireAt, int startSlot) {
        List<TokenPool> tokens = tokenPoolMapper.selectUnassignedHealthyForUpdate(count);
        if (tokens.size() < count) {
            throw new BusinessException(50302, "公司可分配小号资源不足，需要 " + count + " 个，当前只有 " + tokens.size() + " 个");
        }
        insertAssignments(user, packageId, cardKeyId, calls, expireAt, startSlot, tokens);
    }

    private void insertAssignments(User user, Integer packageId, Long cardKeyId, int calls,
                                   LocalDateTime expireAt, int startSlot, List<TokenPool> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            AccountAssignment assignment = new AccountAssignment();
            assignment.setUserId(user.getId());
            assignment.setTokenId(tokens.get(i).getId());
            assignment.setPackagePlanId(packageId);
            assignment.setCardKeyId(cardKeyId);
            assignment.setSlotIndex(startSlot + i);
            assignment.setAllocatedCalls(calls);
            assignment.setUsedCalls(0);
            assignment.setStatus("ACTIVE");
            assignment.setAssignedAt(LocalDateTime.now());
            assignment.setExpireAt(expireAt);
            assignmentMapper.insert(assignment);
        }
    }
}
