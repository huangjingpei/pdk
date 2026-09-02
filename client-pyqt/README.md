# PDK PyQt6 客户端接口 Demo

这个桌面 Demo 用于联调 Spring Boot，不包含真实业务采集逻辑。已覆盖：

- 手机号 + 短信验证码 + 密码注册（注册后自动领取试用）
- 手机号 + 密码 + 本机设备指纹登录
- 卡密激活
- 注销当前会话、解绑电脑
- 查询本人专属小号资源状态、领取 5 分钟短效资源并解密
- 上报 `SUCCESS`、账号封禁、网络失败或业务失败
- 查询剩余次数、成功/失败次数和使用明细
- 调试版可切换 `appId`，所有请求自动发送 `X-PDK-App-ID`

## 运行

先启动 MySQL、Redis 和 `backend-springboot`，然后执行：

```powershell
cd E:\pdk\client-pyqt
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python main.py
```

程序启动后首先显示独立认证窗口，包含登录、注册、卡密兑换和修改密码。注册需要手机号、密码和短信验证码；邀请码与卡密均可选。邀请码只绑定邀请代理，卡密用于激活套餐。使用后端 `local` Profile 时，固定验证码由 `PDK_SMS_FIXED_CODE` 配置；默认及生产环境均关闭。资源租约依赖 Redis；登录和电脑绑定在 Redis 不可用时会自动回退到 MySQL，但资源领取必须有 Redis。

调试工作台顶部提供业务选择：PDD=`appId=1`、zhibo-ai=`appId=2`、zhibo-live=`appId=3`。
默认部署白名单只开放 PDD；zhibo-ai / zhibo-live Handler 已由 `business/zhibo` 聚合实现，但数据库种子默认关闭。将 `PDK_ENABLED_BIZ_CODES=ZHIBO`（或与 PDD 组合）并在管理后台启用对应业务后即可使用，所有客户端 URL 保持不变。
生产客户端通过 `PDK_CLIENT_CONFIG` 指定构建配置并锁定 appId，不向最终用户开放切换；未指定时是可切换业务的调试版：

```powershell
$env:PDK_CLIENT_CONFIG = "E:\pdk\client-pyqt\config\pdd.json"
python main.py
```

也可分别使用 `zhibo-ai.json`、`zhibo-live.json`；后两者共用 `implementationGroup=zhibo`，但 appId 和服务端数据仍严格隔离。公开业务接口会返回注册策略和支持动作，`ADMIN_ONLY` 业务会自动禁用短信注册入口。

注意：`LOCAL-DEMO-SLOT-01` 是启动 SQL 写入的假资源，仅用于验证接口闭环，不能调用真实平台。

## 登录前升级

客户端现在会在显示主窗口前调用升级检查。生产构建必须在对应 JSON 中固定 `version/channel/updaterVersion`，并按 keyId 内置两类不同用途的 Ed25519 公钥；也可以在隔离测试环境临时使用 `PDK_UPDATE_ARTIFACT_PUBLIC_KEY` 与 `PDK_UPDATE_POLICY_PUBLIC_KEY`。

可选更新允许稍后处理；强制更新不会进入登录和业务界面。下载通过 Range 续传，完成后依次校验大小、SHA-256 和构件签名，再交给独立 `updater.py`。部署、包清单和密钥生成方法见 [升级系统一期实施方案](../docs/CLIENT_UPDATE_IMPLEMENTATION_PLAN.md)。
