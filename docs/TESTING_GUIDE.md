# PDK 全链路真实测试指南（8 大场景）

本指南对应管理后台「测试平台」页（`/testing/workbench`）。该页面已从纯前端 Mock 升级为**真实调用后端接口**的测试控制台：每个场景都会真正打到 `localhost:8080` 的后端并返回真实响应，页面上会显示「期待结果」与「✅ 符合期待 / ❌ 不符期待」判定。

> 鉴权说明：后台管理员登录走 `admin` 体系（cookie `satoken`）；客户端接口走 `client` 体系（同样名为 `satoken` 的 token）。本工作台对客户端接口使用独立的 axios 实例，手动把**客户端 token** 注入 `satoken` 请求头，并附带 `X-PDK-Phone` / `X-PDK-Device-ID` 安全头，因此不会污染管理员的登录态。

---

## 0. 前置条件

| 项 | 要求 |
|---|---|
| 后端 | 已启动（`mvn spring-boot:run`，监听 8080）。本批改造需重启后端才能生效。 |
| 前端 | `npm run dev`（默认 8081）。 |
| 底层小号库存 | 场景 4 / 6 / 7 需要 `pdk_token_pool` 中存在 `HEALTHY` 的小号，且目标用户已通过试用或核销获得**已独占分配（ACTIVE）的槽位**。库存不足时 `acquire-token` 会失败。 |
| 可用激活码 | 场景 3 需要一个 `UNUSED` 状态的激活码（后台「激活码池」生成）。 |
| 测试客户端账号 | 场景 2~8 需要一个已注册客户端账号（密码已知）。没有可用账号时，先用**场景 1** 自动注册一个试用账号。 |

后端单元自动化测试已覆盖：场景 3、4、5、6、7（`CardKeyActivationServiceImplTest` / `DispatchGatewayServiceImplTest` / `DeviceSecurityInterceptorTest`）；场景 1、2、8 见 `ClientAuthScenariosTest`。本页提供的是**端到端人工验证**路径。

---

## 场景 1：客户端注册（试用资源分配）

- **目的**：验证新用户注册、短信验证码校验、试用权益与底层小号分配、客户端会话下发。
- **接口**：`POST /api/v1/client/auth/register`（先 `POST /api/v1/client/auth/sms/send` 取验证码）
- **页面操作**：在「场景 1」卡片填手机号 / 设备ID / 密码 → 点「注册并领取试用」（页面自动发送验证码并回填 debug 码）。
- **curl 等价**：
  ```bash
  # 1) 取验证码（debug 模式会在响应里回显 debugCode）
  curl -s -X POST http://localhost:8080/api/v1/client/auth/sms/send \
    -H 'Content-Type: application/json' \
    -d '{"phone":"13800138000","purpose":"REGISTER"}'
  # 2) 注册
  curl -s -X POST http://localhost:8080/api/v1/client/auth/register \
    -H 'Content-Type: application/json' \
    -d '{"phone":"13800138000","smsCode":"<debugCode>","password":"test123456","deviceId":"MAC-00-1B-44-11-3A-B7"}'
  ```
- **期待结果**：`code=200`，响应 `data` 含 `tokenName` / `tokenValue` / `status=TRIAL` / `remainingCalls>0`；`resourceAllocated` 为 `true`（库存充足）或 `false`（库存不足但权益已开）。
- **验证点**：`pdk_user` 新增一行；`pdk_user_credential` 写入 `CUSTOMER`；库存充足时 `pdk_account_assignment` 出现该用户 ACTIVE 槽位。

## 场景 2：客户端登录（设备绑定）

