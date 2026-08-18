# PDK (拼多客) 项目地图 · 团队协作基线

> 用途：这是 PDD 云控商业化平台的整体地图。后续「逐功能调试」都以此为准。
> 维护约定：每次改完一个功能，把关键结论/路径更新到这里，避免文档漂移。

---

## 1. 这个项目是什么

PDK（拼多客）是一个**拼多多公共账号 / Token 资产池调度 + 卡密鉴权 + 财务双向审计**的商业化中台。
核心商业模式：公司集中采购拼多多账号 Token → 按套餐（X 账号 × Y 次）切片卖给客户 → 客户客户端轮巡调度 → 成功才扣次、底层账号异常免责自愈。

工程不是一个单体应用，而是**四个相对独立的部件**：

| 部件 | 位置 | 技术栈 | 角色 | 端口 |
| --- | --- | --- | --- | --- |
| **React 原型（中台 Demo）** | 仓库根 `src/` + `package.json` | React 19 + Vite 6 + Tailwind + lucide-react | 产品原型 / PRD 可视化（**纯前端 mock，不连后端**） | 3000 |
| **后端** | `backend-springboot/` | Spring Boot 3.3 + MyBatis-Plus + Sa-Token + Redis + MySQL | 真实业务核心（鉴权、卡密、调度、财务） | 8080 |
| **管理后台** | `admin-vue3/` | Vue 3 + Element Plus + Pinia + Vue Router + Vite | 多角色管理前端（接真实后端） | 8081 |
| **客户端 Demo** | `client-pyqt/` | PyQt6 | 桌面客户端联调 Demo | 无 |

> 注：根目录的 `package.json` 的 `name` 是 `react-example`，它驱动的是 **React 原型**，不是 admin。admin 有自己独立的 `admin-vue3/package.json`。

---

## 2. 各部件如何运行（本地）

### 2.1 后端（先起，依赖 MySQL 8 + Redis 7）
```powershell
cd E:\pdk\backend-springboot
$env:DB_USER='root'
$env:DB_PASS='你的MySQL密码'
# 如需本地固定验证码（联调用）：再设 $env:PDK_SMS_PROVIDER='local'
mvn spring-boot:run
```
- 启动后自动建库 `pdk_biz_db`、自动执行 `src/main/resources/schema-mysql.sql`（可重复执行）。
- 健康检查：`curl http://127.0.0.1:8080/actuator/health` → `{"status":"UP"}`
- 默认超管账号：`13454118762 / admin123`（见 `schema-mysql.sql` 种子，部署前必须改）。

### 2.2 管理后台（依赖后端已起）
```powershell
cd E:\pdk\admin-vue3
npm install
npm run dev
```
- 访问 `http://localhost:8081`。
- 开发态 vite proxy 把 `/api` 转发到 `http://localhost:8080`。
- 登录即用上面超管账号。

### 2.3 React 原型（独立，不依赖后端）
```powershell
cd E:\pdk
npm install
npm run dev
```
- 访问 `http://localhost:3000`。
- **纯展示/演示**：所有数据写在 `src/App.tsx` 的 `useState` 里，不发任何后端请求。

### 2.4 客户端 Demo（PyQt）
```powershell
cd E:\pdk\client-pyqt
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python main.py
```

---

## 3. React 原型内部结构（你要重点调 UI 的地方）

入口：`src/main.tsx` → `src/App.tsx`（单文件状态管理，所有 mock 数据在此）。
顶部 8 个 Tab，每个对应一个组件：

| Tab 键 | 组件文件 | 内容 | 数据来源 |
| --- | --- | --- | --- |
| `DOCS` | `components/DocumentViewer.tsx` | 架构与 PRD 长文档（含 DDL、事务代码、10 铁律） | 静态 |
| `SPRINGBOOT` | `components/SpringBootProjectViewer.tsx` | 后端源码浏览 | 静态 |
| `TESTING` | `components/TestWorkbench.tsx` | 单元/人工测试工作台 | 静态 |
| `CLIENT_SDK` | `components/ClientSdkIntegrationDoc.tsx` | 客户端对接 SDK 指南（含 4 语言解密代码） | 静态 |
| `ROLES` | `components/RoleMatrixView.tsx` | 三角色权限矩阵 + 4 大决策对比 | 静态 |
| `FINANCE` | `components/FinancialCenter.tsx` | 财务双向审计中心（收入/支出/LTV） | `App.tsx` mock |
| `CARD_STUDIO` | `components/CardKeyStudio.tsx` | 卡密生成 + 原子激活 + 调度模拟 | `App.tsx` mock |
| `SECURITY` | `components/SecurityDemo.tsx` | 通信加密实测演示 | 静态 |

类型定义：`src/types.ts`（PackageTier / UserAccount / CardKeyEntity / FinancialIncomeEntity / CompanyExpenseEntity / FinancialAuditReport）。

> ⚠️ 两个**未被引用**的组件（死代码，见 `ISSUES_AND_INCONSISTENCIES.md`）：
> `components/ArchitectureSimulator.tsx`、`components/SecurityInspector.tsx` —— 在 `App.tsx` 里没有 import，当前界面看不到。

---

## 4. 后端 API 全景（真实实现，12 个 Controller）

所有路径前缀 `/api/v1`。两套登录：`admin` 与 `client` 双 Sa-Token 实例。

