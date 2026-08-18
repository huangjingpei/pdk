# PDK 不一致 & 待修清单（逐功能调试的 backlog）

> 这是扫描工程后发现的「文档与实际 / 原型与后端 / 内部自相矛盾」问题清单。
> 每条都可以单独作为一个调试任务。优先级：`P0`=会影响联调或误导开发；`P1`=体验/正确性；`P2`=整洁度。
> 状态列用于我们协作时打勾。

---

## P0 — 会导致联调失败 / 严重误导

### 1. React 原型的 SDK 文档接口路径与后端对不上
- 位置：`src/components/ClientSdkIntegrationDoc.tsx`
- 问题：文档写的路径在后端**不存在**：
  - `POST /api/v1/auth/register-trial` —— 后端实际是 `POST /api/v1/client/auth/register`（无 `register-trial`）。
  - `POST /api/v1/card/activate` ✅ 存在（这个是对的）。
  - `POST /api/v1/dispatch/acquire-token`、`/report-result` ✅ 存在。
  - 但文档的「统一请求头」写 `Authorization: Bearer` + `X-PDK-Device-ID` + `X-PDK-Timestamp`，而 `ClientSdkIntegrationDoc.tsx` 时序图里又用了 `/api/v1/device/heartbeat`、`/api/v1/card/activate` —— 多处口径混用。
- 影响：照这份文档写客户端会调不通。
- 修复方向：以 `backend-springboot` 的真实 Controller 为唯一事实源，统一原型文档里的路径与请求头。
- **状态**：✅ 已修复（2026-08-18）—— 原型 SDK 文档 / `SecurityDemo` 的接口路径与「统一请求头」已统一到真实后端：注册走 `POST /api/v1/client/auth/register`（前置 `sms/send`），调度走 `POST /api/v1/dispatch/acquire-token`；请求头改为 `X-PDK-Phone`/`X-PDK-Device-ID` + Sa-Token 会话令牌（不再用 `Authorization: Bearer`/`X-PDK-Timestamp`）。

### 2. `SecurityDemo.tsx` 里的接口路径是编的
- 位置：`src/components/SecurityDemo.tsx`（第 128 行附近）
- 问题：`POST /api/v1/sec/payload`、`/api/pdd/dispatch`、`/api/pdd/dispatch` —— 后端**没有任何 `/sec/payload` 或 `/api/pdd/dispatch`** 端点。真实加密下发走 `POST /api/v1/dispatch/acquire-token`。
- 修复方向：把演示里的报文路径改成真实端点，或明确标注为「示意」。
- **状态**：✅ 已修复（2026-08-18）—— `SecurityDemo.tsx` 中的 `/api/v1/sec/payload`、`/api/pdd/dispatch` 已改为真实端点 `POST /api/v1/dispatch/acquire-token`。

### 3. 文档里的数据库表名与后端真实表名不符
- 位置：`src/components/DocumentViewer.tsx`（DDL 章节）、`CLIENT_INTEGRATION_GUIDE.md`、`IMPLEMENTATION_ANALYSIS.md`
- 问题（两处命名错位）：
  - 文档写 `pdk_pdd_account_pool` —— 后端真实是 **`pdk_token_pool`**。
  - 文档写 `pdk_package_template` —— 后端真实业务表是 **`pdk_package_plan`**（`pdk_package_template` 是 DDL 里遗留的僵尸兼容表，无任何代码读写）。
- 影响：照 DDL 章节理解数据模型会和真实 Mapper 对不上。
- 修复方向：以 `schema-mysql.sql` 为事实源，统一文档表名（或删掉僵尸表 `pdk_package_template`）。

### 4. 三份文档的「客户端接口路径」三套口径
- `IMPLEMENTATION_ANALYSIS.md`：用 `/api/v1/client/auth/...`、`/api/v1/client/resources/acquire`
- `CLIENT_INTEGRATION_GUIDE.md`：用 `/api/v1/card/activate`、`/api/v1/dispatch/acquire-token`（带 X-PDK-Phone/Device-ID 头）
- `src/components/ClientSdkIntegrationDoc.tsx`：用 `/api/v1/auth/register-trial`、`/api/v1/dispatch/...`
- 事实：后端**两套都有**（`/api/v1/client/**` 与 `/api/v1/dispatch/**` 并存），但 `register-trial` 不存在。需要收敛成一份权威接口表。
- **状态**：✅ 已修复（2026-08-18）—— 三处文档/原型的客户端接口口径已统一：`IMPLEMENTATION_ANALYSIS.md`、`CLIENT_INTEGRATION_GUIDE.md`、`ClientSdkIntegrationDoc.tsx` 现在一致使用 `POST /api/v1/client/auth/register`（前置 `sms/send`）+ `POST /api/v1/dispatch/acquire-token` / `report-result`。`PROJECT_MAP.md` 的接口总表为权威来源。

---

## P1 — 正确性 / 体验问题