- **目的**：验证登录成功、密码校验、并将当前设备与账号绑定（为场景 5 互踢铺垫）。
- **接口**：`POST /api/v1/client/auth/login`
- **页面操作**：「场景 2」卡片填手机号 / 设备ID / 密码 → 点「登录并绑定设备」。
- **curl 等价**：
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/client/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"phone":"13800138000","deviceId":"MAC-00-1B-44-11-3A-B7","password":"test123456"}'
  ```
- **期待结果**：`code=200`，`data` 含 `tokenName`/`tokenValue`，并绑定该 `deviceId`。
- **验证点**：用**不同**设备ID 登录同一账号会被拒绝（`code=40103`「已在其他电脑登录」）——这正是场景 5 的前提。

## 场景 3：卡密原子核销（权益到账 + 独立财务）

- **目的**：验证激活码核销：套餐期顺延、小号独占写入 `pdk_account_assignment`、独立财务表入账。
- **接口**：`POST /api/v1/card/activate`（开放接口，无需客户端登录）
- **页面操作**：「场景 3」卡片填卡密 / 充值手机号 / 设备ID / 实收金额 → 点「执行核销」。
- **curl 等价**：
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/card/activate \
    -H 'Content-Type: application/json' \
    -d '{"cardKey":"PDK-8891-2041-9982","userPhone":"13800138000","deviceId":"MAC-00-1B-44-11-3A-B7","actualAmount":200,"orderType":"NORMAL_SALE","paymentChannel":"ALIPAY"}'
  ```
- **期待结果**：`code=200`，`data` 含 `packageName` / `extendedDays`（如 30）/ `totalAddedCalls` / `incomeOrderNo`（以 `INC-` 开头）。
- **验证点**：该卡密 `status=ACTIVATED`；`pdk_account_assignment` 多出 N 条该用户 ACTIVE 槽位；`pdk_financial_income` 多一条 `INC` 记账；用户 `remaining_calls` 增加。重复核销同一卡密应返回 `code=40002`。

## 场景 4：短效加密 Token 下发（AES-128-GCM + 字节翻转）

- **目的**：验证从已分配的健康小号中下发加密短效租约，槽位置为 BUSY。
- **接口**：`POST /api/v1/dispatch/acquire-token`（需客户端会话 + `X-PDK-Phone` / `X-PDK-Device-ID`）
- **前置**：已完成场景 1/2 拿到客户端 token；该用户有 HEALTHY 已分配槽位。
- **页面操作**：在「场景 4」选采集动作 → 点「申请加密 Token」。成功后页面会缓存 `leaseTraceId` 供场景 6/7 复用。
- **curl 等价**（`<CLIENT_TOKEN>` 取场景 2 响应的 `data.tokenValue`）：
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/dispatch/acquire-token \
    -H 'Content-Type: application/json' -H 'satoken: <CLIENT_TOKEN>' \
    -H 'X-PDK-Phone: 13800138000' -H 'X-PDK-Device-ID: MAC-00-1B-44-11-3A-B7' \
    -d '{"actionType":"GOODS_COLLECT","goodsId":"1001","timestamp":'<毫秒时间戳>'}'
  ```
- **期待结果**：`code=200`，`data` 含 `encryptedPayload`（AES-128-GCM + `0x50 0x44` 字节翻转后的 Base64 密文）、`leaseTraceId`（`TRACE-` 开头）、`expireAtTimestamp`、`remainingUserQuota`。
- **验证点**：被下发小号 `pdk_token_pool.health_status=BUSY`、`lease_client_phone=该手机号`；`pdk_resource_lease` 写入租约。

## 场景 5：单设备互踢拦截（ERR_DEVICE_KICK_OUT）

- **目的**：验证同一账号只能在一台设备活跃，异地设备请求被网关拦截。
- **接口**：复用 `POST /api/v1/dispatch/acquire-token`，但把 `X-PDK-Device-ID` 换成「入侵设备」。
- **前置**：场景 2 已用设备 A 登录并绑定。
- **页面操作**：「场景 5」填「入侵设备ID」（默认 `MAC-00-99-99-99-99-99`，与场景 2 不同）→ 点「模拟异地设备 B 调用」。
- **curl 等价**：
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/dispatch/acquire-token \
    -H 'Content-Type: application/json' -H 'satoken: <CLIENT_TOKEN>' \
    -H 'X-PDK-Phone: 13800138000' -H 'X-PDK-Device-ID: MAC-00-99-99-99-99-99' \
    -d '{"actionType":"GOODS_COLLECT","timestamp":'<毫秒时间戳>'}'
  ```
