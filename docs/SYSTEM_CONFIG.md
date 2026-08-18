# 系统设置（平台配置）功能说明

> 给超级管理员用的「平台级参数配置」后台页。代码已落地，本文记录设计、接口与验证方式。
> 配套技术文档：`docs/SA_TOKEN_GUIDE.md`、`docs/SA_TOKEN_ONBOARDING_CHECKLIST.md`。

## 一、功能定位
集中管理全局开关/策略（令牌分配、短信验证码、协议加密等）。采用**通用 KV 表 + schema 常量**设计：以后加配置只改两处（加一个常量 + 一条种子数据），**不用改表结构、不用改前端渲染**。

## 二、首批配置项（种子数据，默认值已定）
| config_key | 名称 | 类型 | 选项 / 默认 | 分组 |
|---|---|---|---|---|
| `token.allocation.mode` | 账号小号 Token 使用方式 | SELECT | 固定分配(`FIXED`,默认) / 轮询(`POLLING`) | ACCOUNT |
| `sms.register.enabled` | 注册短信验证码 | SWITCH | 默认 **关(false)** | SMS |
| `security.encryption.enabled` | 协议安全加密 | SWITCH | 默认 **开(true)** | SECURITY |
| `trial.days` | 新用户试用天数 | NUMBER | 默认 1（预留待启用） | ACCOUNT |
| `device.kickout.enabled` | 单设备互踢 | SWITCH | 默认 开(true)（预留待启用） | SECURITY |
| `heartbeat.interval.seconds` | 心跳间隔(秒) | NUMBER | 默认 45（预留待启用） | SECURITY |

> 预留项已在页面展示但业务尚未消费，作为后续二期联动的“开关位”。

## 三、后端改动清单
- `schema-mysql.sql`：新增 `pdk_system_config` 表 + 6 条种子数据（`ON DUPLICATE` 只更新元数据，不覆盖管理员已改的值）。
- `domain/entity/SystemConfig.java`：MyBatis-Plus 实体。
- `mapper/SystemConfigMapper.java`：`extends BaseMapper`（已被 `@MapperScan("com.pdk.mapper")` 覆盖）。
- `config/ConfigKeys.java`：配置键常量 + 默认值 + 取值辅助方法（如 `isPollingAllocation`、`isSecurityEncryptionEnabled`）。
- `service/SystemConfigService.java`：`listAll` / `saveConfigs`（按 key 更新）/ `getValue(key, default)`（内存缓存 + 默认值回退，异常安全）。
- `controller/AdminSystemConfigController.java`：`GET /api/v1/admin/system-config/list`、`POST /api/v1/admin/system-config/update`（均 `@RequirePermission(SYSTEM_CONFIG)`，更新写审计日志）。
- `security/RolePermissions.java`：新增 `SYSTEM_CONFIG = "system:config"`，已并入 `SUPER_ADMIN` 的 `ALL` 集合（PARTNER 无此权限，看不到入口）。

## 四、前端改动清单（admin-vue3）
- `views/settings/SystemConfig.vue`：按 `config_group` 分卡片渲染表单（SWITCH→`el-switch`、SELECT→`el-select`、NUMBER→`el-input-number`、TEXT→`el-input`），保存调用 `POST /update`。
- `router/index.ts`：新增 `/settings` 路由，`meta.permission = 'system:config'`。
- `App.vue`：侧边菜单新增「系统设置」入口，`v-if="hasPermission('system:config')"` 仅超管可见。

## 五、业务联动（二期，未在本次接入）
- `token.allocation.mode` → 影响 `dispatch/acquire-token` 的选号策略（固定槽位 vs 轮询）。
- `sms.register.enabled` → 控制 `ClientAuthController` 注册是否强制校验短信验证码。
- `security.encryption.enabled` → 控制 dispatch 下发 Token 是否走 AES-GCM 加密（关闭可灰度降级）。
- 业务代码读取方式：`systemConfigService.getValue(ConfigKeys.TOKEN_ALLOCATION_MODE, ConfigKeys.DEFAULT_TOKEN_ALLOCATION_MODE)`。

## 六、如何验证（手动）
### 后端
```powershell
cd E:\pdk\backend-springboot
# 若首次或改了 schema，先确保库已初始化（执行 src/main/resources/schema-mysql.sql）
mvn compile        # 已验证 BUILD SUCCESS
# 启动后（端口 8080），用超管 token 调：
# GET  /api/v1/admin/system-config/list   → 返回 6 条配置
# POST /api/v1/admin/system-config/update  Body: [{configKey, configValue}, ...] → 保存并写审计
```
### 前端
```powershell
cd E:\pdk\admin-vue3
npm run dev        # 打开 http://localhost:8081
```
预期：
- 用**超级管理员**账号登录后，左侧出现「系统设置」菜单；点进去看到「账号与 Token / 短信 / 安全」三组配置，可切换/修改并保存。
- 用**代理商(PARTNER)**账号登录：菜单无「系统设置」，直接访问 `/settings` 会被路由守卫弹回 `/dashboard`。
- 改 `security.encryption.enabled` 为关并保存 → 后端配置值更新（`pdk_system_config` 表对应行 `config_value='false'`），审计表新增一条 `SYSTEM_CONFIG` 记录。