> **鉴权澄清（2026-08-18 清理）**：本项目**从不使用 JWT**，鉴权完全由 Sa-Token 负责——`adminStpLogic` 与 `clientStpLogic` 两个实例，令牌名 `satoken`，登录成功后返回 `tokenName` + `tokenValue`。仓库内曾出现的 `jwt`/`eyJ` 字符串，全部来自第三方依赖（主要是 `google-auth-library`，已随死亡依赖 `@google/genai` 一并从 `package.json` 与 `node_modules` 移除），与本项目自身鉴权无关。后续请勿再引入 JWT。

**客户端 / 调度类**
- `ClientAuthController` (`/api/v1/client/auth`)：`POST /sms/send`、`/register`、`/login`、`/change-password`、`/logout`、`/unbind-device`
- `CardKeyActivationController` (`/api/v1/card`)：`POST /activate`
- `ClientAccountController` (`/api/v1/client`)：`GET /account/profile`、`/account/usage`、`/resources/status`、`/account/card`、`POST /resources/acquire`、`POST /resources/report`
- `DispatchGatewayController` (`/api/v1/dispatch`)：`POST /acquire-token`、`POST /report-result`（要求头 `X-PDK-Phone` + `X-PDK-Device-ID`）

**管理类**（需 `@RequirePermission` + `AdminAuthInterceptor`）
- `AdminAuthController` (`/api/v1/admin/auth`)：`POST /login`、`GET /me`、`POST /logout`
- `AdminCardKeyController` (`/api/v1/admin/card`)：`POST /batch-generate`、`GET /list`、`POST /{cardKey}/renew`、`PUT /void-all`、`PUT /{cardKey}/void`
- `AdminDashboardController` (`/api/v1/admin/dashboard`)：`GET /summary`
- `AdminPackageController` (`/api/v1/admin/package`)：`GET /list`、`POST /`、`PUT /{id}/disable`
- `AdminSalesController` (`/api/v1/admin/sales`)：`GET /list`
- `AdminTokenController` (`/api/v1/admin/token`)：`GET /list`、`POST /`、`PUT /{id}/status`
- `AdminUserController` (`/api/v1/admin/user`)：`GET /list`、`POST /{id}/unbind-device`、`PUT /{id}/role`、`PUT /{id}/status`
- `FinancialAuditController` (`/api/v1/admin/finance`)：`GET /summary`、`/incomes`、`/expenses`、`POST /expenses/purchase-token`

**鉴权机制**
- `AdminAuthInterceptor`：校验 admin 会话 + `@RequirePermission` 注解 + `RolePermissions` 静态矩阵。
- `DeviceSecurityInterceptor`：`/api/v1/dispatch/**` 与 `/api/v1/client/**`：校验 client 会话 + 强制 `X-PDK-Phone`/`X-PDK-Device-ID` + 手机号与会话一致 + 单设备互踢（`40103`）。

**加密实现（真实）**
- `common/utils/AesByteFlipUtils.java`：`Key = SHA-256(ROOT_SALT + "_" + epochSecond/60/10)` 取前 16 字节 → AES-128-GCM → 拼 `0x50 0x44` 魔数 → 整段字节倒序 → Base64。
- `ROOT_SALT` 来自环境变量 `PDK_SECURITY_ROOT_SALT`，缺省 `PDK_SECRET_SALT_2026_ENTERPRISE`。

---

## 5. 管理后台路由（真实，接后端）

共 10 条路由，守卫读 `localStorage` 里的 `AdminSession.permissions`。

| 路由 | 视图 | 权限 |
| --- | --- | --- |
| `/login` | `auth/Login.vue` | public |
| `/dashboard` | `dashboard/Dashboard.vue` | `dashboard:view` |
| `/finance/income` | `finance/IncomeAudit.vue` | `finance:view` |
| `/finance/expense` | `finance/ExpenseAudit.vue` | `finance:view` |
| `/sales` | `finance/IncomeAudit.vue`（复用） | `sales:view` |
| `/card/generator` | `card/CardGenerator.vue` | `card:view` |
| `/token/pool` | `token/TokenPoolManager.vue` | `token:view` |
| `/testing/workbench` | `testing/TestingWorkbench.vue` | `dispatch:view` |
| `/package/manager` | `package/PackageManager.vue` | `package:view` |
| `/user/manager` | `user/UserManager.vue` | `user:view` |

API 层：只有 `src/api.ts`（axios 实例 + 拦截器自动注入 token），**没有独立的 service 模块**，端点字符串散落在各 `.vue` 中。

---

## 6. 数据库真实表（15 张，`pdk_biz_db`）

业务核心表：`pdk_user`、`pdk_card_key`、`pdk_financial_income`、`pdk_company_expense`、`pdk_dispatch_log`、`pdk_admin_audit_log`、`pdk_token_pool`（⚠️ 文档里叫 `pdk_pdd_account_pool`）、`pdk_package_plan`（⚠️ 文档里叫 `pdk_package_template`）、`pdk_account_assignment`、`pdk_user_credential`、`pdk_sms_verification`、`pdk_invitation_code`、`pdk_user_referral`、`pdk_admin_user`。

---

## 7. 一页速记（给后续对话用）

- **要改 UI / 原型** → 动 `src/`（React 原型）或 `admin-vue3/src/views/`（真后台）。
- **要改业务逻辑 / 接口** → 动 `backend-springboot/`。
- **React 原型和后端目前完全脱节**（原型是 mock），这是后续「接真数据」类需求的核心前提。
- **所有「不一致」问题**集中在 `docs/ISSUES_AND_INCONSISTENCIES.md`，逐条可作为调试任务。