- **期待结果**：被拦截，`code=40103`，`message` 含 `ERR_DEVICE_KICK_OUT`。**页面显示 ✅ 符合期待**（因为这是预期中的拒绝）。
- **验证点**：后端日志出现 `单设备互踢触发`。

## 场景 6：业务成功上报扣费（SUCCESS）

- **目的**：验证一次成功调用扣 1 次、写成功流水、槽位释放回 HEALTHY、用户总池派生重算。
- **接口**：`POST /api/v1/dispatch/report-result`
- **前置**：先执行场景 4 拿到 `leaseTraceId`（页面已自动缓存）。
- **页面操作**：「场景 6」的 `leaseTraceId` 默认等于场景 4 缓存值 → 点「上报成功并扣费」。
- **curl 等价**：
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/dispatch/report-result \
    -H 'Content-Type: application/json' -H 'satoken: <CLIENT_TOKEN>' \
    -H 'X-PDK-Phone: 13800138000' -H 'X-PDK-Device-ID: MAC-00-1B-44-11-3A-B7' \
    -d '{"leaseTraceId":"<TRACE_ID>","status":"SUCCESS","responseDurationMs":88}'
  ```
- **期待结果**：`code=200`，`message=上报处理成功`。
- **验证点**：`pdk_dispatch_log` 多一条 `exec_status=SUCCESS`、`deduct_count=1`；对应槽位 `used_calls+1`；用户 `remaining_calls` 减少 1；被下发小号 `health_status` 回到 `HEALTHY`。重复上报同一 `leaseTraceId` 应幂等（不再扣费）。

## 场景 7：故障免责扣费与自动拉黑（FAIL_ACCOUNT_BANNED）

- **目的**：验证底层官方账号被封时，本次调用免责扣 0 次，并自动拉黑故障槽位。
- **接口**：`POST /api/v1/dispatch/report-result`（`status=FAIL_ACCOUNT_BANNED`）
- **前置**：需要有可下发的健康小号（页面内部先自动领一笔新租约再上报）。
- **页面操作**：「场景 7」点「模拟官方账号失效自愈」（页面先 `acquire` 再 `report`）。
- **curl 等价**：
  ```bash
  # 先领租约拿到 TRACE_ID，再上报故障
  curl -s -X POST http://localhost:8080/api/v1/dispatch/report-result \
    -H 'Content-Type: application/json' -H 'satoken: <CLIENT_TOKEN>' \
    -H 'X-PDK-Phone: 13800138000' -H 'X-PDK-Device-ID: MAC-00-1B-44-11-3A-B7' \
    -d '{"leaseTraceId":"<TRACE_ID>","status":"FAIL_ACCOUNT_BANNED","errorMessage":"pdd account banned"}'
  ```
- **期待结果**：`code=200`，`exec_status=TOKEN_FAIL`。
- **验证点**：对应小号 `health_status=FAULT_BLACK`；用户 `remaining_calls` **不变**（免责不扣）；`pdk_dispatch_log.deduct_count=0`。

## 场景 8：解绑设备（需重新登录）

- **目的**：验证解绑后设备清空、客户端会话注销，旧设备后续请求触发互踢。
- **接口**：`POST /api/v1/client/auth/unbind-device`
- **前置**：已完成场景 2 登录。
- **页面操作**：「场景 8」点「解绑当前电脑」。
- **curl 等价**：
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/client/auth/unbind-device \
    -H 'satoken: <CLIENT_TOKEN>' -H 'X-PDK-Phone: 13800138000'
  ```
- **期待结果**：`code=200`，`message` 含「解绑」。页面会自动清空客户端会话。
- **验证点**：`pdk_user.device_id` 置空；该客户端 token 失效；之后用旧设备ID 请求会返回 `40103`（与场景 5 一致）。

---

## 3. 一键 curl 脚本（冒烟）

把下面脚本里的 `<时间戳>` 替换为 `$(date +%s)000`、`<CLIENT_TOKEN>` 替换为登录响应里的 `data.tokenValue` 即可串联跑完 8 个场景（场景 3 需替换为真实卡密）。

