-- ==========================================================
-- PDK 多业务商业化体系 - 生产级 DDL 数据表结构
-- ==========================================================

-- 0. 业务主数据。app_id 是客户端公开标识；biz_id 是数据库内部关联键。
CREATE TABLE IF NOT EXISTS `pdk_business` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `app_id` BIGINT NOT NULL COMMENT '客户端固定 appId',
    `biz_code` VARCHAR(32) NOT NULL COMMENT '稳定业务编码',
    `biz_name` VARCHAR(64) NOT NULL,
    `description` VARCHAR(255) DEFAULT NULL,
    `registration_mode` VARCHAR(20) NOT NULL DEFAULT 'ADMIN_ONLY' COMMENT 'SELF_SERVICE, ADMIN_ONLY',
    `authorization_mode` VARCHAR(24) NOT NULL DEFAULT 'USER_SUBSCRIPTION' COMMENT 'USER_SUBSCRIPTION, DEVICE_LICENSE',
    `trial_enabled` TINYINT(1) NOT NULL DEFAULT 0,
    `trial_duration_hours` INT NOT NULL DEFAULT 0,
    `trial_account_count` INT NOT NULL DEFAULT 0,
    `trial_calls_per_account` INT NOT NULL DEFAULT 0,
    `force_initial_password_change` TINYINT(1) NOT NULL DEFAULT 1,
    `status` VARCHAR(20) NOT NULL DEFAULT 'DISABLED' COMMENT 'ACTIVE, DISABLED',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_business_app_id` (`app_id`),
    UNIQUE KEY `uk_business_code` (`biz_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务主数据与运行策略';

INSERT INTO `pdk_business` (`id`, `app_id`, `biz_code`, `biz_name`, `description`, `registration_mode`, `authorization_mode`,
 `trial_enabled`, `trial_duration_hours`, `trial_account_count`, `trial_calls_per_account`,
 `force_initial_password_change`, `status`) VALUES
(1, 1, 'PDD', '拼多多业务', '拼多多账号与下单资源服务', 'SELF_SERVICE', 'USER_SUBSCRIPTION', 1, 24, 1, 20, 0, 'ACTIVE'),
(2, 2, 'ZHIBO_AI', '直播 AI', '直播智能内容生成能力；与直播矩阵共用 zhibo 聚合实现', 'ADMIN_ONLY', 'USER_SUBSCRIPTION', 0, 0, 0, 0, 1, 'DISABLED'),
(3, 3, 'ZHIBO_LIVE', '直播矩阵', '直播场控与账号能力；与直播 AI 共用 zhibo 聚合实现', 'ADMIN_ONLY', 'DEVICE_LICENSE', 0, 0, 0, 0, 1, 'DISABLED')
ON DUPLICATE KEY UPDATE `biz_name` = VALUES(`biz_name`), `description` = VALUES(`description`);


-- 1. 用户主表
CREATE TABLE IF NOT EXISTS `pdk_user` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户主键ID',
    `biz_id` BIGINT NOT NULL DEFAULT 1 COMMENT '所属业务',
    `phone` VARCHAR(20) NOT NULL COMMENT '手机号（业务内唯一）',
    `account_source` VARCHAR(20) NOT NULL DEFAULT 'SELF_REGISTER' COMMENT 'SELF_REGISTER, ADMIN_CREATED',
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
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_user_biz_phone` (`biz_id`, `phone`),
    INDEX `idx_user_biz_status` (`biz_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户主表';

CREATE TABLE IF NOT EXISTS `pdk_login_log` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `biz_id` BIGINT NULL,
    `actor_type` VARCHAR(10) NOT NULL COMMENT 'CLIENT/ADMIN',
    `actor_id` BIGINT NULL,
    `actor_account` VARCHAR(50) NOT NULL,
    `event_type` VARCHAR(30) NOT NULL,
    `result` VARCHAR(10) NOT NULL,
    `fail_reason` VARCHAR(200) NULL,
    `ip_address` VARCHAR(64) NULL,
    `device_id` VARCHAR(200) NULL,
    `user_device_id` BIGINT NULL,
    `device_license_id` BIGINT NULL,
    `license_status` VARCHAR(20) NULL,
    `license_expire_at` DATETIME NULL,
    `user_agent` VARCHAR(500) NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_login_actor` (`actor_type`, `actor_id`, `created_at`),
    INDEX `idx_login_account` (`actor_account`, `created_at`),
    INDEX `idx_login_biz` (`biz_id`, `created_at`),
    INDEX `idx_login_license` (`biz_id`, `device_license_id`, `created_at`),
    -- 登录日志页默认无筛选、只按时间倒序分页，缺此索引会全表扫描 + filesort
    INDEX `idx_login_created` (`created_at`),
    -- 许可证登录历史只按 device_license_id 过滤，不带 biz_id，用不上上面的复合索引
    INDEX `idx_login_license_id` (`device_license_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录、设备和许可证安全审计';

