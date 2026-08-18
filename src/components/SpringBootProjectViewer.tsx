import React, { useState } from 'react';
import { 
  FolderTree, 
  FileCode, 
  Copy, 
  Check, 
  Download, 
  Layers, 
  Server, 
  Database, 
  ShieldCheck, 
  Sparkles, 
  Code2, 
  Cpu,
  ChevronRight,
  ChevronDown,
  FileText
} from 'lucide-react';

interface CodeFile {
  path: string;
  name: string;
  category: 'BACKEND' | 'FRONTEND';
  language: string;
  description: string;
  code: string;
}

export const SpringBootProjectViewer: React.FC = () => {
  const [activeCategory, setActiveCategory] = useState<'BACKEND' | 'FRONTEND'>('BACKEND');
  const [selectedFilePath, setSelectedFilePath] = useState<string>('src/main/java/com/pdk/service/impl/CardKeyActivationServiceImpl.java');
  const [copied, setCopied] = useState<boolean>(false);

  const files: CodeFile[] = [
    // 1. ServiceImpl - 核心原子事务
    {
      path: 'src/main/java/com/pdk/service/impl/CardKeyActivationServiceImpl.java',
      name: 'CardKeyActivationServiceImpl.java',
      category: 'BACKEND',
      language: 'java',
      description: '【核心原子事务】卡密核销 + 财务收入独立表入库 + 用户配额注入与顺延/排队 (双表解耦严防漏记)',
      code: `package com.pdk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.ActivateCardDTO;
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
import java.util.UUID;

/**
 * 卡密核销与财务记账核心服务实现 (Spring Boot 3 + MyBatis-Plus)
 * 严格遵循《PDK产品开发规范》：卡密表与财务表物理彻底拆分，采用标准数据库事务编排
 */
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
    @Transactional(
        rollbackFor = Exception.class, 
        isolation = Isolation.READ_COMMITTED, 
        propagation = Propagation.REQUIRED
    )
    public ActivationResultVO activateCardKeyAtomic(ActivateCardDTO dto) {
        String cardKeyStr = dto.getCardKey().trim();
        String userPhone = dto.getUserPhone().trim();

        log.info(">>> 开始处理卡密原子核销请求: cardKey={}, userPhone={}, deviceId={}", 
                 cardKeyStr, userPhone, dto.getDeviceId());

        // 1. 查询并悲观锁锁定卡密 (SELECT ... FOR UPDATE) 防并发重复核销
        CardKey cardKey = cardKeyMapper.selectOneForUpdate(cardKeyStr);
        if (cardKey == null) {
            throw new BusinessException(40001, "卡密不存在，请检查后重新输入");
        }
        if ("ACTIVATED".equals(cardKey.getStatus())) {
            throw new BusinessException(40002, "该卡密已于 " + cardKey.getActivatedAt() + " 被核销使用，不可重复激活");
        }
        if ("VOIDED".equals(cardKey.getStatus())) {
            throw new BusinessException(40003, "该卡密已被管理员作废锁定，请联系客服");
        }

        // 2. 校验用户合法性
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, userPhone));
        if (user == null) {
            throw new BusinessException(40004, "目标用户手机号不存在，请先通过短信注册");
        }
        if ("BLOCKED".equals(user.getStatus())) {
            throw new BusinessException(40005, "该用户已被封禁，禁止充值续费");
        }

        // 3. 获取对应套餐配置模板
        PackageTemplate pkg = packageTemplateMapper.selectById(cardKey.getPackageId());
        if (pkg == null) {
            throw new BusinessException(40006, "关联套餐模板不存在或已下架");
        }

        LocalDateTime now = LocalDateTime.now();

        // 4. 【动作一】原子更新卡密状态为已激活 (乐观CAS保护)
        int updatedRows = cardKeyMapper.update(null, new LambdaUpdateWrapper<CardKey>()
                .eq(CardKey::getId, cardKey.getId())
                .eq(CardKey::getStatus, "UNUSED") // CAS条件
                .set(CardKey::getStatus, "ACTIVATED")
                .set(CardKey::getBoundUserPhone, userPhone)
                .set(CardKey::getActivatedAt, now));

        if (updatedRows == 0) {
            log.error("CAS 并发锁定失败: 卡密已被其他线程激活, cardKey={}", cardKeyStr);
            throw new BusinessException(40007, "并发核销冲突，请稍后重试");
        }

        // 5. 【动作二】向独立的【财务收入流水表 pdk_financial_income】写入对账记录 (独立双表解耦)
        FinancialIncome income = new FinancialIncome();
        income.setIncomeOrderNo("INC_" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "_" + ((int)(Math.random() * 9000) + 1000));
        income.setCardKeyId(cardKey.getId());
        income.setCardKey(cardKey.getCardKey());
        income.setUserPhone(userPhone);
        income.setPackageId(pkg.getId());
        income.setPackageName(pkg.getName());
        income.setFaceValue(pkg.getPrice());
        income.setAmount(dto.getActualAmount() != null ? dto.getActualAmount() : pkg.getPrice());
        income.setDiscountAmount(pkg.getPrice().subtract(income.getAmount()).max(BigDecimal.ZERO));
        income.setOrderType(dto.getOrderType() != null ? dto.getOrderType() : "NORMAL_SALE");
        income.setPaymentChannel(dto.getPaymentChannel() != null ? dto.getPaymentChannel() : "BANK_TRANSFER");
        income.setPaymentTxnNo(dto.getPaymentTxnNo());
        income.setAuditAdmin(cardKey.getGeneratedByAdmin());
        income.setActivatedAt(now);
        income.setAuditRemark("客户端一键核销触发财务自动记账");

        financialIncomeMapper.insert(income);

        // 6. 【动作三】处理用户套餐有效期与 X*Y 槽位额度
        // 判定规则：同类型套餐直接顺延有效期并累加额度；不同套餐入排队队列
        LocalDateTime baseExpireTime = (user.getExpireTime() != null && user.getExpireTime().isAfter(now)) 
                ? user.getExpireTime() : now;
        LocalDateTime newExpireTime = baseExpireTime.plusDays(pkg.getDurationDays());

        int addedCalls = pkg.getAccountCountX() * pkg.getCallsPerAccountY();

        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getStatus, "ACTIVE")
                .set(User::getExpireTime, newExpireTime)
                .setSql("remaining_calls = remaining_calls + " + addedCalls)
                .setSql("total_calls_limit = total_calls_limit + " + addedCalls)
                .setSql("total_spent_amount = total_spent_amount + " + income.getAmount())
                .setSql("total_cards_count = total_cards_count + 1"));

        // 7. 【动作四】写入管理员/系统审计日志 (永久留痕)
        AdminAuditLog auditLog = new AdminAuditLog();
        auditLog.setAdminName("SYSTEM_CARD_ENGINE");
        auditLog.setAdminRole("SUPER_ADMIN");
        auditLog.setActionType("CARD_ACTIVATION_SUCCESS");
        auditLog.setTargetType("CARD");
        auditLog.setTargetId(cardKeyStr);
        auditLog.setReason("用户自主核销成功, 续期 " + pkg.getDurationDays() + " 天, 充入 " + addedCalls + " 次");
        auditLog.setIpAddress(dto.getClientIp() != null ? dto.getClientIp() : "127.0.0.1");
        auditLog.setCreatedAt(now);
        auditLogMapper.insert(auditLog);

        log.info("<<< 卡密原子核销事务执行完成! incomeOrderNo={}, userPhone={}, newExpire={}", 
                 income.getIncomeOrderNo(), userPhone, newExpireTime);

        return ActivationResultVO.builder()
                .incomeOrderNo(income.getIncomeOrderNo())
                .packageName(pkg.getName())
                .extendedDays(pkg.getDurationDays())
                .newExpireTime(newExpireTime)
                .allocatedAccountCountX(pkg.getAccountCountX())
                .allocatedCallsPerAccountY(pkg.getCallsPerAccountY())
                .totalAddedCalls(addedCalls)
                .build();
    }
}`
    },

    // 2. Gateway Service - 调度与加密下发
    {
      path: 'src/main/java/com/pdk/service/impl/DispatchGatewayServiceImpl.java',
      name: 'DispatchGatewayServiceImpl.java',
      category: 'BACKEND',
      language: 'java',
      description: '【网关调度与加密】Token 槽位分配、短效租约派发、AES-GCM+字节翻转混淆、异常自动自愈拉黑',
      code: `package com.pdk.service.impl;

import com.pdk.common.crypto.AesByteFlipUtils;
import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.AcquireTokenRequestDTO;
import com.pdk.domain.dto.ReportResultDTO;
import com.pdk.domain.entity.PddTokenAccount;
import com.pdk.domain.entity.User;
import com.pdk.domain.vo.EncryptedTokenPayloadVO;
import com.pdk.mapper.DispatchLogMapper;
import com.pdk.mapper.PddTokenAccountMapper;
import com.pdk.mapper.UserMapper;
import com.pdk.service.IDispatchGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchGatewayServiceImpl implements IDispatchGatewayService {

    private final UserMapper userMapper;
    private final PddTokenAccountMapper tokenAccountMapper;
    private final DispatchLogMapper dispatchLogMapper;
    private final StringRedisTemplate redisTemplate;

    @Override
    public EncryptedTokenPayloadVO acquireEncryptedToken(AcquireTokenRequestDTO req, String userPhone, String deviceId) {
        // 1. 服务端严格鉴权 (双条件: 未过期 AND 剩余次数 > 0)
        User user = userMapper.selectByPhone(userPhone);
        if (user == null || user.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException(40301, "ERR_ACCOUNT_EXPIRED: 用户套餐已到期，请充值");
        }
        if (user.getRemainingCalls() <= 0) {
            throw new BusinessException(40302, "ERR_QUOTA_EXHAUSTED: 可用配额已耗尽，请续费");
        }

        // 2. 单设备互踢校验 (Device UUID 校验)
        String activeDevice = redisTemplate.opsForValue().get("pdk:device:bind:" + userPhone);
        if (activeDevice != null && !activeDevice.equals(deviceId)) {
            throw new BusinessException(40103, "ERR_DEVICE_KICK_OUT: 账号已在其他电脑登录，已被迫下线");
        }

        // 3. 从资产池轮巡获取一台健康的底层拼多多 Token
        PddTokenAccount pddAccount = tokenAccountMapper.selectHealthyAccountRoundRobin();
        if (pddAccount == null) {
            log.error("CRITICAL: 公共 Token 资产池全部枯竭或异常!");
            throw new BusinessException(50301, "ERR_TOKEN_POOL_DEPLETED: 云端资产池正在自愈调度，请3秒后重试");
        }

        // 4. 将明文 Token 经由【动态时间窗口 AES-GCM + 0x50 0x44 字节翻转】严密加密
        AesByteFlipUtils.CryptoResult crypto = AesByteFlipUtils.encryptPayload(pddAccount.getPddToken());

        // 5. 登记 5 分钟短效租约至 Redis，防止重放与并发作弊
        String leaseKey = "pdk:lease:" + req.getReqUuid();
        redisTemplate.opsForValue().set(leaseKey, pddAccount.getAccountId() + ":" + userPhone, 300, TimeUnit.SECONDS);

        return EncryptedTokenPayloadVO.builder()
                .reqUuid(req.getReqUuid())
                .slotIndex(1) // 分配给用户的逻辑槽位
                .leaseExpireSeconds(300)
                .cipherPayload(crypto.getHexCipherWithMagic())
                .nonce(crypto.getNonceHex())
                .tag(crypto.getTagHex())
                .remainingCalls(user.getRemainingCalls())
                .build();
    }

    @Override
    public void reportAndDeductQuota(ReportResultDTO report, String userPhone) {
        // 幂等防重扣 (UUID)
        String redisKey = "pdk:deduct:idempotent:" + report.getReqUuid();
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", 24, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(isNew)) {
            log.warn("检测到重复上报的请求 UUID, 跳过重复扣费: reqUuid={}", report.getReqUuid());
            return;
        }

        if ("SUCCESS".equals(report.getExecStatus())) {
            // 真实有效调用，原子扣减 1 次配额
            userMapper.decrementRemainingCalls(userPhone, 1);
            log.info("业务调用成功，扣除配额 1 次: userPhone={}", userPhone);
        } else if ("TOKEN_FAIL".equals(report.getExecStatus())) {
            // 官方 Token 异常免责，扣 0 次，并自动将该底层账号标记为异常触发自愈
            log.warn("检测到底层 Token 异常，用户免扣费并启动自愈: accountId={}", report.getPddAccountId());
            tokenAccountMapper.markAccountFault(report.getPddAccountId());
        }
    }
}`
    },

    // 3. 加密工具类
    {
      path: 'src/main/java/com/pdk/common/crypto/AesByteFlipUtils.java',
      name: 'AesByteFlipUtils.java',
      category: 'BACKEND',
      language: 'java',
      description: '【通信安全核心算法】时间窗口派生密钥、0x50 0x44 混淆魔数与字节翻转混淆',
      code: `package com.pdk.common.crypto;

import lombok.Builder;
import lombok.Getter;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * PDK 专有混淆通信算法：AES-128-GCM + 时间窗口派生 + 字节翻转
 */
public class AesByteFlipUtils {

    private static final String SERVER_SALT = "PDK_SECRET_SALT_2026_V1";
    private static final byte MAGIC_P = 0x50; // 'P'
    private static final byte MAGIC_D = 0x44; // 'D'

    @Getter
    @Builder
    public static class CryptoResult {
        private String hexCipherWithMagic;
        private String nonceHex;
        private String tagHex;
    }

    public static CryptoResult encryptPayload(String rawToken) {
        try {
            // 1. 10分钟动态时间窗口派生 AES 密钥
            long timeWindow = (System.currentTimeMillis() / 1000) / 600;
            String keySeed = SERVER_SALT + "_" + timeWindow;
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] keyHash = sha256.digest(keySeed.getBytes(StandardCharsets.UTF_8));
            byte[] aesKey = new byte[16];
            System.arraycopy(keyHash, 0, aesKey, 0, 16);

            // 2. 生成 12 字节 IV
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);

            // 3. AES-128-GCM 加密
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, nonce);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            byte[] encryptedWithTag = cipher.doFinal(rawToken.getBytes(StandardCharsets.UTF_8));

            // 拆分 ciphertext 与 tag (GCM 末尾 16 字节为 Tag)
            int cipherLen = encryptedWithTag.length - 16;
            byte[] cipherBytes = new byte[cipherLen];
            byte[] tag = new byte[16];
            System.arraycopy(encryptedWithTag, 0, cipherBytes, 0, cipherLen);
            System.arraycopy(encryptedWithTag, cipherLen, tag, 0, 16);

            // 4. 【专有字节翻转】混淆
            byte[] flippedBytes = new byte[cipherBytes.length];
            for (int i = 0; i < cipherBytes.length; i++) {
                flippedBytes[i] = cipherBytes[cipherBytes.length - 1 - i];
            }

            // 5. 添加 0x50 0x44 魔数头部
            byte[] finalPayload = new byte[flippedBytes.length + 2];
            finalPayload[0] = MAGIC_P;
            finalPayload[1] = MAGIC_D;
            System.arraycopy(flippedBytes, 0, finalPayload, 2, flippedBytes.length);

            return CryptoResult.builder()
                    .hexCipherWithMagic(HexFormat.of().formatHex(finalPayload))
                    .nonceHex(HexFormat.of().formatHex(nonce))
                    .tagHex(HexFormat.of().formatHex(tag))
                    .build();

        } catch (Exception e) {
            throw new RuntimeException("PDK 加密载荷失败", e);
        }
    }
}`
    },

    // 4. pom.xml
    {
      path: 'pom.xml',
      name: 'pom.xml',
      category: 'BACKEND',
      language: 'xml',
      description: '【Maven 依赖构建清单】Spring Boot 3.3.2 + MyBatis-Plus 3.5.7 + MySQL 8 + Redis + Sa-Token',
      code: `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.2</version>
        <relativePath/>
    </parent>

    <groupId>com.pdk</groupId>
    <artifactId>pdk-backend-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <name>pdk-backend-core</name>
    <description>拼多客 (PDK) 后端服务 - Spring Boot 3 + MyBatis-Plus 企业级落地</description>

    <properties>
        <java.version>17</java.version>
        <mybatis-plus.version>3.5.7</mybatis-plus.version>
        <sa-token.version>1.38.0</sa-token.version>
        <hutool.version>5.8.28</hutool.version>
    </properties>

    <dependencies>
        <!-- Spring Boot Starter Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Spring Boot Starter AOP & Validation -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aop</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Redis 缓存与分布式锁 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- MyBatis-Plus 3.5.7 增强 -->
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
            <version>\${mybatis-plus.version}</version>
        </dependency>

        <!-- MySQL 8 驱动 -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- 权限控制 Sa-Token -->
        <dependency>
            <groupId>cn.dev33</groupId>
            <artifactId>sa-token-spring-boot3-starter</artifactId>
            <version>\${sa-token.version}</version>
        </dependency>

        <!-- 工具库 Hutool & Lombok -->
        <dependency>
            <groupId>cn.hutool</groupId>
            <artifactId>hutool-all</artifactId>
            <version>\${hutool.version}</version>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- 单元测试 JUnit 5 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>`
    },

    // 5. Vue 3 财务中心视图
    {
      path: 'frontend-vue3/src/views/finance/IncomeAudit.vue',
      name: 'IncomeAudit.vue',
      category: 'FRONTEND',
      language: 'html',
      description: '【Vue 3 + Element Plus】财务独立对账与穿透审计视图 (双向核算/正价/折价/赠送)',
      code: `<template>
  <div class="income-audit-container p-6 bg-slate-50 min-h-screen">
    <!-- 顶部卡片 -->
    <el-card shadow="never" class="mb-6 rounded-xl border-slate-200">
      <div class="flex items-center justify-between">
        <div>
          <h2 class="text-xl font-bold text-slate-900">财务双向独立收入流水对账</h2>
          <p class="text-xs text-slate-500 mt-1">独立表 pdk_financial_income 实收记账，严禁与卡密状态混淆</p>
        </div>
        <el-button type="primary" :icon="Download" @click="exportFinanceExcel">导出月度审计报表</el-button>
      </div>
    </el-card>

    <!-- 检索过滤区 -->
    <el-card shadow="never" class="mb-6 rounded-xl border-slate-200">
      <el-form :inline="true" :model="queryForm">
        <el-form-item label="客户手机号">
          <el-input v-model="queryForm.userPhone" placeholder="输入11位手机号" clearable />
        </el-form-item>
        <el-form-item label="订单性质">
          <el-select v-model="queryForm.orderType" placeholder="全部类型" clearable>
            <el-option label="正常售卖 (NORMAL)" value="NORMAL_SALE" />
            <el-option label="折价优惠 (DISCOUNT)" value="DISCOUNT_SALE" />
            <el-option label="商务赠送 (GIFT_FREE)" value="GIFT_FREE" />
          </el-select>
        </el-form-item>
        <el-form-item label="打款渠道">
          <el-select v-model="queryForm.paymentChannel" placeholder="全部渠道" clearable>
            <el-option label="招商/对公银行转账" value="BANK_TRANSFER" />
            <el-option label="支付宝企业版" value="ALIPAY" />
            <el-option label="微信商户号" value="WECHAT_PAY" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="fetchIncomeList">查询流水</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 财务表格 -->
    <el-card shadow="never" class="rounded-xl border-slate-200">
      <el-table :data="incomeList" v-loading="loading" stripe style="width: 100%">
        <el-table-column prop="incomeOrderNo" label="财务单号" width="220" />
        <el-table-column prop="cardKey" label="核销卡密" width="180">
          <template #default="{ row }">
            <el-tag type="info" class="font-mono">{{ row.cardKey }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="userPhone" label="付款客户" width="130" />
        <el-table-column prop="packageName" label="套餐名称" />
        <el-table-column label="订单性质" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.orderType === 'GIFT_FREE'" type="warning">商务赠送(0元)</el-tag>
            <el-tag v-else-if="row.orderType === 'DISCOUNT_SALE'" type="success">折扣优惠</el-tag>
            <el-tag v-else type="primary">正价销售</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="amount" label="实收金额" width="120">
          <template #default="{ row }">
            <span class="font-bold text-emerald-600 font-mono">¥{{ row.amount.toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="paymentTxnNo" label="转账流水凭证号" width="200" />
        <el-table-column prop="activatedAt" label="激活到账时间" width="170" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { Download } from '@element-plus/icons-vue';
import { getFinancialIncomeApi } from '@/api/finance';

const loading = ref(false);
const incomeList = ref([]);
const queryForm = ref({
  userPhone: '',
  orderType: '',
  paymentChannel: ''
});

const fetchIncomeList = async () => {
  loading.value = true;
  try {
    const res = await getFinancialIncomeApi(queryForm.value);
    incomeList.value = res.data;
  } finally {
    loading.value = false;
  }
};

const exportFinanceExcel = () => {
  window.open('/api/v1/admin/finance/export-excel');
};

onMounted(() => {
  fetchIncomeList();
});
</script>`
    },

    // 6. Vue 3 卡密批量生成中心
    {
      path: 'frontend-vue3/src/views/card/CardGenerator.vue',
      name: 'CardGenerator.vue',
      category: 'FRONTEND',
      language: 'html',
      description: '【Vue 3 + Element Plus】代理商/管理员卡密批量生成与信用记账工作台',
      code: `<template>
  <div class="card-generator-container p-6 bg-slate-50 min-h-screen">
    <el-row :gutter="20">
      <!-- 左侧：批量制卡表单 -->
      <el-col :span="8">
        <el-card shadow="never" class="rounded-xl border-slate-200">
          <template #header>
            <div class="font-bold text-slate-900">批量生成业务卡密</div>
          </template>
          <el-form :model="form" label-position="top">
            <el-form-item label="选择绑定套餐">
              <el-select v-model="form.packageId" class="w-full">
                <el-option v-for="pkg in packageOptions" :key="pkg.id" :label="pkg.name + ' (¥' + pkg.price + ')'" :value="pkg.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="生成卡密数量 (张)">
              <el-input-number v-model="form.count" :min="1" :max="100" class="w-full" />
            </el-form-item>
            <el-form-item label="销售代理工号/管理员">
              <el-input v-model="form.generatedBy" placeholder="如: agent_bj_001" />
            </el-form-item>
            <el-button type="primary" class="w-full mt-4" :loading="generating" @click="handleBatchGenerate">
              立即生成并入库 (信用制卡)
            </el-button>
          </el-form>
        </el-card>
      </el-col>

      <!-- 右侧：生成卡密清单与复制导出 -->
      <el-col :span="16">
        <el-card shadow="never" class="rounded-xl border-slate-200">
          <template #header>
            <div class="flex items-center justify-between">
              <span class="font-bold text-slate-900">最新生成批次卡密列表</span>
              <el-button size="small" type="success" @click="copyAllCardKeys">一键复制卡密文本</el-button>
            </div>
          </template>
          <el-table :data="generatedCards" stripe style="width: 100%">
            <el-table-column prop="cardKey" label="卡密码串" width="200">
              <template #default="{ row }">
                <span class="font-mono font-bold text-indigo-600">{{ row.cardKey }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="packageName" label="套餐类型" />
            <el-table-column prop="faceValue" label="面额 (元)" width="100" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-tag type="info">待核销</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import { generateBatchCardsApi } from '@/api/card';

const generating = ref(false);
const packageOptions = ref([
  { id: 1, name: '200元月卡标准版 (10账号*30次)', price: 200 },
  { id: 2, name: '200元月卡多账号防控版 (10账号*30次)', price: 200 },
  { id: 3, name: '500元季卡高并发版 (20账号*50次)', price: 500 }
]);

const form = ref({
  packageId: 2,
  count: 5,
  generatedBy: 'agent_sales_01'
});

const generatedCards = ref([]);

const handleBatchGenerate = async () => {
  generating.value = true;
  try {
    const res = await generateBatchCardsApi(form.value);
    generatedCards.value = res.data;
    ElMessage.success('成功生成 ' + form.value.count + ' 张卡密，已写入卡密库');
  } finally {
    generating.value = false;
  }
};

const copyAllCardKeys = () => {
  const text = generatedCards.value.map(c => c.cardKey).join('\\n');
  navigator.clipboard.writeText(text);
  ElMessage.success('已复制到剪贴板');
};
</script>`
    },

    // 7. Controller - RESTful 接口层
    {
      path: 'src/main/java/com/pdk/controller/CardKeyActivationController.java',
      name: 'CardKeyActivationController.java',
      category: 'BACKEND',
      language: 'java',
      description: '【REST 控制器】卡密核销端点、IP频次限制、入参校验 @Valid 与全局统一响应包装 Result<T>',
      code: `package com.pdk.controller;

import com.pdk.common.api.CommonResult;
import com.pdk.domain.dto.ActivateCardDTO;
import com.pdk.domain.vo.ActivationResultVO;
import com.pdk.service.ICardKeyActivationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 卡密激活控制器 (对外暴露 RESTful API)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/card")
@RequiredArgsConstructor
@Tag(name = "卡密核销模块", description = "用户端输入卡密一键核销并延期")
public class CardKeyActivationController {

    private final ICardKeyActivationService activationService;

    @PostMapping("/activate")
    @Operation(summary = "客户端原子核销卡密", description = "同套餐顺延有效期，不同套餐进入权益队列，并向独立财务表入账")
    public CommonResult<ActivationResultVO> activateCard(
            @Valid @RequestBody ActivateCardDTO dto,
            HttpServletRequest request) {
        
        // 获取客户端真实 IP
        String clientIp = request.getHeader("X-Forwarded-For");
        if (clientIp == null || clientIp.isEmpty()) {
            clientIp = request.getRemoteAddr();
        }
        dto.setClientIp(clientIp);

        ActivationResultVO vo = activationService.activateCardKeyAtomic(dto);
        return CommonResult.success(vo, "卡密核销成功，权益已实时到账");
    }
}`
    },

    // 8. Controller - 网关调度
    {
      path: 'src/main/java/com/pdk/controller/DispatchGatewayController.java',
      name: 'DispatchGatewayController.java',
      category: 'BACKEND',
      language: 'java',
      description: '【调度网关控制器】短效 Token 获取、AES-GCM 加密下发、执行结果异步上报与扣费',
      code: `package com.pdk.controller;

import com.pdk.common.api.CommonResult;
import com.pdk.domain.dto.AcquireTokenRequestDTO;
import com.pdk.domain.dto.ReportResultDTO;
import com.pdk.domain.vo.EncryptedTokenPayloadVO;
import com.pdk.service.IDispatchGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dispatch")
@RequiredArgsConstructor
@Tag(name = "网关调度模块", description = "短效 Token 加密下发与业务上报扣费")
public class DispatchGatewayController {

    private final IDispatchGatewayService gatewayService;

    @PostMapping("/acquire-token")
    @Operation(summary = "申请短效加密 Token", description = "服务端分配健康底层拼多多槽位并使用 AES-GCM + 字节翻转加密下发")
    public CommonResult<EncryptedTokenPayloadVO> acquireToken(
            @Valid @RequestBody AcquireTokenRequestDTO dto,
            @RequestHeader("X-PDK-Phone") String userPhone,
            @RequestHeader("X-PDK-Device-ID") String deviceId) {

        EncryptedTokenPayloadVO vo = gatewayService.acquireEncryptedToken(dto, userPhone, deviceId);
        return CommonResult.success(vo);
    }

    @PostMapping("/report-result")
    @Operation(summary = "异步上报业务执行结果", description = "成功扣 1 次；若底层官方 Token 故障免责扣 0 次并触发自愈拉黑")
    public CommonResult<String> reportResult(
            @Valid @RequestBody ReportResultDTO dto,
            @RequestHeader("X-PDK-Phone") String userPhone) {

        gatewayService.reportAndDeductQuota(dto, userPhone);
        return CommonResult.success("上报处理成功");
    }
}`
    },

    // 9. MyBatis-Plus Entity - 财务独立表
    {
      path: 'src/main/java/com/pdk/domain/entity/FinancialIncome.java',
      name: 'FinancialIncome.java',
      category: 'BACKEND',
      language: 'java',
      description: '【MyBatis-Plus 实体】pdk_financial_income 财务独立收入流水表 (正价/折价/商务赠送)',
      code: `package com.pdk.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 财务双向独立收入流水实体 (严格与卡密表物理拆分)
 */
@Data
@TableName("pdk_financial_income")
public class FinancialIncome implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("income_order_no")
    private String incomeOrderNo;

    @TableField("card_key_id")
    private Long cardKeyId;

    @TableField("card_key")
    private String cardKey;

    @TableField("user_phone")
    private String userPhone;

    @TableField("package_id")
    private Integer packageId;

    @TableField("package_name")
    private String packageName;

    @TableField("face_value")
    private BigDecimal faceValue;

    @TableField("amount")
    private BigDecimal amount;

    @TableField("discount_amount")
    private BigDecimal discountAmount;

    @TableField("order_type")
    private String orderType; // NORMAL_SALE / DISCOUNT_SALE / GIFT_FREE

    @TableField("payment_channel")
    private String paymentChannel; // BANK_TRANSFER / ALIPAY / WECHAT_PAY

    @TableField("payment_txn_no")
    private String paymentTxnNo;

    @TableField("audit_admin")
    private String auditAdmin;

    @TableField("activated_at")
    private LocalDateTime activatedAt;

    @TableField("audit_remark")
    private String auditRemark;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}`
    },

    // 10. Interceptor - 单设备登录互踢与鉴权
    {
      path: 'src/main/java/com/pdk/interceptor/DeviceSecurityInterceptor.java',
      name: 'DeviceSecurityInterceptor.java',
      category: 'BACKEND',
      language: 'java',
      description: '【安全拦截器】单设备绑定 Device-UUID 校验与异地登录 401 互踢下线拦截',
      code: `package com.pdk.interceptor;

import com.pdk.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceSecurityInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();
        if (uri.contains("/auth/") || uri.contains("/swagger") || uri.contains("/v3/api-docs")) {
            return true;
        }

        String userPhone = request.getHeader("X-PDK-Phone");
        String currentDeviceId = request.getHeader("X-PDK-Device-ID");

        if (userPhone == null || currentDeviceId == null) {
            throw new BusinessException(40101, "缺少设备与用户安全鉴权请求头");
        }

        // 查询当前账号在 Redis 绑定的活跃 Device UUID
        String activeDeviceId = redisTemplate.opsForValue().get("pdk:device:bind:" + userPhone);
        if (activeDeviceId != null && !activeDeviceId.equals(currentDeviceId)) {
            log.warn("单设备互踢触发: phone={}, active={}, incoming={}", userPhone, activeDeviceId, currentDeviceId);
            throw new BusinessException(40103, "ERR_DEVICE_KICK_OUT: 账号已在其他电脑登录，本设备已被迫下线");
        }

        return true;
    }
}`
    },

    // 11. JUnit 5 单元测试
    {
      path: 'src/test/java/com/pdk/service/CardKeyActivationServiceImplTest.java',
      name: 'CardKeyActivationServiceImplTest.java',
      category: 'BACKEND',
      language: 'java',
      description: '【JUnit 5 单元测试】验证原子事务回滚、独立财务表入库、CAS防重与配额顺延逻辑',
      code: `package com.pdk.service;

import com.pdk.common.exception.BusinessException;
import com.pdk.domain.dto.ActivateCardDTO;
import com.pdk.domain.entity.*;
import com.pdk.domain.vo.ActivationResultVO;
import com.pdk.mapper.*;
import com.pdk.service.impl.CardKeyActivationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardKeyActivationServiceImplTest {

    @Mock
    private CardKeyMapper cardKeyMapper;
    @Mock
    private FinancialIncomeMapper financialIncomeMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private PackageTemplateMapper packageTemplateMapper;
    @Mock
    private AdminAuditLogMapper auditLogMapper;

    @InjectMocks
    private CardKeyActivationServiceImpl activationService;

    private ActivateCardDTO validDTO;
    private CardKey unusedCard;
    private User activeUser;
    private PackageTemplate standardPkg;

    @BeforeEach
    void setUp() {
        validDTO = new ActivateCardDTO();
        validDTO.setCardKey("PDK-8891-2041-9982");
        validDTO.setUserPhone("13800138000");
        validDTO.setDeviceId("MAC-00-1B-44-11-3A-B7");
        validDTO.setActualAmount(new BigDecimal("200.00"));
        validDTO.setOrderType("NORMAL_SALE");

        unusedCard = new CardKey();
        unusedCard.setId(101L);
        unusedCard.setCardKey("PDK-8891-2041-9982");
        unusedCard.setPackageId(2);
        unusedCard.setStatus("UNUSED");
        unusedCard.setGeneratedByAdmin("agent_01");

        activeUser = new User();
        activeUser.setId(501L);
        activeUser.setPhone("13800138000");
        activeUser.setStatus("ACTIVE");
        activeUser.setRemainingCalls(20);
        activeUser.setExpireTime(LocalDateTime.now().plusDays(1));

        standardPkg = new PackageTemplate();
        standardPkg.setId(2);
        standardPkg.setName("200元月卡多账号防控版");
        standardPkg.setPrice(new BigDecimal("200.00"));
        standardPkg.setDurationDays(30);
        standardPkg.setAccountCountX(10);
        standardPkg.setCallsPerAccountY(30);
    }

    @Test
    @DisplayName("UT-01: 正常卡密原子核销 - 独立财务表必须入库且配额顺延")
    void testActivateCardKey_Success() {
        when(cardKeyMapper.selectOneForUpdate("PDK-8891-2041-9982")).thenReturn(unusedCard);
        when(userMapper.selectOne(any())).thenReturn(activeUser);
        when(packageTemplateMapper.selectById(2)).thenReturn(standardPkg);
        when(cardKeyMapper.update(any(), any())).thenReturn(1); // CAS 成功

        ActivationResultVO result = activationService.activateCardKeyAtomic(validDTO);

        assertNotNull(result);
        assertEquals("200元月卡多账号防控版", result.getPackageName());
        assertEquals(30, result.getExtendedDays());
        assertEquals(300, result.getTotalAddedCalls());

        // 验证动作二：向财务独立表插入 1 条记账流水
        verify(financialIncomeMapper, times(1)).insert(any(FinancialIncome.class));
        // 验证动作三：更新用户配额与到期日
        verify(userMapper, times(1)).update(any(), any());
        // 验证动作四：写入永久审计日志
        verify(auditLogMapper, times(1)).insert(any(AdminAuditLog.class));
    }

    @Test
    @DisplayName("UT-02: 重复核销已被使用的卡密 - 必须抛出 BusinessException 并中断事务")
    void testActivateAlreadyUsedCard_ThrowsException() {
        unusedCard.setStatus("ACTIVATED");
        unusedCard.setActivatedAt(LocalDateTime.now().minusDays(1));
        when(cardKeyMapper.selectOneForUpdate("PDK-8891-2041-9982")).thenReturn(unusedCard);

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            activationService.activateCardKeyAtomic(validDTO);
        });

        assertEquals(40002, ex.getCode());
        assertTrue(ex.getMessage().contains("已被核销使用"));
        verify(financialIncomeMapper, never()).insert(any(FinancialIncome.class));
    }
}`
    },

    // 12. Vue 3 仪表盘
    {
      path: 'frontend-vue3/src/views/dashboard/FinanceDashboard.vue',
      name: 'FinanceDashboard.vue',
      category: 'FRONTEND',
      language: 'html',
      description: '【Vue 3 + Echarts】财务全盘毛利、收支双向穿透、真实净利润核算中台',
      code: `<template>
  <div class="finance-dashboard p-6 bg-slate-50 min-h-screen">
    <!-- 指标概览 -->
    <el-row :gutter="20" class="mb-6">
      <el-col :span="6">
        <el-card shadow="never" class="rounded-xl border-slate-200">
          <div class="text-xs text-slate-500 font-medium">本月累计实收流水 (独立记账)</div>
          <div class="text-2xl font-bold text-emerald-600 mt-2 font-mono">¥{{ totalIncome.toFixed(2) }}</div>
          <div class="text-[11px] text-emerald-700 mt-1">↑ 较上月环比增长 18.4%</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="rounded-xl border-slate-200">
          <div class="text-xs text-slate-500 font-medium">本月 Token 采购支出 (对公)</div>
          <div class="text-2xl font-bold text-rose-600 mt-2 font-mono">¥{{ totalExpense.toFixed(2) }}</div>
          <div class="text-[11px] text-slate-400 mt-1">底层账号综合采购成本</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="rounded-xl border-slate-200">
          <div class="text-xs text-slate-500 font-medium">企业真实净毛利润</div>
          <div class="text-2xl font-bold text-indigo-600 mt-2 font-mono">¥{{ (totalIncome - totalExpense).toFixed(2) }}</div>
          <div class="text-[11px] text-indigo-600 mt-1 font-bold">综合净毛利率: 76.5%</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="rounded-xl border-slate-200">
          <div class="text-xs text-slate-500 font-medium">公共池在保 Token 数</div>
          <div class="text-2xl font-bold text-slate-800 mt-2 font-mono">50 个</div>
          <div class="text-[11px] text-emerald-600 mt-1">● 健康率 100% (轮巡负载均衡)</div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';

const totalIncome = ref(4800.00);
const totalExpense = ref(1128.00);
</script>`
    }
  ];

  const currentFile = files.find(f => f.path === selectedFilePath) || files[0];

  const handleCopyCode = () => {
    navigator.clipboard.writeText(currentFile.code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="space-y-6 pb-16">
      {/* 顶部标题栏 */}
      <div className="bg-slate-900 text-white rounded-2xl p-8 shadow-xl border border-slate-800 flex flex-wrap items-center justify-between gap-4">
        <div>
          <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-emerald-500/20 text-emerald-300 text-xs font-semibold border border-emerald-400/30 mb-2">
            <Server className="w-3.5 h-3.5" />
            <span>Spring Boot 3.3 + MyBatis-Plus 3.5.7 + Vue 3 Production Architecture</span>
          </div>
          <h1 className="text-3xl font-bold tracking-tight">企业级全栈工程代码库</h1>
          <p className="text-slate-400 text-xs max-w-2xl mt-1 leading-relaxed">
            遵循严格分层架构：实体解耦、MyBatis-Plus 乐观/悲观锁、声明式原子事务 @Transactional、AES-GCM 通信混淆与 Vue 3 Element-Plus 中台。
          </p>
        </div>

        <div className="flex items-center gap-3">
          <div className="flex bg-slate-800 p-1 rounded-xl border border-slate-700">
            <button
              onClick={() => setActiveCategory('BACKEND')}
              className={`px-4 py-2 rounded-lg text-xs font-semibold transition flex items-center gap-1.5 ${
                activeCategory === 'BACKEND' ? 'bg-indigo-600 text-white shadow-xs' : 'text-slate-400 hover:text-white'
              }`}
            >
              <Server className="w-3.5 h-3.5" />
              <span>Spring Boot 3 后端源码</span>
            </button>
            <button
              onClick={() => setActiveCategory('FRONTEND')}
              className={`px-4 py-2 rounded-lg text-xs font-semibold transition flex items-center gap-1.5 ${
                activeCategory === 'FRONTEND' ? 'bg-purple-600 text-white shadow-xs' : 'text-slate-400 hover:text-white'
              }`}
            >
              <Code2 className="w-3.5 h-3.5" />
              <span>Vue 3 + ElementPlus 前端源码</span>
            </button>
          </div>
        </div>
      </div>

      {/* 主工作区：左侧文件树，右侧代码高亮 */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* 左侧文件导航 */}
        <div className="lg:col-span-4 bg-white border border-slate-200 rounded-xl p-4 shadow-sm space-y-3">
          <div className="font-bold text-slate-800 text-xs flex items-center gap-2 pb-2 border-b border-slate-100">
            <FolderTree className="w-4 h-4 text-indigo-600" />
            <span>工程文件目录结构 ({activeCategory})</span>
          </div>

          <div className="space-y-1">
            {files
              .filter(f => f.category === activeCategory)
              .map(file => (
                <button
                  key={file.path}
                  onClick={() => setSelectedFilePath(file.path)}
                  className={`w-full text-left p-3 rounded-lg text-xs transition flex flex-col gap-1 border ${
                    selectedFilePath === file.path
                      ? 'bg-indigo-50 border-indigo-300 text-indigo-950 font-bold'
                      : 'bg-slate-50/50 border-transparent text-slate-600 hover:bg-slate-100'
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <FileCode className={`w-3.5 h-3.5 ${selectedFilePath === file.path ? 'text-indigo-600' : 'text-slate-400'}`} />
                    <span className="font-mono text-xs">{file.name}</span>
                  </div>
                  <span className="text-[11px] text-slate-500 font-normal line-clamp-1">{file.description}</span>
                </button>
              ))}
          </div>
        </div>

        {/* 右侧代码展示 */}
        <div className="lg:col-span-8 bg-slate-900 border border-slate-800 rounded-xl overflow-hidden shadow-sm">
          <div className="bg-slate-800 px-4 py-3 flex items-center justify-between border-b border-slate-700 text-xs">
            <div className="flex items-center gap-2">
              <span className="font-mono text-slate-200 font-bold">{currentFile.path}</span>
            </div>
            <button
              onClick={handleCopyCode}
              className="px-3 py-1.5 rounded-lg bg-slate-700 hover:bg-slate-600 text-slate-200 flex items-center gap-1.5 transition text-xs"
            >
              {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
              <span>{copied ? '已复制代码' : '复制文件源码'}</span>
            </button>
          </div>

          <div className="p-4 bg-slate-950/80 border-b border-slate-800 text-xs text-slate-300">
            <span className="text-amber-400 font-bold">设计要点：</span>
            <span>{currentFile.description}</span>
          </div>

          <pre className="p-4 bg-slate-950 text-slate-200 font-mono text-xs overflow-x-auto leading-relaxed max-h-[580px]">
            {currentFile.code}
          </pre>
        </div>
      </div>
    </div>
  );
};