```bash
BASE=http://localhost:8080
PHONE=13800138000
DEV=MAC-00-1B-44-11-3A-B7

# S1 注册
CODE=$(curl -s -X POST $BASE/api/v1/client/auth/sms/send -H 'Content-Type: application/json' -d "{\"phone\":\"$PHONE\",\"purpose\":\"REGISTER\"}" | grep -o '"debugCode":"[0-9]*"' | grep -o '[0-9]*')
curl -s -X POST $BASE/api/v1/client/auth/register -H 'Content-Type: application/json' -d "{\"phone\":\"$PHONE\",\"smsCode\":\"$CODE\",\"password\":\"test123456\",\"deviceId\":\"$DEV\"}"

# S2 登录（提取 token）
TOKEN=$(curl -s -X POST $BASE/api/v1/client/auth/login -H 'Content-Type: application/json' -d "{\"phone\":\"$PHONE\",\"deviceId\":\"$DEV\",\"password\":\"test123456\"}" | grep -o '"tokenValue":"[^"]*"' | sed 's/.*:"//;s/"//')
echo "CLIENT_TOKEN=$TOKEN"

# S3 核销（替换为真实卡密）
curl -s -X POST $BASE/api/v1/card/activate -H 'Content-Type: application/json' -d "{\"cardKey\":\"PDK-8891-2041-9982\",\"userPhone\":\"$PHONE\",\"deviceId\":\"$DEV\",\"actualAmount\":200}"

# S4 领租约
TRACE=$(curl -s -X POST $BASE/api/v1/dispatch/acquire-token -H 'Content-Type: application/json' -H "satoken: $TOKEN" -H "X-PDK-Phone: $PHONE" -H "X-PDK-Device-ID: $DEV" -d "{\"actionType\":\"GOODS_COLLECT\",\"timestamp\":$(date +%s)000}" | grep -o '"leaseTraceId":"[^"]*"' | sed 's/.*:"//;s/"//')
echo "LEASE=$TRACE"

# S5 互踢
curl -s -X POST $BASE/api/v1/dispatch/acquire-token -H 'Content-Type: application/json' -H "satoken: $TOKEN" -H "X-PDK-Phone: $PHONE" -H "X-PDK-Device-ID: MAC-00-99-99-99-99-99" -d "{\"actionType\":\"GOODS_COLLECT\",\"timestamp\":$(date +%s)000}"

# S6 成功上报
curl -s -X POST $BASE/api/v1/dispatch/report-result -H 'Content-Type: application/json' -H "satoken: $TOKEN" -H "X-PDK-Phone: $PHONE" -H "X-PDK-Device-ID: $DEV" -d "{\"leaseTraceId\":\"$TRACE\",\"status\":\"SUCCESS\",\"responseDurationMs\":88}"

# S7 故障拉黑（先领新租约）
TRACE2=$(curl -s -X POST $BASE/api/v1/dispatch/acquire-token -H 'Content-Type: application/json' -H "satoken: $TOKEN" -H "X-PDK-Phone: $PHONE" -H "X-PDK-Device-ID: $DEV" -d "{\"actionType\":\"ORDER_PULL\",\"timestamp\":$(date +%s)000}" | grep -o '"leaseTraceId":"[^"]*"' | sed 's/.*:"//;s/"//')
curl -s -X POST $BASE/api/v1/dispatch/report-result -H 'Content-Type: application/json' -H "satoken: $TOKEN" -H "X-PDK-Phone: $PHONE" -H "X-PDK-Device-ID: $DEV" -d "{\"leaseTraceId\":\"$TRACE2\",\"status\":\"FAIL_ACCOUNT_BANNED\"}"

# S8 解绑
curl -s -X POST $BASE/api/v1/client/auth/unbind-device -H "satoken: $TOKEN" -H "X-PDK-Phone: $PHONE"
```

---

## 4. 常见问题

- **场景 4/6/7 返回 `40302` 配额耗尽或 `41001` 租约过期**：说明该用户没有可用已分配小号或租约已超时（默认 300s），请改用有配额/刚领租约的账号，或补充 `pdk_token_pool` 健康库存。
- **涉及客户端接口返回 `40100`**：说明没带上有效客户端 token——请先跑场景 1 或场景 2。
- **页面改动需重启后端吗**：前端热更新即生效；但本批「测试平台」只读已有接口，无需后端重启（除非后端尚未部署最新代码）。