### 5. 加密时间窗口描述前后矛盾
- `DocumentViewer.tsx` / 加密规范：密钥每 **10 分钟**滚动（`epochSecond/60/10`）。
- `SecurityDemo.tsx`（第 145 行）：写「密钥每 **30 秒**动态轮转」。
- 后端真实：10 分钟窗口（容错 ±1 窗口）。
- 修复方向：统一成 10 分钟，并以代码为准。
- **状态**：✅ 已修复（2026-08-18）—— `SecurityDemo.tsx` 的「30 秒」已改为「10 分钟（600 秒）」，与 `CLIENT_INTEGRATION_GUIDE.md` 及 SDK 解密代码一致。

### 6. `DocumentViewer.tsx` 加密算法名写错
- 第 542 行写「动态混淆 **AES-128-CBC**」，但全文其它处和后端都是 **AES-128-GCM**。
- 修复方向：CBC 改为 GCM。

### 7. 管理后台 `TestingWorkbench.vue` 是纯假数据桩
- 位置：`admin-vue3/src/views/testing/TestingWorkbench.vue`
- 问题：4 个测试场景（核销、加密下发、互踢、故障拉黑）全部写死 `reactive` 结果，**不发任何后端请求**。路由却叫「全链路测试工作台」，名不副实。
- 修复方向：要么接真实接口，要么改名明确为「演示桩」，避免误导。

### 8. 加密根盐有硬编码兜底默认值
- 后端 `AesByteFlipUtils`：`PDK_SECURITY_ROOT_SALT` 缺省 `PDK_SECRET_SALT_2026_ENTERPRISE`。若部署忘设环境变量，密钥可被推导。
- 修复方向：生产环境缺省值直接启动失败（fail-fast），不要兜底。

---

## P2 — 整洁度 / 死代码

### 9. 两个 React 组件是孤儿（死代码）
- `src/components/ArchitectureSimulator.tsx`、`src/components/SecurityInspector.tsx`
- 在 `App.tsx` 里**从未 import**，界面上完全看不到。
- 其中 `ArchitectureSimulator` 其实功能比 `CardKeyStudio` 的调度模拟更完整（有账号槽位可视化、低余量预警），`SecurityInspector` 有可交互的加解密演示框。
- 处理方向（二选一）：
  - 接进 `App.tsx` 的某个 Tab（推荐：把 `ArchitectureSimulator` 替换/合并进 `CARD_STUDIO` 或单独加 Tab；`SecurityInspector` 作为 `SECURITY` 的增强）；
  - 或删除，避免维护负担。

### 10. `@google/genai` 依赖引入但从未使用
- `package.json` 依赖 `@google/genai ^2.4.0`，但 `src/` 下**没有任何文件 import 它**。
- 也就是说这个「Gemini 产品原型」目前并没有真正接入 Gemini。
- 处理方向：
  - 如果想让原型有 AI 能力（比如文档问答、接口生成），再接；
  - 否则从 `package.json` 移除，避免误导和打包体积。
- **状态**：✅ 已处理（2026-08-18）—— 已从根 `package.json` 移除 `@google/genai`，并 `npm install` 剪掉其传递依赖 `google-auth-library` 及 `jws`/`jwa`/`ecdsa-sig-formatter` 整棵 JWT 子树（node_modules 与 package-lock.json 均已 0 提及）。同时把 `ClientSdkIntegrationDoc.tsx` 里那个看着像 JWT 的 mock token 值 `pdk_usr_eyJhbGciOiJIUzI1...` 改为 Sa-Token 风格的 `pdk_usr_8f4c2b1a6d3e4f5a9b7c2e1d`。详见 PROJECT_MAP.md「鉴权澄清」。
- **补充（JWT 澄清）**：本项目**从不使用 JWT**，鉴权 100% 由 Sa-Token 负责。仓库内残留的 `jwt`/`eyJ` 字样现已清零（除第三方库 `mime-db` 的 `application/jwt` MIME 类型定义、vueuse 元数据里的 `jwt-decode` 集成登记——这两者非鉴权代码，属正常存在，无需处理）。

### 11. 管理后台没有集中 API service 层
- 所有端点字符串散落在各 `.vue` 里（仅 `src/api.ts` 提供 axios 实例）。
- 风险：后端改路径时容易漏改。
- 处理方向：抽一个 `src/api/` 模块按 Controller 归类端点（可选优化，非阻塞）。

### 12. `/sales` 路由复用 `IncomeAudit.vue`
- 没有独立销售视图，若后续销售与收入展示逻辑分叉需重构。当前可工作。

---

## 附：已确认「没问题」的部分（不必重复排查）
- 后端 12 个 Controller 真实存在，无显式 TODO/FIXME 占位。
- 管理后台所有「真实」API 调用都能在后端找到对应端点（除测试页根本不发请求）。
- 后端加密实现（AES-GCM + 字节翻转 + 时间窗口）逻辑自洽，与 `CLIENT_INTEGRATION_GUIDE.md` 的解密示例一致。
- 权限矩阵：`RolePermissions` 静态常量 + `AdminAuthInterceptor` 真实生效，前端守卫与之一致。
