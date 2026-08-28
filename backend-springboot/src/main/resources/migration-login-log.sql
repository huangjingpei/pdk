-- 登录日志与操作审计后台可见化迁移
-- 执行方式：mysql -u<user> -p<pass> <database> < migration-login-log.sql
-- 说明：CREATE TABLE 带 IF NOT EXISTS，可重复执行。

-- 只需新建一张表，不改动任何既有业务表。
-- 未执行本脚本时，仅新增的两个日志页会报错；用户管理等既有页面不受影响。

-- 1. 登录与敏感动作日志。客户端用户与后台管理员共用一张表，用 actor_type 区分。
CREATE TABLE IF NOT EXISTS `pdk_login_log` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `biz_id` BIGINT NULL COMMENT '客户端用户所属业务；管理员登录为 NULL',
    `actor_type` VARCHAR(10) NOT NULL COMMENT 'CLIENT 客户端用户, ADMIN 后台管理员',
    `actor_id` BIGINT NULL COMMENT 'pdk_user.id 或 pdk_admin_user.id；登录失败且账号不存在时为 NULL',
    `actor_account` VARCHAR(50) NOT NULL COMMENT '登录账号：手机号或管理账号名',
    `event_type` VARCHAR(30) NOT NULL COMMENT 'LOGIN, LOGOUT, PASSWORD_RESET, FORCE_CHANGE, DEVICE_UNBIND',
    `result` VARCHAR(10) NOT NULL COMMENT 'SUCCESS, FAIL',
    `fail_reason` VARCHAR(200) NULL COMMENT '失败原因，取业务异常文案',
    `ip_address` VARCHAR(64) NULL,
    `device_id` VARCHAR(200) NULL COMMENT '客户端登录携带的设备指纹',
    `user_agent` VARCHAR(500) NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_login_actor` (`actor_type`, `actor_id`, `created_at`),
    INDEX `idx_login_account` (`actor_account`, `created_at`),
    INDEX `idx_login_ip` (`ip_address`, `created_at`),
    INDEX `idx_login_created` (`created_at`),
    INDEX `idx_login_biz` (`biz_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录与敏感动作日志';
