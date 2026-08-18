package com.pdk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.entity.AccountAssignment;
import com.pdk.domain.entity.PackagePlan;
import com.pdk.domain.entity.TokenPool;
import com.pdk.domain.entity.User;
import com.pdk.mapper.AccountAssignmentMapper;
import com.pdk.mapper.TokenPoolMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AccountAssignmentService {
    private final AccountAssignmentMapper assignmentMapper;
    private final TokenPoolMapper tokenPoolMapper;

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
        return new AssignedResource(assignment, token);
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
