package com.pdk.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pdk.common.api.CommonResult;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.TokenResourceDTO;
import com.pdk.domain.entity.TokenPool;
import com.pdk.mapper.TokenPoolMapper;
import com.pdk.security.RequirePermission;
import com.pdk.security.RolePermissions;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.pdk.security.AdminPrincipal;
import com.pdk.service.AdminAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import com.pdk.platform.business.BusinessService;
import com.pdk.platform.business.BusinessContext;

@RestController
@RequestMapping("/api/v1/admin/token")
@RequiredArgsConstructor
public class AdminTokenController {
    private final TokenPoolMapper tokenPoolMapper;
    private final AdminAuditService adminAuditService;
    private final BusinessService businessService;

    @GetMapping("/list")
    @RequirePermission(RolePermissions.TOKEN_VIEW)
    public CommonResult<Page<TokenPool>> list(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Integer discarded,
                                               @RequestParam(required = false) Long bizId,
                                               @RequestParam(required = false) Long appId) {
        LambdaQueryWrapper<TokenPool> query = new LambdaQueryWrapper<>();
        if (appId != null) bizId = businessService.requireByAppId(appId).getId();
        if (bizId != null) query.eq(TokenPool::getBizId, bizId);
        if (status != null && !status.isBlank()) {
            query.eq(TokenPool::getHealthStatus, status);
        }
        if (discarded != null) {
            query.eq(TokenPool::getIsDiscarded, discarded);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> w.like(TokenPool::getAccountAlias, kw).or().like(TokenPool::getUuid, kw));
        }
        query.orderByDesc(TokenPool::getCreatedAt);
        Page<TokenPool> result = tokenPoolMapper.selectPage(new Page<>(page, Math.min(size, 100)), query);
        result.getRecords().forEach(this::maskSecret);
        return CommonResult.success(result);
    }

    @PostMapping
    @RequirePermission(RolePermissions.TOKEN_EDIT)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<TokenPool> create(@Valid @RequestBody TokenResourceDTO dto, HttpServletRequest request) {
        BusinessContext business = businessService.requireAvailableByAppId(dto.getAppId());
        TokenPool token = new TokenPool();
        token.setBizId(business.bizId());
        token.setAccountAlias(dto.getAccountAlias());
        token.setTokenVal(dto.getTokenVal());
        token.setCredentialType(dto.getCredentialType());
        token.setCredentialPayload(dto.getTokenVal());
        token.setHealthStatus("HEALTHY");
        token.setDailyCallsCount(0);
        token.setDailyMaxCapacity(dto.getDailyMaxCapacity());
        token.setRiskScore(0);
        tokenPoolMapper.insert(token);
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        adminAuditService.record(admin, token.getBizId(), "CREATE_TOKEN_RESOURCE", "ACCOUNT", token.getId().toString(), null,
                "{\"alias\":\"" + token.getAccountAlias() + "\",\"capacity\":" + token.getDailyMaxCapacity() + "}",
                "录入小号资源", request);
        maskSecret(token);
        return CommonResult.success(token, "资源已加入公共池");
    }

    @PutMapping("/{id}/status")
    @RequirePermission(RolePermissions.TOKEN_EDIT)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<String> changeStatus(@PathVariable Long id, @RequestParam String status,
                                              HttpServletRequest request) {
        if (!java.util.Set.of("HEALTHY", "FAULT_BLACK", "EXPIRED").contains(status)) {
            throw new BusinessException(40030, "资源状态不合法");
        }
        TokenPool token = tokenPoolMapper.selectById(id);
        if (token == null) {
            throw new BusinessException(40401, "资源不存在");
        }
        String beforeStatus = token.getHealthStatus();
        token.setHealthStatus(status);
        if ("HEALTHY".equals(status)) {
            token.setRiskScore(0);
            token.setDailyCallsCount(0);
        }
        tokenPoolMapper.updateById(token);
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        adminAuditService.record(admin, token.getBizId(), "CHANGE_TOKEN_STATUS", "ACCOUNT", id.toString(),
                "{\"status\":\"" + beforeStatus + "\"}", "{\"status\":\"" + status + "\"}",
                "调整小号资源状态", request);
        return CommonResult.success("资源状态已更新");
    }

    @PostMapping("/import")
    @RequirePermission(RolePermissions.TOKEN_EDIT)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<Map<String, Object>> importTokens(@RequestParam("file") MultipartFile file,
                                                          @RequestParam Long appId,
                                                          @RequestParam(defaultValue = "500") int dailyMaxCapacity,
                                                          HttpServletRequest request) throws IOException {
        BusinessContext business = businessService.requireAvailableByAppId(appId);
        if (file.isEmpty()) {
            throw new BusinessException(40032, "上传文件为空");
        }
        byte[] bytes = file.getBytes();
        String preview = new String(bytes, StandardCharsets.UTF_8);
        Charset cs = preview.contains("\uFFFD") ? Charset.forName("GBK") : StandardCharsets.UTF_8;
        List<TokenPool> list = new java.util.ArrayList<>();
        int skipped = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), cs))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("[-—━]{2,}", 2);
                if (parts.length < 2) { skipped++; continue; }
                String alias = parts[0].trim();
                String token = parts[1].trim();
                if (alias.isEmpty() || token.isEmpty() || alias.length() > 64 || token.length() > 512) {
                    skipped++; continue;
                }
                TokenPool t = new TokenPool();
                t.setBizId(business.bizId());
                t.setAccountAlias(alias);
                t.setTokenVal(token);
                t.setCredentialType("TOKEN");
                t.setCredentialPayload(token);
                t.setHealthStatus("HEALTHY");
                t.setDailyCallsCount(0);
                t.setDailyMaxCapacity(dailyMaxCapacity);
                t.setRiskScore(0);
                t.setUuid(UUID.randomUUID().toString());
                t.setIsDiscarded(0);
                list.add(t);
            }
        }
        if (list.isEmpty()) {
            throw new BusinessException(40033, "未解析到任何有效记录，请检查文件格式是否为「别名------token」");
        }
        tokenPoolMapper.batchInsert(list);
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        adminAuditService.record(admin, business.bizId(), "IMPORT_TOKEN_RESOURCE", "ACCOUNT", "batch",
                null, "{\"imported\":" + list.size() + ",\"skipped\":" + skipped + "}", "批量导入底层小号", request);
        Map<String, Object> result = new HashMap<>();
        result.put("imported", list.size());
        result.put("skipped", skipped);
        return CommonResult.success(result, "成功导入 " + list.size() + " 条，跳过 " + skipped + " 条格式错误行");
    }

    @PostMapping("/batch-discard")
    @RequirePermission(RolePermissions.TOKEN_EDIT)
    @Transactional(rollbackFor = Exception.class)
    public CommonResult<String> batchDiscard(@RequestBody List<Long> ids, HttpServletRequest request) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(40031, "未选择任何记录");
        }
        int updated = tokenPoolMapper.update(null, new LambdaUpdateWrapper<TokenPool>()
                .in(TokenPool::getId, ids).set(TokenPool::getIsDiscarded, 1));
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        adminAuditService.record(admin, "DISCARD_TOKEN_RESOURCE", "ACCOUNT", ids.toString(),
                null, "{\"discarded\":1}", "批量逻辑废弃小号 " + ids.size() + " 条（记录保留）", request);
        return CommonResult.success("已逻辑废弃 " + updated + " 条（记录保留，不再参与调度）");
    }

    @PutMapping("/{id}/discard")
    @RequirePermission(RolePermissions.TOKEN_EDIT)
    public CommonResult<String> discard(@PathVariable Long id, HttpServletRequest request) {
        TokenPool token = tokenPoolMapper.selectById(id);
        if (token == null) throw new BusinessException(40401, "资源不存在");
        token.setIsDiscarded(1);
        tokenPoolMapper.updateById(token);
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        adminAuditService.record(admin, token.getBizId(), "DISCARD_TOKEN_RESOURCE", "ACCOUNT", id.toString(),
                null, "{\"discarded\":1}", "逻辑废弃小号", request);
        return CommonResult.success("已逻辑废弃该小号（记录保留，不再参与调度）");
    }

    @PutMapping("/{id}/restore")
    @RequirePermission(RolePermissions.TOKEN_EDIT)
    public CommonResult<String> restore(@PathVariable Long id, HttpServletRequest request) {
        TokenPool token = tokenPoolMapper.selectById(id);
        if (token == null) throw new BusinessException(40401, "资源不存在");
        token.setIsDiscarded(0);
        tokenPoolMapper.updateById(token);
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute("pdkAdminPrincipal");
        adminAuditService.record(admin, token.getBizId(), "RESTORE_TOKEN_RESOURCE", "ACCOUNT", id.toString(),
                null, "{\"discarded\":0}", "恢复废弃小号", request);
        return CommonResult.success("已恢复该小号为可用状态");
    }

    private void maskSecret(TokenPool token) {
        String value = token.getTokenVal();
        if (value == null || value.length() < 9) {
            token.setTokenVal("********");
            token.setCredentialPayload("********");
            return;
        }
        token.setTokenVal(value.substring(0, 4) + "****" + value.substring(value.length() - 4));
        token.setCredentialPayload("********");
    }
}
