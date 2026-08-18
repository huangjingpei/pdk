
CREATE DATABASE IF NOT EXISTS `pdk_biz_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `pdk_biz_db`;

-- ==========================================================
-- 拼多多云控商业化体系 - 生产级 DDL 数据表结构
-- ==========================================================


-- 1. 用户主表
CREATE TABLE IF NOT EXISTS `pdk_user` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户主键ID',
    `phone` VARCHAR(20) NOT NULL UNIQUE COMMENT '手机号 (唯一主键标识)',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE, TRIAL, FROZEN',
    `device_id` VARCHAR(128) DEFAULT NULL COMMENT '当前绑定的单机物理设备UUID',
    `current_package_id` INT DEFAULT NULL COMMENT '当前生效的套餐模版ID',
    `current_package_name` VARCHAR(64) DEFAULT NULL COMMENT '套餐名称',
    `expire_time` DATETIME DEFAULT NULL COMMENT '套餐到期时间',
    `remaining_calls` INT NOT NULL DEFAULT 0 COMMENT '剩余可用调用总次数',
    `daily_calls_limit` INT NOT NULL DEFAULT 0 COMMENT '每日调用上限限制',
    `max_accounts` INT NOT NULL DEFAULT 1 COMMENT '允许并发挂载的买家/店铺账号上限',
    `is_trial_claimed` TINYINT NOT NULL DEFAULT 0 COMMENT '是否已领取过1天20次新人试用(0:否, 1:是)',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户主表';

-- 2. 卡密凭证表 (纯生命周期与卡密状态)
CREATE TABLE IF NOT EXISTS `pdk_card_key` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '卡密ID',
    `card_key` VARCHAR(64) NOT NULL UNIQUE COMMENT '卡密序列号 (PDK-XXXX-XXXX-XXXX)',
    `package_id` INT NOT NULL COMMENT '绑定套餐模版ID',
    `status` VARCHAR(20) NOT NULL DEFAULT 'UNUSED' COMMENT '状态: UNUSED(待售), ACTIVATED(已激活), VOID(作废)',
    `generated_by_admin` VARCHAR(64) NOT NULL COMMENT '制卡管理员或代理商账号',
    `agent_id` BIGINT DEFAULT NULL COMMENT '所属代理商ID',
    `activated_by_phone` VARCHAR(20) DEFAULT NULL COMMENT '激活绑定的用户手机号',
    `activated_at` DATETIME DEFAULT NULL COMMENT '激活核销时间',
    `activated_device_id` VARCHAR(128) DEFAULT NULL COMMENT '核销时绑定的设备UUID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '制卡时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='卡密凭证表';

-- 3. 财务独立实收流水表 (与卡密物理拆分)
CREATE TABLE IF NOT EXISTS `pdk_financial_income` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '流水ID',
    `income_order_no` VARCHAR(64) NOT NULL UNIQUE COMMENT '收入对账流水号',
    `card_key_id` BIGINT NOT NULL COMMENT '关联卡密ID',
    `card_key` VARCHAR(64) NOT NULL COMMENT '卡密序列号',
    `user_phone` VARCHAR(20) NOT NULL COMMENT '付费充值手机号',
    `package_id` INT NOT NULL COMMENT '套餐模版ID',
    `package_name` VARCHAR(64) NOT NULL COMMENT '套餐名称',
    `face_value` DECIMAL(10,2) NOT NULL COMMENT '官方标价面值',
    `amount` DECIMAL(10,2) NOT NULL COMMENT '实际记账收入金额',
    `discount_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '优惠让利金额',
    `order_type` VARCHAR(30) NOT NULL COMMENT '类型: NORMAL_SALE(正价售卖), DISCOUNT_SALE(折价销售), GIFT_FREE(商务赠送)',
    `payment_channel` VARCHAR(30) NOT NULL COMMENT '支付通道: ALIPAY, WECHAT_PAY, BANK_TRANSFER, OFFLINE',
    `payment_txn_no` VARCHAR(128) DEFAULT NULL COMMENT '外部第三方支付流水号',
    `audit_admin` VARCHAR(64) NOT NULL COMMENT '审核/制卡操作人',
    `activated_at` DATETIME NOT NULL COMMENT '核销记账时间',
    `audit_remark` VARCHAR(255) DEFAULT NULL COMMENT '记账备注说明',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记账创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='财务独立收入流水表';

