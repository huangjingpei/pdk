# ZHIBO_LIVE MediaMTX 测试说明

## 1. 自动化测试

在 `backend-springboot` 下执行：

```powershell
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.2\plugins\maven\lib\maven3\bin\mvn.cmd' test
```

直播相关测试覆盖：

- appId=3 用户可申请随机票据，其他业务不能申请；
- 无票据、伪造票据、错误内部令牌拒绝；
- 首次有效票据允许，相同票据换 connection ID 重放拒绝；
- Controller 拒绝一定保持非 2xx，允许使用裸 204；
- 客户端明文 JSON 经加密 Advice 检查后仍可被 Jackson 正常读取。

当前全量实测结果：`68 tests, 0 failures, 0 errors, BUILD SUCCESS`。

## 2. 后端真实 HTTP 契约测试

准备一个属于 bizId=3 的 ACTIVE 用户，为测试设备分配并绑定一张 ACTIVE 许可证，确保许可证
`expire_at` 在未来且 `remaining_calls > 0`，启用业务和 MediaMTX 配置后运行：

```powershell
./scripts/verify-zhibo-live-auth.ps1 `
  -BackendBaseUrl http://127.0.0.1:8080 `
  -Phone 13900000003 `
  -Password 'your-password' `
  -DeviceId 'your-device-id' `
  -MediaMtxServiceToken $env:PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN
```

脚本依次断言：

| 场景 | 期待 |
| --- | --- |
| appId=3 业务登录 | CommonResult code 200，bizCode=ZHIBO_LIVE |
| 登录后签发票据 | code 200 |
| 模拟直接 ffmpeg，无 token | HTTP 401 |
| 有效票据首次鉴权 | HTTP 204 |
| 同票据换连接重放 | HTTP 409 |
| available | HTTP 204，会话 LIVE，billedUnits=1 |
| unavailable | HTTP 204，会话 ENDED |

本次已使用真实 Spring Boot + MySQL 临时库跑通全部断言。

## 3. MediaMTX 配置测试

固定使用 v1.20.1 启动 `deploy/mediamtx/mediamtx.yml`，日志必须包含：

```text
configuration loaded from ...mediamtx.yml
[RTMP] started with listener on :1935
[API] started with listener on :9997
```

Control API 检查：

```powershell
Invoke-RestMethod http://127.0.0.1:9997/v3/config/global/get |
  Select-Object authMethod,rtmp,rtsp,hls
```

期待 `authMethod=http`、`rtmp=true`、`rtsp=false`、`hls=false`。本次已用官方 Windows v1.20.1 二进制验证配置可加载。

## 4. FFmpeg 黑盒验收

### 4.1 未登录直接推流必须失败

使用符合 path 正则但不含 token 的地址：

```powershell
ffmpeg -re -f lavfi -i "testsrc=size=640x360:rate=25" `
  -c:v libx264 -f flv `
  "rtmp://127.0.0.1:1935/zhibo-live/ls_0123456789abcdef"
```

期待：FFmpeg 连接/发布失败；后端 auth 返回 401，MediaMTX 不出现可用流。

### 4.2 登录客户端推流必须成功

```powershell
python client-pyqt/live_push_demo.py --api http://127.0.0.1:8080 `
  --phone 13900000003 --password 'your-password' --device-id 'your-device-id'
```

期待：登录和票据签发成功，MediaMTX 出现 publisher，后端会话为 LIVE；Ctrl+C 后变为 ENDED。

本机本次没有预装 FFmpeg，因此未执行音视频帧级黑盒推流；HTTP auth 契约、真实 Spring/MySQL 状态机和 MediaMTX 配置加载均已验证。

## 5. 安全回归

- 搜索应用日志，不得出现完整 `publishUrl`、ticket、`MTX_QUERY`。
- 将内部服务令牌改错，auth/event 必须返回 403。
- 把 ZHIBO_LIVE 关闭或移出部署 allowlist，新票据签发和 auth 均应失败。
- 用户冻结、许可证到期/暂停/作废、许可证次数为 0、设备 UUID 或绑定关系变化时均应失败。

## 6. 多设备许可证专项验收

专项测试以 [多设备许可证解决方案](./ZHIBO_LIVE_MULTI_DEVICE_LICENSE_SOLUTION.md) 第 16、20 节为准。
本次真实 MySQL 已验证同手机号 10 张卡/10 台设备登录成功，第 11 台无卡返回 40380、复用旧卡返回
40383，续费保留原卡和 licenseId，解绑换机不重算到期时间。
- MediaMTX API 9997 不应映射到公网；公网防火墙只开放必要的 RTMP/RTMPS 端口。
