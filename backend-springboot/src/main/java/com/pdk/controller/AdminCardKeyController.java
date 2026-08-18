package com.pdk.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pdk.common.api.CommonResult;
import com.pdk.domain.dto.CreateCardBatchDTO;
import com.pdk.domain.entity.CardKey;
import com.pdk.mapper.CardKeyMapper;
import com.pdk.service.ICardKeyActivationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/card")
@RequiredArgsConstructor
@Tag(name = "卡密管理模块", description = "制卡凭证池、批量生成与批次导出")
public class AdminCardKeyController {

    private final ICardKeyActivationService activationService;
    private final CardKeyMapper cardKeyMapper;

    @PostMapping("/batch-generate")
    @Operation(summary = "管理员/代理商批量生成卡密")
    public CommonResult<List<String>> batchGenerate(
            @Valid @RequestBody CreateCardBatchDTO dto,
            @RequestHeader(value = "X-Admin-User", defaultValue = "super_admin") String adminUser) {
        List<String> keys = activationService.createCardKeyBatch(dto, adminUser);
        return CommonResult.success(keys, "批量制卡成功");
    }

    @GetMapping("/list")
    @Operation(summary = "分页查询卡密列表")
    public CommonResult<Page<CardKey>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {
        Page<CardKey> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<CardKey> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq(CardKey::getStatus, status);
        }
        wrapper.orderByDesc(CardKey::getCreatedAt);
        return CommonResult.success(cardKeyMapper.selectPage(pageParam, wrapper));
    }
}