-- 4. 财务对公采购支出表 (Token 进货成本)
CREATE TABLE IF NOT EXISTS `pdk_company_expense` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '支出ID',
    `expense_order_no` VARCHAR(64) NOT NULL UNIQUE COMMENT '支出对账流水号',
    `category` VARCHAR(64) NOT NULL COMMENT '支出类目: TOKEN_PURCHASE, SERVER_PROXY, SMS_GATEWAY',
    `token_batch_id` VARCHAR(64) DEFAULT NULL COMMENT '采购 Token 批次号',
    `token_count` INT NOT NULL DEFAULT 0 COMMENT '采购 Token 数量',
    `supplier_name` VARCHAR(128) NOT NULL COMMENT '供应商名称',
    `unit_cost` DECIMAL(10,2) NOT NULL COMMENT '采购单价 (元/个)',
    `total_cost` DECIMAL(10,2) NOT NULL COMMENT '支出总金额',
    `invoice_url` VARCHAR(255) DEFAULT NULL COMMENT '发票凭证链接',
    `purchaser` VARCHAR(64) NOT NULL COMMENT '经办采购人',
    `purchased_at` DATETIME NOT NULL COMMENT '采购发生时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业对公采购支出表';

-- 5. 拼多多官方底层 Token 公共调度池
CREATE TABLE IF NOT EXISTS `pdk_token_pool` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `token_val` VARCHAR(512) NOT NULL COMMENT '拼多多官方底层 Session Token',
    `account_alias` VARCHAR(64) NOT NULL COMMENT '底层账号备注/别名',
    `health_status` VARCHAR(20) NOT NULL DEFAULT 'HEALTHY' COMMENT '状态: HEALTHY(健康), BUSY(占用), FAULT_BLACK(故障拉黑), EXPIRED(过期)',
    `daily_calls_count` INT NOT NULL DEFAULT 0 COMMENT '今日已调度调用次数',
    `daily_max_capacity` INT NOT NULL DEFAULT 500 COMMENT '单账号每日建议安全调用阈值',
    `risk_score` INT NOT NULL DEFAULT 0 COMMENT '风控危险分 (0-100)',
    `lease_client_phone` VARCHAR(20) DEFAULT NULL COMMENT '当前租借客户端手机号',
    `leased_at` DATETIME DEFAULT NULL COMMENT '租借时间',
    `last_fault_time` DATETIME DEFAULT NULL COMMENT '最近一次报错故障时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '录入时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拼多多官方 Token 公共调度池';

