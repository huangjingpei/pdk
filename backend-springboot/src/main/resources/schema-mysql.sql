
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

-- 初始化套餐数据
INSERT INTO `pdk_package_template` (`id`, `name`, `price`, `duration_days`, `account_count_x`, `calls_per_account_y`, `description`) VALUES
(1, '20元天卡（高频体验版）', 20.00, 1, 1, 50, '单日50次调用，单机绑定'),
(2, '200元月卡（多账号防控版）', 200.00, 30, 10, 30, '10个买家号并发挂载，单号日限30次'),
(3, '500元季卡（高并发工作室版）', 500.00, 90, 30, 50, '30个买家号并发挂载，单号日限50次'),
(4, '1500元年卡（旗舰企业尊享版）', 1500.00, 365, 100, 100, '100个买家号并发挂载，单号日限100次');
