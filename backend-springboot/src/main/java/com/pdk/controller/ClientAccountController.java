package com.pdk.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pdk.common.api.CommonResult;
import com.pdk.domain.dto.AcquireTokenRequestDTO;
import com.pdk.domain.dto.ReportResultDTO;
import com.pdk.domain.entity.PdkDispatchLog;
import com.pdk.domain.entity.User;
import com.pdk.domain.entity.AccountAssignment;
import com.pdk.domain.entity.CardKey;
import com.pdk.domain.vo.EncryptedTokenPayloadVO;
import com.pdk.mapper.PdkDispatchLogMapper;
import com.pdk.mapper.AccountAssignmentMapper;
import com.pdk.mapper.CardKeyMapper;
import com.pdk.service.IDispatchGatewayService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/client")
@RequiredArgsConstructor
public class ClientAccountController {
    private final PdkDispatchLogMapper dispatchLogMapper;
    private final IDispatchGatewayService gatewayService;
    private final AccountAssignmentMapper assignmentMapper;
    private final CardKeyMapper cardKeyMapper;

    @GetMapping("/account/profile")
    public CommonResult<Map<String, Object>> profile(HttpServletRequest request) {
        User user = currentUser(request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phone", user.getPhone());
        data.put("status", user.getStatus());
        data.put("deviceId", user.getDeviceId());
        data.put("packageName", user.getCurrentPackageName());
        data.put("expireTime", user.getExpireTime());
        data.put("remainingCalls", user.getRemainingCalls());
        data.put("dailyCallsLimit", user.getDailyCallsLimit());
        data.put("maxAccounts", user.getMaxAccounts());
        return CommonResult.success(data);
    }

    @GetMapping("/account/usage")
    public CommonResult<Map<String, Object>> usage(HttpServletRequest request,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        User user = currentUser(request);
        LambdaQueryWrapper<PdkDispatchLog> base = new LambdaQueryWrapper<PdkDispatchLog>()
                .eq(PdkDispatchLog::getUserPhone, user.getPhone());
        long total = dispatchLogMapper.selectCount(base);
        long successes = dispatchLogMapper.selectCount(new LambdaQueryWrapper<PdkDispatchLog>()
                .eq(PdkDispatchLog::getUserPhone, user.getPhone())
                .eq(PdkDispatchLog::getExecStatus, "SUCCESS"));
        long failures = total - successes;
        Page<PdkDispatchLog> logs = dispatchLogMapper.selectPage(new Page<>(page, Math.min(size, 100)),
                new LambdaQueryWrapper<PdkDispatchLog>()
                        .eq(PdkDispatchLog::getUserPhone, user.getPhone())
                        .orderByDesc(PdkDispatchLog::getCreatedAt));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("remainingCalls", user.getRemainingCalls());
        data.put("totalReported", total);
        data.put("successCount", successes);
        data.put("failureCount", failures);
        data.put("records", logs);
        return CommonResult.success(data);
    }

    @GetMapping("/resources/status")
    public CommonResult<Map<String, Object>> resourceStatus(HttpServletRequest request) {
        User user = currentUser(request);
        java.util.List<AccountAssignment> assignments = assignmentMapper.selectList(new LambdaQueryWrapper<AccountAssignment>()
                .eq(AccountAssignment::getUserId, user.getId())
                .eq(AccountAssignment::getStatus, "ACTIVE")
                .orderByAsc(AccountAssignment::getSlotIndex));
        long available = assignments.stream().filter(a -> a.getUsedCalls() < a.getAllocatedCalls()).count();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assignedResourceCount", assignments.size());
        data.put("availableResourceCount", available);
        data.put("maxConcurrentAccounts", user.getMaxAccounts());
        data.put("remainingCalls", user.getRemainingCalls());
        data.put("assignments", assignments);
        data.put("resourcePolicy", "套餐期独占；客户端只能使用自己名下的小号，异常后平台替换并继承已用次数");
        return CommonResult.success(data);
    }

    @GetMapping("/account/card")
    public CommonResult<Map<String, Object>> card(HttpServletRequest request) {
        User user = currentUser(request);
        CardKey card = cardKeyMapper.selectOne(new LambdaQueryWrapper<CardKey>()
                .eq(CardKey::getActivatedByPhone, user.getPhone())
                .orderByDesc(CardKey::getActivatedAt)
                .last("LIMIT 1"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cardKey", card == null ? null : card.getCardKey());
        data.put("cardStatus", card == null ? null : card.getStatus());
        data.put("packageName", user.getCurrentPackageName());
        data.put("expireTime", user.getExpireTime());
        data.put("remainingCalls", user.getRemainingCalls());
        data.put("assignments", assignmentMapper.selectList(new LambdaQueryWrapper<AccountAssignment>()
                .eq(AccountAssignment::getUserId, user.getId())
                .eq(AccountAssignment::getStatus, "ACTIVE")
                .orderByAsc(AccountAssignment::getSlotIndex)));
        return CommonResult.success(data);
    }

    @PostMapping("/resources/acquire")
    public CommonResult<EncryptedTokenPayloadVO> acquire(@Valid @RequestBody AcquireTokenRequestDTO dto,
                                                          HttpServletRequest request) {
        User user = currentUser(request);
        String deviceId = request.getHeader("X-PDK-Device-ID");
        return CommonResult.success(gatewayService.acquireEncryptedToken(dto, user.getPhone(), deviceId));
    }

    @PostMapping("/resources/report")
    public CommonResult<String> report(@Valid @RequestBody ReportResultDTO dto, HttpServletRequest request) {
        User user = currentUser(request);
        gatewayService.reportAndDeductQuota(dto, user.getPhone());
        return CommonResult.success("资源使用结果已记录");
    }

    private User currentUser(HttpServletRequest request) {
        return (User) request.getAttribute("pdkClientUser");
    }
}