-- 6. 套餐模版表
CREATE TABLE IF NOT EXISTS `pdk_package_template` (
    `id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '套餐ID',
    `name` VARCHAR(64) NOT NULL COMMENT '套餐名称',
    `price` DECIMAL(10,2) NOT NULL COMMENT '官方标价',
    `duration_days` INT NOT NULL COMMENT '有效天数',
    `account_count_x` INT NOT NULL COMMENT '允许买家账号挂载数 X',
    `calls_per_account_y` INT NOT NULL COMMENT '单账号日调用配额 Y',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '套餐说明',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE, INACTIVE'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='套餐模版表';

-- 7. 业务调用与扣费流水日志表 (不可物理删除)
CREATE TABLE IF NOT EXISTS `pdk_dispatch_log` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '日志ID',
    `req_uuid` VARCHAR(64) NOT NULL UNIQUE COMMENT '客户端请求幂等唯一UUID (防重试重复扣费)',
    `user_phone` VARCHAR(20) NOT NULL COMMENT '调用用户手机号',
    `slot_index` INT NOT NULL DEFAULT 1 COMMENT '消耗的逻辑账号槽位 (1~X)',
    `real_pdd_account_id` VARCHAR(64) NOT NULL COMMENT '实际承载调度的底层公司账号ID',
    `action_type` VARCHAR(64) NOT NULL COMMENT '业务操作类型 (如: QUERY_ORDER, GET_GOODS)',
    `deduct_count` TINYINT NOT NULL DEFAULT 1 COMMENT '本次扣减次数 (成功扣1, 账号异常/免责扣0)',
    `exec_status` VARCHAR(30) NOT NULL COMMENT '执行状态: SUCCESS, TOKEN_FAIL, PARAM_ERROR, NET_TIMEOUT, FAULT_HEALED',
    `response_time_ms` INT NOT NULL COMMENT '网关处理耗时 (ms)',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调度发生时间',
    INDEX `idx_user_time` (`user_phone`, `created_at`),
    INDEX `idx_account_stat` (`real_pdd_account_id`, `exec_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务调度与扣费明细表';

-- 8. 管理员核心操作不可逆审计日志表
CREATE TABLE IF NOT EXISTS `pdk_admin_audit_log` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '审计日志ID',
    `admin_name` VARCHAR(32) NOT NULL COMMENT '操作管理员账号',
    `admin_role` VARCHAR(32) NOT NULL COMMENT '管理员角色: SUPER_ADMIN, FINANCE, AGENT',
    `action_type` VARCHAR(64) NOT NULL COMMENT '操作类型 (MANUAL_ADJUST_QUOTA, EXTEND_EXPIRE, VOID_CARD, BLOCK_USER, GENERATE_CARD)',
    `target_type` VARCHAR(32) NOT NULL COMMENT '目标对象类型 (USER, CARD, ACCOUNT, PACKAGE)',
    `target_id` VARCHAR(64) NOT NULL COMMENT '目标对象标识 (如手机号或卡密码)',
    `before_state` TEXT DEFAULT NULL COMMENT '修改前状态快照 JSON',
    `after_state` TEXT DEFAULT NULL COMMENT '修改后状态快照 JSON',
    `reason` VARCHAR(255) NOT NULL COMMENT '人工操作必须填写的原因备注',
    `ip_address` VARCHAR(45) NOT NULL DEFAULT '127.0.0.1' COMMENT '操作人客户端IP',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '审计记录时间',
    INDEX `idx_admin_created` (`admin_name`, `created_at`),
    INDEX `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员不可逆审计操作日志表';

-- 9. 管理后台账号表（权限由角色映射，密码仅保存带 pepper 的 SHA-256 摘要）
CREATE TABLE IF NOT EXISTS `pdk_admin_user` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '管理员ID',
    `username` VARCHAR(64) NOT NULL UNIQUE COMMENT '登录账号',
    `password_hash` CHAR(64) NOT NULL COMMENT '密码摘要',
    `display_name` VARCHAR(64) NOT NULL COMMENT '显示名称',
    `role_code` VARCHAR(32) NOT NULL COMMENT 'SUPER_ADMIN, OPERATIONS, FINANCE, AGENT, SUPPORT',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, DISABLED',
    `last_login_at` DATETIME DEFAULT NULL COMMENT '最后登录时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理后台多角色账号表';

-- 初始化套餐数据
INSERT INTO `pdk_package_template` (`id`, `name`, `price`, `duration_days`, `account_count_x`, `calls_per_account_y`, `description`) VALUES
(1, '20元天卡（高频体验版）', 20.00, 1, 1, 50, '单日50次调用，单机绑定'),
(2, '200元月卡（多账号防控版）', 200.00, 30, 10, 30, '10个买家号并发挂载，单号日限30次'),
(3, '500元季卡（高并发工作室版）', 500.00, 90, 30, 50, '30个买家号并发挂载，单号日限50次'),
(4, '1500元年卡（旗舰企业尊享版）', 1500.00, 365, 100, 100, '100个买家号并发挂载，单号日限100次')
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `price` = VALUES(`price`),
    `duration_days` = VALUES(`duration_days`),
    `account_count_x` = VALUES(`account_count_x`),
    `calls_per_account_y` = VALUES(`calls_per_account_y`),
    `description` = VALUES(`description`);

-- 本地首次启动账号。部署生产前应修改密码并通过环境变量替换 pepper。
-- 默认密码依次为 admin123 / ops123 / finance123 / agent123 / support123。
INSERT INTO `pdk_admin_user` (`username`, `password_hash`, `display_name`, `role_code`, `status`) VALUES
('super_admin', '3fe7dd4057e685865075f0c208ccebb407485dd81db2d2b8e8aed31f26feb8c1', '超级管理员', 'SUPER_ADMIN', 'ACTIVE'),
('operations', 'b053619348358460d00cf1f71b4bd559235fe1fcccafccaa04d5b136475784e6', '运营管理员', 'OPERATIONS', 'ACTIVE'),
('finance', '64823ca6bce383f5be122a472e652d8003c7f03dcb14a4730468a5995a1bf7cd', '财务管理员', 'FINANCE', 'ACTIVE'),
('agent_demo', '6f99db49327ae9bbf6a5174a36a35cd5cfd9ccecb357eb1493294e520760ff17', '演示代理商', 'AGENT', 'ACTIVE'),
('support', '95be7be79cab48c30b43b55779bd045593806cef5f33fb87a11a7fca4f3be5b3', '客服管理员', 'SUPPORT', 'ACTIVE')
ON DUPLICATE KEY UPDATE
    `display_name` = VALUES(`display_name`),
    `role_code` = VALUES(`role_code`),
    `status` = VALUES(`status`);

-- 仅用于本地联调，避免首次启动资源池为空；生产环境应在管理端替换为真实资源。
INSERT INTO `pdk_token_pool` (`token_val`, `account_alias`, `health_status`, `daily_calls_count`, `daily_max_capacity`, `risk_score`)
SELECT 'demo_pdd_session_token_replace_me', 'LOCAL-DEMO-SLOT-01', 'HEALTHY', 0, 500, 0
WHERE NOT EXISTS (SELECT 1 FROM `pdk_token_pool` WHERE `account_alias` = 'LOCAL-DEMO-SLOT-01');

-- 10. 客户端登录凭证与业务身份。普通注册固定为 CUSTOMER，只有超级管理员可升级为 PARTNER。
CREATE TABLE IF NOT EXISTS `pdk_user_credential` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL UNIQUE,
    `password_hash` VARCHAR(100) NOT NULL,
    `role_code` VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER' COMMENT 'CUSTOMER, PARTNER',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_credential_role` (`role_code`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户端密码凭证与业务身份';

-- 11. 短信验证码留痕。只保存摘要，不保存验证码明文。
CREATE TABLE IF NOT EXISTS `pdk_sms_verification` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `phone` VARCHAR(20) NOT NULL,
    `purpose` VARCHAR(20) NOT NULL,
    `code_hash` CHAR(64) NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, USED, EXPIRED',
    `expire_at` DATETIME NOT NULL,
    `used_at` DATETIME DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_sms_phone_purpose` (`phone`, `purpose`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='短信验证码记录';

-- 12. 不可变套餐版本。任何指标变化都新建模板，历史模板只能停用不能修改。
CREATE TABLE IF NOT EXISTS `pdk_package_plan` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `owner_user_id` BIGINT DEFAULT NULL COMMENT 'NULL 表示平台模板，否则为代理模板',
    `name` VARCHAR(64) NOT NULL,
    `version_no` INT NOT NULL DEFAULT 1,
    `list_price` DECIMAL(10,2) NOT NULL,
    `discount_rate` DECIMAL(5,2) NOT NULL DEFAULT 100.00 COMMENT '折扣百分比，100为无折扣',
    `sale_price` DECIMAL(10,2) NOT NULL,
    `duration_hours` INT NOT NULL,
    `account_count` INT NOT NULL,
    `calls_per_account` INT NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    `description` VARCHAR(255) DEFAULT NULL,
    `created_by` VARCHAR(64) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `disabled_at` DATETIME DEFAULT NULL,
    INDEX `idx_plan_owner_status` (`owner_user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变套餐版本';

-- 13. 套餐期独占小号分配历史。ACTIVE 资源只能归一个客户使用。
CREATE TABLE IF NOT EXISTS `pdk_account_assignment` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `token_id` BIGINT NOT NULL,
    `package_plan_id` INT NOT NULL,
    `card_key_id` BIGINT DEFAULT NULL,
    `slot_index` INT NOT NULL,
    `allocated_calls` INT NOT NULL,
    `used_calls` INT NOT NULL DEFAULT 0,
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, REPLACED, RELEASED',
    `assigned_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `expire_at` DATETIME NOT NULL,
    `released_at` DATETIME DEFAULT NULL,
    `replaced_by_assignment_id` BIGINT DEFAULT NULL,
    INDEX `idx_assignment_user_status` (`user_id`, `status`),
    INDEX `idx_assignment_token_status` (`token_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='套餐期独占小号分配';

-- 14. 代理邀请码。仅用于渠道归属，不承载权限、套餐或余额。
CREATE TABLE IF NOT EXISTS `pdk_invitation_code` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `code` VARCHAR(20) NOT NULL UNIQUE,
    `owner_user_id` BIGINT NOT NULL UNIQUE COMMENT '邀请码所属 PARTNER 用户',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    `max_uses` INT DEFAULT NULL COMMENT 'NULL 表示不限次数',
    `used_count` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_invitation_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理注册邀请码';

-- 15. 注册渠道归属。注册后保持历史归属，不随邀请码停用而删除。
CREATE TABLE IF NOT EXISTS `pdk_user_referral` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL UNIQUE,
    `invitation_code_id` BIGINT NOT NULL,
    `partner_user_id` BIGINT NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_referral_partner` (`partner_user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户注册渠道归属';

-- 为升级前已存在的代理补齐稳定邀请码；重复启动不会重复生成。
INSERT INTO `pdk_invitation_code` (`code`, `owner_user_id`, `status`, `used_count`)
SELECT CONCAT('P', LPAD(UPPER(HEX(c.`user_id`)), 7, '0')), c.`user_id`, 'ACTIVE', 0
FROM `pdk_user_credential` c
WHERE c.`role_code` = 'PARTNER'
  AND NOT EXISTS (SELECT 1 FROM `pdk_invitation_code` i WHERE i.`owner_user_id` = c.`user_id`);

-- 平台默认套餐版本；旧 pdk_package_template 仅保留兼容历史数据。
INSERT INTO `pdk_package_plan` (`id`, `owner_user_id`, `name`, `version_no`, `list_price`, `discount_rate`, `sale_price`, `duration_hours`, `account_count`, `calls_per_account`, `status`, `description`, `created_by`) VALUES
(1, NULL, '20元天卡', 1, 20.00, 100.00, 20.00, 24, 1, 50, 'ACTIVE', '1个独占账号，每账号50次', '13454118762'),
(2, NULL, '200元月卡', 1, 200.00, 100.00, 200.00, 720, 10, 30, 'ACTIVE', '10个独占账号，每账号30次', '13454118762'),
(3, NULL, '500元季卡', 1, 500.00, 100.00, 500.00, 2160, 30, 50, 'ACTIVE', '30个独占账号，每账号50次', '13454118762')
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);

-- 唯一平台超级管理员。旧演示管理角色停用，避免继续产生混乱权限。
INSERT INTO `pdk_admin_user` (`username`, `password_hash`, `display_name`, `role_code`, `status`) VALUES
('13454118762', '3fe7dd4057e685865075f0c208ccebb407485dd81db2d2b8e8aed31f26feb8c1', '平台超级管理员', 'SUPER_ADMIN', 'ACTIVE')
ON DUPLICATE KEY UPDATE `display_name` = VALUES(`display_name`), `role_code` = 'SUPER_ADMIN', `status` = 'ACTIVE';

UPDATE `pdk_admin_user` SET `status` = 'DISABLED'
WHERE `username` IN ('super_admin', 'operations', 'finance', 'agent_demo', 'support');