-- 2. 卡密凭证表 (纯生命周期与卡密状态)
CREATE TABLE IF NOT EXISTS `pdk_card_key` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '卡密ID',
    `biz_id` BIGINT NOT NULL DEFAULT 1 COMMENT '所属业务',
    `card_key` VARCHAR(64) NOT NULL COMMENT '业务内唯一卡密序列号',
    `package_id` INT NOT NULL COMMENT '绑定套餐模版ID',
    `status` VARCHAR(20) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED(库存), ASSIGNED(已分配), ACTIVATED(已绑设备), VOID(作废)',
    `generated_by_admin` VARCHAR(64) NOT NULL COMMENT '制卡管理员或代理商账号',
    `agent_id` BIGINT DEFAULT NULL COMMENT '所属代理商ID',
    `assigned_user_id` BIGINT DEFAULT NULL COMMENT '预分配用户ID（设备许可证模式）',
    `assigned_phone` VARCHAR(20) DEFAULT NULL COMMENT '预分配手机号快照',
    `assigned_at` DATETIME DEFAULT NULL COMMENT '预分配时间',
    `activated_by_phone` VARCHAR(20) DEFAULT NULL COMMENT '激活绑定的用户手机号',
    `activated_by_user_id` BIGINT DEFAULT NULL COMMENT '激活用户ID',
    `activated_at` DATETIME DEFAULT NULL COMMENT '激活核销时间',
    `activated_device_id` VARCHAR(128) DEFAULT NULL COMMENT '核销时绑定的设备UUID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '制卡时间',
    UNIQUE KEY `uk_card_biz_key` (`biz_id`, `card_key`),
    INDEX `idx_card_biz_status` (`biz_id`, `status`),
    INDEX `idx_card_assigned_user` (`biz_id`, `assigned_user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='卡密凭证表';

-- 3. 财务独立实收流水表 (与卡密物理拆分)
CREATE TABLE IF NOT EXISTS `pdk_financial_income` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '流水ID',
    `biz_id` BIGINT NOT NULL DEFAULT 1 COMMENT '所属业务',
    `user_id` BIGINT DEFAULT NULL COMMENT '付费用户ID',
    `income_order_no` VARCHAR(64) NOT NULL UNIQUE COMMENT '收入对账流水号',
    `card_key_id` BIGINT NOT NULL COMMENT '关联卡密ID',
    `card_key` VARCHAR(64) NOT NULL COMMENT '卡密序列号',
    `user_phone` VARCHAR(20) NOT NULL COMMENT '付费充值手机号',
    `package_id` INT NOT NULL COMMENT '套餐模版ID',
    `package_name` VARCHAR(64) NOT NULL COMMENT '套餐名称',
    `face_value` DECIMAL(10,2) NOT NULL COMMENT '官方标价面值',
    `amount` DECIMAL(10,2) NOT NULL COMMENT '实际记账收入金额',
    `discount_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '优惠让利金额',
    `order_type` VARCHAR(30) NOT NULL COMMENT '类型: NORMAL_SALE(正价售卖), DISCOUNT_SALE(折价销售), GIFT_FREE(商务赠送), RENEWAL(续费)',
    `payment_channel` VARCHAR(30) NOT NULL COMMENT '支付通道: ALIPAY, WECHAT_PAY, BANK_TRANSFER, OFFLINE',
    `payment_txn_no` VARCHAR(128) DEFAULT NULL COMMENT '外部第三方支付流水号',
    `audit_admin` VARCHAR(64) NOT NULL COMMENT '审核/制卡操作人',
    `activated_at` DATETIME NOT NULL COMMENT '核销记账时间',
    `audit_remark` VARCHAR(255) DEFAULT NULL COMMENT '记账备注说明',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记账创建时间',
    INDEX `idx_income_biz_created` (`biz_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='财务独立收入流水表';

-- 4. 财务对公采购支出表 (Token 进货成本)
CREATE TABLE IF NOT EXISTS `pdk_company_expense` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '支出ID',
    `biz_id` BIGINT DEFAULT NULL COMMENT 'NULL 为平台公共费用，否则归属业务',
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
    `biz_id` BIGINT NOT NULL DEFAULT 1 COMMENT '所属业务',
    `token_val` VARCHAR(512) NOT NULL COMMENT '拼多多官方底层 Session Token',
    `credential_type` VARCHAR(32) NOT NULL DEFAULT 'TOKEN' COMMENT 'TOKEN/COOKIE/ACCOUNT_PASSWORD/JSON',
    `credential_payload` VARCHAR(2048) DEFAULT NULL COMMENT '通用业务凭证载荷；兼容期同时保留 token_val',
    `account_alias` VARCHAR(64) NOT NULL COMMENT '底层账号备注/别名',
    `health_status` VARCHAR(20) NOT NULL DEFAULT 'HEALTHY' COMMENT '状态: HEALTHY(健康), BUSY(占用), FAULT_BLACK(故障拉黑), EXPIRED(过期)',
    `daily_calls_count` INT NOT NULL DEFAULT 0 COMMENT '今日已调度调用次数',
    `daily_max_capacity` INT NOT NULL DEFAULT 500 COMMENT '单账号每日建议安全调用阈值',
    `risk_score` INT NOT NULL DEFAULT 0 COMMENT '风控危险分 (0-100)',
    `lease_client_phone` VARCHAR(20) DEFAULT NULL COMMENT '当前租借客户端手机号',
    `leased_at` DATETIME DEFAULT NULL COMMENT '租借时间',
    `last_fault_time` DATETIME DEFAULT NULL COMMENT '最近一次报错故障时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '录入时间',
    `uuid` VARCHAR(36) DEFAULT NULL COMMENT '全局唯一标识，用于与卡密分配记录(pdk_account_assignment)及调度对账关联',
    `is_discarded` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否逻辑废弃(0否1是)；废弃后不参与调度，记录保留供管理员查看',
    UNIQUE KEY `uk_token_pool_uuid` (`uuid`),
    INDEX `idx_token_biz_health` (`biz_id`, `health_status`, `is_discarded`),
    INDEX `idx_token_lease_recovery` (`health_status`, `is_discarded`, `leased_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拼多多官方 Token 公共调度池';

-- 6. 套餐模版表
CREATE TABLE IF NOT EXISTS `pdk_package_template` (
    `id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '套餐ID',
    `biz_id` BIGINT NOT NULL DEFAULT 1 COMMENT '所属业务（旧兼容表）',
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
    `biz_id` BIGINT NOT NULL DEFAULT 1 COMMENT '所属业务',
    `user_id` BIGINT DEFAULT NULL COMMENT '调用用户ID',
    `req_uuid` VARCHAR(64) NOT NULL COMMENT '业务内请求幂等UUID',
    `user_phone` VARCHAR(20) NOT NULL COMMENT '调用用户手机号',
    `slot_index` INT NOT NULL DEFAULT 1 COMMENT '消耗的逻辑账号槽位 (1~X)',
    `real_pdd_account_id` VARCHAR(64) NOT NULL COMMENT '实际承载调度的底层公司账号ID',
    `action_type` VARCHAR(64) NOT NULL COMMENT '业务操作类型 (如: QUERY_ORDER, GET_GOODS)',
    `deduct_count` TINYINT NOT NULL DEFAULT 1 COMMENT '本次扣减次数 (成功扣1, 账号异常/免责扣0)',
    `exec_status` VARCHAR(30) NOT NULL COMMENT '执行状态: SUCCESS, TOKEN_FAIL, PARAM_ERROR, NET_TIMEOUT, FAULT_HEALED',
    `response_time_ms` INT NOT NULL COMMENT '网关处理耗时 (ms)',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调度发生时间',
    UNIQUE KEY `uk_dispatch_biz_req` (`biz_id`, `req_uuid`),
    INDEX `idx_user_time` (`biz_id`, `user_id`, `created_at`),
    INDEX `idx_account_stat` (`real_pdd_account_id`, `exec_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务调度与扣费明细表';

-- 8. 管理员核心操作不可逆审计日志表
CREATE TABLE IF NOT EXISTS `pdk_admin_audit_log` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '审计日志ID',
    `biz_id` BIGINT DEFAULT NULL COMMENT 'NULL 为平台级操作，否则归属业务',
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
    `biz_id` BIGINT DEFAULT NULL COMMENT 'PARTNER 所属业务；SUPER_ADMIN 为 NULL',
    `username` VARCHAR(64) NOT NULL UNIQUE COMMENT '登录账号',
    `password_hash` CHAR(64) NOT NULL COMMENT '密码摘要',
    `display_name` VARCHAR(64) NOT NULL COMMENT '显示名称',
    `role_code` VARCHAR(32) NOT NULL COMMENT 'SUPER_ADMIN, PARTNER（后台仅两种身份：超级管理员与代理商）',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, DISABLED',
    `last_login_at` DATETIME DEFAULT NULL COMMENT '最后登录时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理后台多角色账号表';

-- 初始化套餐数据
-- INSERT INTO `pdk_package_template` (`id`, `name`, `price`, `duration_days`, `account_count_x`, `calls_per_account_y`, `description`) VALUES
-- (1, '20元天卡（高频体验版）', 20.00, 1, 1, 50, '单日50次调用，单机绑定'),
-- (2, '200元月卡（多账号防控版）', 200.00, 30, 10, 30, '10个买家号并发挂载，单号日限30次'),
-- (3, '500元季卡（高并发工作室版）', 500.00, 90, 30, 50, '30个买家号并发挂载，单号日限50次'),
-- (4, '1500元年卡（旗舰企业尊享版）', 1500.00, 365, 100, 100, '100个买家号并发挂载，单号日限100次')
-- ON DUPLICATE KEY UPDATE
--     `name` = VALUES(`name`),
--     `price` = VALUES(`price`),
--     `duration_days` = VALUES(`duration_days`),
--     `account_count_x` = VALUES(`account_count_x`),
--     `calls_per_account_y` = VALUES(`calls_per_account_y`),
--     `description` = VALUES(`description`);

-- 本地首次启动账号。部署生产前应修改密码并通过环境变量替换 pepper。
-- 默认密码依次为 admin123 / ops123 / finance123 / agent123 / support123。
-- INSERT INTO `pdk_admin_user` (`username`, `password_hash`, `display_name`, `role_code`, `status`) VALUES
-- ('super_admin', '3fe7dd4057e685865075f0c208ccebb407485dd81db2d2b8e8aed31f26feb8c1', '超级管理员', 'SUPER_ADMIN', 'ACTIVE'),
-- ('operations', 'b053619348358460d00cf1f71b4bd559235fe1fcccafccaa04d5b136475784e6', '运营管理员', 'OPERATIONS', 'ACTIVE'),
-- ('finance', '64823ca6bce383f5be122a472e652d8003c7f03dcb14a4730468a5995a1bf7cd', '财务管理员', 'FINANCE', 'ACTIVE'),
-- ('agent_demo', '6f99db49327ae9bbf6a5174a36a35cd5cfd9ccecb357eb1493294e520760ff17', '演示代理商', 'AGENT', 'ACTIVE'),
-- ('support', '95be7be79cab48c30b43b55779bd045593806cef5f33fb87a11a7fca4f3be5b3', '客服管理员', 'SUPPORT', 'ACTIVE')
-- ON DUPLICATE KEY UPDATE
--     `display_name` = VALUES(`display_name`),
--     `role_code` = VALUES(`role_code`),
--     `status` = VALUES(`status`);

-- 仅用于本地联调，避免首次启动资源池为空；生产环境应在管理端替换为真实资源。
-- INSERT INTO `pdk_token_pool` (`token_val`, `account_alias`, `health_status`, `daily_calls_count`, `daily_max_capacity`, `risk_score`)
-- SELECT 'demo_pdd_session_token_replace_me', 'LOCAL-DEMO-SLOT-01', 'HEALTHY', 0, 500, 0
-- WHERE NOT EXISTS (SELECT 1 FROM `pdk_token_pool` WHERE `account_alias` = 'LOCAL-DEMO-SLOT-01');

-- 10. 客户端登录凭证与业务身份。普通注册固定为 CUSTOMER，只有超级管理员可升级为 PARTNER。
CREATE TABLE IF NOT EXISTS `pdk_user_credential` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL UNIQUE,
    `password_hash` VARCHAR(100) NOT NULL,
    `role_code` VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER' COMMENT 'CUSTOMER, PARTNER',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    `must_change_password` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '管理员预置账号首次登录后必须改密',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_credential_role` (`role_code`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户端密码凭证与业务身份';

-- 11. 短信验证码留痕。只保存摘要，不保存验证码明文。
CREATE TABLE IF NOT EXISTS `pdk_sms_verification` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `biz_id` BIGINT NOT NULL DEFAULT 1,
    `phone` VARCHAR(20) NOT NULL,
    `purpose` VARCHAR(20) NOT NULL,
    `code_hash` CHAR(64) NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, USED, EXPIRED',
    `expire_at` DATETIME NOT NULL,
    `used_at` DATETIME DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_sms_phone_purpose` (`biz_id`, `phone`, `purpose`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='短信验证码记录';

-- 12. 不可变套餐版本。任何指标变化都新建模板，历史模板只能停用不能修改。
CREATE TABLE IF NOT EXISTS `pdk_package_plan` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `biz_id` BIGINT NOT NULL DEFAULT 1,
    `owner_user_id` BIGINT DEFAULT NULL COMMENT 'NULL 表示平台模板，否则为代理模板',
    `owner_scope` BIGINT GENERATED ALWAYS AS (COALESCE(`owner_user_id`, 0)) STORED COMMENT '平台模板按0参与唯一约束',
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
    UNIQUE KEY `uk_plan_biz_owner_name_version` (`biz_id`, `owner_scope`, `name`, `version_no`),
    INDEX `idx_plan_owner_status` (`biz_id`, `owner_user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变套餐版本';

-- 13. 套餐期独占小号分配历史。ACTIVE 资源只能归一个客户使用。
CREATE TABLE IF NOT EXISTS `pdk_account_assignment` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `biz_id` BIGINT NOT NULL DEFAULT 1,
    `user_id` BIGINT NOT NULL,
    `token_id` BIGINT NOT NULL,
    `package_plan_id` INT NOT NULL,
    `card_key_id` BIGINT DEFAULT NULL,
    `slot_index` INT NOT NULL,
    `allocated_calls` INT NOT NULL,
    `used_calls` INT NOT NULL DEFAULT 0,
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, REPLACED, RELEASED',
    `active_token_guard` BIGINT GENERATED ALWAYS AS (CASE WHEN `status` = 'ACTIVE' THEN `token_id` ELSE NULL END) STORED,
    `active_slot_guard` VARCHAR(96) GENERATED ALWAYS AS (CASE WHEN `status` = 'ACTIVE' THEN CONCAT(`user_id`, ':', `slot_index`) ELSE NULL END) STORED,
    `assigned_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `expire_at` DATETIME NOT NULL,
    `released_at` DATETIME DEFAULT NULL,
    `replaced_by_assignment_id` BIGINT DEFAULT NULL,
    UNIQUE KEY `uk_assignment_active_token` (`biz_id`, `active_token_guard`),
    UNIQUE KEY `uk_assignment_active_slot` (`biz_id`, `active_slot_guard`),
    INDEX `idx_assignment_user_status` (`biz_id`, `user_id`, `status`),
    INDEX `idx_assignment_token_status` (`biz_id`, `token_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='套餐期独占小号分配';

-- 14. 代理邀请码。仅用于渠道归属，不承载权限、套餐或余额。
CREATE TABLE IF NOT EXISTS `pdk_invitation_code` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `biz_id` BIGINT NOT NULL DEFAULT 1,
    `code` VARCHAR(20) NOT NULL,
    `owner_user_id` BIGINT NOT NULL COMMENT '邀请码所属 PARTNER 用户',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    `max_uses` INT DEFAULT NULL COMMENT 'NULL 表示不限次数',
    `used_count` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_invitation_biz_code` (`biz_id`, `code`),
    UNIQUE KEY `uk_invitation_biz_owner` (`biz_id`, `owner_user_id`),
    INDEX `idx_invitation_status` (`biz_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理注册邀请码';

-- 15. 注册渠道归属。注册后保持历史归属，不随邀请码停用而删除。
CREATE TABLE IF NOT EXISTS `pdk_user_referral` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `biz_id` BIGINT NOT NULL DEFAULT 1,
    `user_id` BIGINT NOT NULL,
    `invitation_code_id` BIGINT NOT NULL,
    `partner_user_id` BIGINT NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_referral_biz_user` (`biz_id`, `user_id`),
    INDEX `idx_referral_partner` (`biz_id`, `partner_user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户注册渠道归属';

-- 16. 多设备授权：设备是登录终端，许可证是一张卡对应的独立授权席位。
CREATE TABLE IF NOT EXISTS `pdk_user_device` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `biz_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `device_id` VARCHAR(128) NOT NULL,
    `device_id_hash` CHAR(64) NOT NULL,
    `device_name` VARCHAR(128) DEFAULT NULL,
    `platform` VARCHAR(32) DEFAULT NULL,
    `client_version` VARCHAR(32) DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/UNBOUND/BLOCKED',
    `first_bound_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `last_login_at` DATETIME DEFAULT NULL,
    `last_seen_at` DATETIME DEFAULT NULL,
    `unbound_at` DATETIME DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_user_device` (`biz_id`, `user_id`, `device_id_hash`),
    INDEX `idx_device_user_status` (`biz_id`, `user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户设备；DEVICE_LICENSE 业务允许一用户多设备';

CREATE TABLE IF NOT EXISTS `pdk_device_license` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `biz_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `card_key_id` BIGINT NOT NULL,
    `user_device_id` BIGINT DEFAULT NULL,
    `package_id` BIGINT NOT NULL,
    `package_name_snapshot` VARCHAR(64) NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'UNBOUND' COMMENT 'UNBOUND/ACTIVE/EXPIRED/SUSPENDED/REVOKED',
    `activated_at` DATETIME DEFAULT NULL,
    `effective_at` DATETIME DEFAULT NULL,
    `expire_at` DATETIME DEFAULT NULL,
    `remaining_calls` INT NOT NULL DEFAULT 0,
    `total_calls` INT NOT NULL DEFAULT 0,
    `last_used_at` DATETIME DEFAULT NULL,
    `version` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `active_device_guard` BIGINT GENERATED ALWAYS AS (
        CASE WHEN `status` IN ('ACTIVE','SUSPENDED') THEN `user_device_id` ELSE NULL END
    ) STORED,
    UNIQUE KEY `uk_license_card` (`card_key_id`),
    UNIQUE KEY `uk_license_active_device` (`biz_id`, `active_device_guard`),
    INDEX `idx_license_user_status` (`biz_id`, `user_id`, `status`, `expire_at`),
    INDEX `idx_license_expiry` (`status`, `expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一张卡一个设备许可证，独立期限和次数';

CREATE TABLE IF NOT EXISTS `pdk_license_renewal` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `biz_id` BIGINT NOT NULL,
    `license_id` BIGINT NOT NULL,
    `card_key_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `renewal_order_no` VARCHAR(64) NOT NULL,
    `before_expire_at` DATETIME DEFAULT NULL,
    `duration_hours` INT NOT NULL,
    `after_expire_at` DATETIME NOT NULL,
    `added_calls` INT NOT NULL DEFAULT 0,
    `amount` DECIMAL(10,2) NOT NULL DEFAULT 0,
    `payment_channel` VARCHAR(30) NOT NULL DEFAULT 'OFFLINE',
    `operator_id` VARCHAR(64) NOT NULL,
    `remark` VARCHAR(255) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_license_renewal_order` (`renewal_order_no`),
    INDEX `idx_license_renewal` (`biz_id`, `license_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备许可证续费历史；原卡不变，每次续费新增记录';

-- 17b. 卡密导出存根。管理员从后台导出某用户在某业务下的卡密后，原文留档在服务器，便于追溯与客户核对。
CREATE TABLE IF NOT EXISTS `pdk_license_export_stub` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `biz_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `phone` VARCHAR(32) DEFAULT NULL COMMENT '导出时的客户手机号',
    `operator` VARCHAR(64) NOT NULL COMMENT '操作管理员账号',
    `file_name` VARCHAR(128) NOT NULL COMMENT '导出文件名',
    `record_count` INT NOT NULL DEFAULT 0 COMMENT '导出卡密条数',
    `content` MEDIUMTEXT NOT NULL COMMENT '导出的 CSV 原文（含明文卡密），UTF-8 BOM',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_stub_biz_user` (`biz_id`, `user_id`),
    INDEX `idx_stub_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='卡密导出存根，服务器留存原文以备追溯';

-- 17. ZHIBO_LIVE MediaMTX 推流会话。票据只保存 SHA-256；活动许可证生成列保证单席位单流。
CREATE TABLE IF NOT EXISTS `pdk_live_stream_session` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `biz_id` BIGINT NOT NULL COMMENT '固定归属 ZHIBO_LIVE 业务',
    `user_id` BIGINT NOT NULL,
    `user_device_id` BIGINT DEFAULT NULL,
    `device_license_id` BIGINT DEFAULT NULL,
    `stream_session_no` VARCHAR(48) NOT NULL,
    `client_request_id` VARCHAR(64) NOT NULL,
    `media_node_code` VARCHAR(64) NOT NULL,
    `path` VARCHAR(160) NOT NULL,
    `protocol` VARCHAR(10) NOT NULL DEFAULT 'RTMP',
    `status` VARCHAR(24) NOT NULL COMMENT 'ISSUED/AUTHORIZED/LIVE/ENDED/EXPIRED',
    `ticket_hash` CHAR(64) NOT NULL COMMENT '推流票据 SHA-256，禁止存明文',
    `ticket_expires_at` DATETIME NOT NULL,
    `device_id_hash` CHAR(64) NOT NULL,
    `client_ip` VARCHAR(64) DEFAULT NULL,
    `mediamtx_connection_id` VARCHAR(64) DEFAULT NULL,
    `mediamtx_source_id` VARCHAR(64) DEFAULT NULL,
    `authorized_at` DATETIME DEFAULT NULL,
    `started_at` DATETIME DEFAULT NULL,
    `ended_at` DATETIME DEFAULT NULL,
    `duration_seconds` BIGINT DEFAULT NULL,
    `billed_units` INT NOT NULL DEFAULT 0,
    `end_reason` VARCHAR(64) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `active_subject_guard` VARCHAR(80) GENERATED ALWAYS AS (
        CASE WHEN `status` IN ('ISSUED','AUTHORIZED','LIVE','KICK_REQUESTED')
             THEN CONCAT(`user_id`, ':', IFNULL(`device_license_id`, 0)) ELSE NULL END
    ) STORED,
    UNIQUE KEY `uk_live_session_no` (`stream_session_no`),
    UNIQUE KEY `uk_live_ticket_hash` (`ticket_hash`),
    UNIQUE KEY `uk_live_client_request` (`biz_id`, `user_id`, `client_request_id`),
    UNIQUE KEY `uk_live_active_subject` (`biz_id`, `active_subject_guard`),
    UNIQUE KEY `uk_live_mediamtx_conn` (`media_node_code`, `mediamtx_connection_id`),
    INDEX `idx_live_user_status` (`biz_id`, `user_id`, `status`, `created_at`),
    INDEX `idx_live_license_status` (`biz_id`, `device_license_id`, `status`, `created_at`),
    INDEX `idx_live_ticket_expire` (`status`, `ticket_expires_at`),
    INDEX `idx_live_path` (`path`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ZHIBO_LIVE 推流会话与短效票据';

-- 为升级前已存在的代理补齐稳定邀请码；重复启动不会重复生成。
-- INSERT INTO `pdk_invitation_code` (`code`, `owner_user_id`, `status`, `used_count`)
-- SELECT CONCAT('P', LPAD(UPPER(HEX(c.`user_id`)), 7, '0')), c.`user_id`, 'ACTIVE', 0
-- FROM `pdk_user_credential` c
-- WHERE c.`role_code` = 'PARTNER'
--   AND NOT EXISTS (SELECT 1 FROM `pdk_invitation_code` i WHERE i.`owner_user_id` = c.`user_id`);

-- 平台默认套餐版本；旧 pdk_package_template 仅保留兼容历史数据。
-- INSERT INTO `pdk_package_plan` (`id`, `owner_user_id`, `name`, `version_no`, `list_price`, `discount_rate`, `sale_price`, `duration_hours`, `account_count`, `calls_per_account`, `status`, `description`, `created_by`) VALUES
-- (1, NULL, '20元天卡', 1, 20.00, 100.00, 20.00, 24, 1, 50, 'ACTIVE', '1个独占账号，每账号50次', '13454118762'),
-- (2, NULL, '200元月卡', 1, 200.00, 100.00, 200.00, 720, 10, 30, 'ACTIVE', '10个独占账号，每账号30次', '13454118762'),
-- (3, NULL, '500元季卡', 1, 500.00, 100.00, 500.00, 2160, 30, 50, 'ACTIVE', '30个独占账号，每账号50次', '13454118762')
-- ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);

-- 唯一平台超级管理员。旧演示管理角色停用，避免继续产生混乱权限。
INSERT INTO `pdk_admin_user` (`username`, `password_hash`, `display_name`, `role_code`, `status`) VALUES
('13454118762', '3fe7dd4057e685865075f0c208ccebb407485dd81db2d2b8e8aed31f26feb8c1', '平台超级管理员', 'SUPER_ADMIN', 'ACTIVE')
ON DUPLICATE KEY UPDATE `display_name` = VALUES(`display_name`), `role_code` = 'SUPER_ADMIN', `status` = 'ACTIVE';

UPDATE `pdk_admin_user` SET `status` = 'DISABLED'
WHERE `username` IN ('super_admin', 'operations', 'finance', 'agent_demo', 'support');

-- 17. 平台系统配置（通用 KV，由超级管理员在「系统设置」页维护）
CREATE TABLE IF NOT EXISTS `pdk_system_config` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `biz_id` BIGINT DEFAULT NULL COMMENT 'NULL 为平台配置',
    `config_key` VARCHAR(64) NOT NULL UNIQUE COMMENT '配置键',
    `config_value` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '当前值',
    `config_type` VARCHAR(16) NOT NULL DEFAULT 'TEXT' COMMENT 'SWITCH/SELECT/NUMBER/TEXT',
    `config_group` VARCHAR(32) NOT NULL DEFAULT 'GENERAL' COMMENT 'ACCOUNT/SMS/SECURITY/GENERAL',
    `config_label` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '中文显示名',
    `config_options` VARCHAR(256) NOT NULL DEFAULT '' COMMENT 'SELECT 选项: VALUE:显示名,...',
    `default_value` VARCHAR(512) NOT NULL DEFAULT '' COMMENT '默认值',
    `description` VARCHAR(256) NOT NULL DEFAULT '' COMMENT '说明',
    `editable_by` VARCHAR(32) NOT NULL DEFAULT 'SUPER_ADMIN' COMMENT '可编辑角色',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_config_group` (`config_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台系统配置';

-- 全新建库模式：不执行历史 ALTER 迁移。部署前如有旧结构，请先备份并重建数据库。

-- 平台配置种子数据（超级管理员可在后台「系统设置」修改；重复执行只更新元数据，不覆盖已修改的配置值）
INSERT INTO `pdk_system_config` (`config_key`, `config_value`, `config_type`, `config_group`, `config_label`, `config_options`, `default_value`, `description`, `editable_by`) VALUES
('token.allocation.mode', 'FIXED', 'SELECT', 'ACCOUNT', '账号小号 Token 使用方式', 'FIXED:固定分配,POLLING:轮询(预留未启用)', 'FIXED', '当前仅 FIXED(固定分配)生效：激活时把小号独占绑定给用户；POLLING(轮询)预留未启用', 'SUPER_ADMIN'),
('sms.register.enabled', 'false', 'SWITCH', 'SMS', '注册短信验证码', '', 'false', '开启后客户端注册必须校验短信验证码', 'SUPER_ADMIN'),
('security.encryption.enabled', 'true', 'SWITCH', 'SECURITY', '协议安全加密', '', 'true', '开启后服务端下发拼多多 Token 走 AES-GCM 加密；关闭可灰度降级', 'SUPER_ADMIN'),
('trial.days', '1', 'NUMBER', 'ACCOUNT', '新用户试用天数', '', '1', '注册赠送的体验版天数（预留，待启用）', 'SUPER_ADMIN'),
('device.kickout.enabled', 'true', 'SWITCH', 'SECURITY', '单设备互踢', '', 'true', '开启后同账号仅允许一台设备在线（预留，待启用）', 'SUPER_ADMIN'),
('heartbeat.interval.seconds', '45', 'NUMBER', 'SECURITY', '心跳间隔(秒)', '', '45', '客户端建议心跳上报间隔（预留，待启用）', 'SUPER_ADMIN')
ON DUPLICATE KEY UPDATE `config_label` = VALUES(`config_label`), `config_options` = VALUES(`config_options`), `default_value` = VALUES(`default_value`), `description` = VALUES(`description`);