---

## 5. PyQt 桌面校验器（client-pyqt）

除网页工作台外，仓库内 `client-pyqt/` 提供一套 **PyQt6 桌面版全链路校验器**，逻辑与网页工作台完全一致，并额外覆盖边界测试。它把 8 个功能场景与 16 个边界用例做成了可点击验证的界面，也提供无界面的命令行校验器。

### 目录结构
```
client-pyqt/
├── pdk_client.py        # GUI 无关的核心 API 客户端（真实接口 + 报文解密）
├── pdk_testrunner.py    # 8 场景 + 16 边界 的执行器（GUI / CLI 共用）
├── run_tests.py         # 无界面命令行校验器
└── main.py              # PyQt6 桌面界面
```

### Swagger UI 风格接口调试

`client-pyqt/main.py` 启动后，「接口调试」页按后端真实接口列出 13 张独立卡片（与 Swagger UI 类似），每张卡片包含：

- **请求行**：METHOD（GET/POST 颜色徽章）+ Path + 接口中文名称。
- **参数表单**：根据接口定义动态生成输入框；手机号 / 密码 / 设备ID / 短信验证码 自动从顶部「连接与客户端身份」带入；注册手机号、密码留空时会自动生成随机值。
- **请求预览**：随输入实时更新，清晰展示将要发送的 `method`、`url`、`headers`、`body`，并对 `password` / `token` 等字段脱敏。
- **发送请求**：在后台线程调用 `pdk_client` 对应方法，避免界面卡死。
- **响应结果**：HTTP 状态码 + 业务 `code` / `message` + 完整响应报文；`code=200` 绿色、`业务异常` 红色、`本地异常/未连通` 黄色。
- **场景快捷入口**：部分卡片右上角带「作为 Sx 运行」按钮，可一键以场景方式执行（自动处理前后依赖与断言）。

当前卡片覆盖的接口：

| 接口 | METHOD | 关键输入参数 |
|---|---|---|
| 发送短信验证码 | POST | phone, purpose |
| 客户端注册（试用） | POST | phone, password, deviceId, smsCode, invitationCode |
| 客户端登录（绑设备） | POST | phone, password, deviceId |
| 注销会话 | POST | — |
| 解绑设备 | POST | — |
| 修改密码 | POST | phone, oldPassword, newPassword |
| 卡密核销 | POST | cardKey, userPhone, deviceId |
| 加密 Token 下发 | POST | actionType, goodsId |
| 执行结果上报 | POST | leaseTraceId, status, responseDurationMs, errorMessage |
| 账号资料 | GET | — |
| 使用统计 | GET | — |
| 小号使用情况 | GET | — |
| 已核销卡密 | GET | — |

### HTTP 调试日志（每条请求显示「请求 / 响应 / 期待」）
- `pdk_client.PdkApiClient.request` 内置 `on_request` 钩子，每次调用都会回传结构化记录（方法、完整 URL、请求体/查询参数、HTTP 状态码、业务 code/msg、响应报文、当前「期待」注解）。
- GUI「响应日志」页把每条记录渲染为三段：**▶ 请求什么**（METHOD + URL + 参数）、**◀ 响应什么**（HTTP 状态 + code/msg + 报文）、**🎯 期待什么**（场景/边界的 expected，或手动按钮的合理预期）。
- 八个功能场景与十六个边界用例在调用前会自动把自身 `expected` 注入日志注解；手动按钮（发送验证码 / 登录 / 注销 / 解绑 / 查询小号）也各自标注期待。
- 调试日志对 `password` / `token` 等敏感字段自动脱敏（`***`），不影响明文请求体的排错可读性。
- 跨线程安全：场景在后台线程跑，HTTP 记录经 `MainWindow.http_log_ready` 信号排队回主线程渲染，避免界面卡死或崩溃。

