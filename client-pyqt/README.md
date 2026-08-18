# PDK PyQt6 客户端接口 Demo

这个桌面 Demo 用于联调 Spring Boot，不包含真实业务采集逻辑。已覆盖：

- 手机号 + 短信验证码 + 密码注册（注册后自动领取试用）
- 手机号 + 密码 + 本机设备指纹登录
- 卡密激活
- 注销当前会话、解绑电脑
- 查询本人专属小号资源状态、领取 5 分钟短效资源并解密
- 上报 `SUCCESS`、账号封禁、网络失败或业务失败
- 查询剩余次数、成功/失败次数和使用明细

## 运行

先启动 MySQL、Redis 和 `backend-springboot`，然后执行：

```powershell
cd E:\pdk\client-pyqt
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python main.py
```

程序启动后首先显示独立认证窗口，包含登录、注册、卡密兑换和修改密码。注册需要手机号、密码和短信验证码；邀请码与卡密均可选。邀请码只绑定邀请代理，卡密用于激活套餐。使用后端 `local` Profile 时，固定验证码由 `PDK_SMS_FIXED_CODE` 配置；默认及生产环境均关闭。资源租约依赖 Redis；登录和电脑绑定在 Redis 不可用时会自动回退到 MySQL，但资源领取必须有 Redis。

注意：`LOCAL-DEMO-SLOT-01` 是启动 SQL 写入的假资源，仅用于验证接口闭环，不能调用真实平台。