### 运行方式
```bash
# 1) 安装依赖（已为你建好隔离 venv，直接用它即可）
VENV=~/.workbuddy/binaries/python/envs/pdk-client/Scripts/python.exe
$VENV -m pip install requests cryptography PyQt6   # 仅首次

# 2) 命令行一键校验（无需显示器）
$VENV client-pyqt/run_tests.py -u http://localhost:8080

# 3) 启动桌面界面
$VENV client-pyqt/main.py
```

### 前置环境变量（跑通完整链路必需）
| 变量 | 作用 | 默认 |
|---|---|---|
| `PDK_API_BASE` | 后端地址 | `http://localhost:8080` |
| `PDK_TEST_CARD_KEY` | 一条真实 `UNUSED` 激活码，用于跑通场景 3 | 空（不配则场景 3 SKIP） |
| `PDK_TEST_SMS_CODE` | 指定短信验证码（见下） | 空 |

> **注册自动化说明**：后端 `SmsCodeService` 仅在 `pdk.sms.local.fixed-code-enabled=true` 时回显验证码；否则返回 `null`（模拟真实下发）。验证码获取优先级：**GUI「短信验证码」输入框 / CLI `--sms-code`** > 响应回显的 `debugCode` > 环境变量 `PDK_TEST_SMS_CODE`。建议本地开启：
> ```yaml
> pdk:
>   sms:
>     local:
>       fixed-code-enabled: true
>       fixed-code: "123456"
> ```
> 未配置时场景 1 会明确 SKIP 并提示如何开启。GUI 的「发送验证码」按钮在 fixed-code 模式下会自动回填输入框。
>
> **60 秒限频说明**：后端 `SmsCodeService` 对同一「手机号 + 用途」有 60 秒一条的限频（返回 `42901 短信发送过于频繁，请60秒后重试`）。网页工作台、PyQt GUI「发送验证码」按钮、场景 1 内部的下发，对同一手机号共享这一限额。为此桌面校验器做了两层处理：
> - GUI「发送验证码」按钮成功后进入 60 秒倒计时，防止连点；命中 `42901` 时提示「已发的验证码 5 分钟内仍有效，直接填入即可」。
> - 场景 1 遇到 `42901` 不再判 FAIL：只要有手输 / `debugCode` / 环境变量验证码，就复用已发验证码继续注册（结果明细会标注「60 秒限频内复用已发验证码」）；拿不到验证码值则 SKIP 并给出指引。

### 小号使用情况同步（下单成功 / 失败后）
- **S6 模拟下单成功**：上报 SUCCESS 后自动调 `/api/v1/client/resources/status` + `/api/v1/client/account/usage`，展示「槽位 used_calls 变化、用户剩余次数 Δ=1、成功/失败统计」。
- **S7 模拟下单失败（底层账号被封）**：上报 FAIL_ACCOUNT_BANNED 后同样同步，展示「Δ=0 免责扣费、槽位自愈替换、上报统计」；FAULT_BLACK 拉黑发生在平台侧 Token 池，可在管理后台「调度中心」查看。
- GUI 连接面板提供「查询小号使用情况」按钮，登录后可随时手动同步（完整 JSON 进「响应日志」页）。

### 边界测试清单（无需登录即可验证的部分已实测通过）
- B1 注册非法手机号 → `40001` · B2 重复手机号 → `40010` · B3 弱密码 → `40001`
- B4 错误验证码 → `40011` · B5 登录账号不存在 → `40100`
- B6 登录密码错误 → `40105` · B7 登录设备不一致 → `40103`
- B8 卡密格式非法 → `40001` · B9 卡密不存在 → `40001` · B10 核销手机号非法 → `40001`
- B11 下发缺失设备头 → `40101` · B12 非法业务动作 → `40001`
- B13 非法上报状态 → `40001` · B14 上报租约为空 → `40001` · B15 未知租约 → `41001`
- B16 未登录解绑 → `40100`

> 本校验器已对当前运行中的后端实测：无需会话的边界用例全部 PASS；需要登录态的场景（S2~S8 及 B6/B7/B11~B15）在开启 fixed-code 短信模式并提供真实激活码后，可一键跑通完整 8 场景。
