# 07 · 能力④ / 客户端安全接入 SDK

**平台规格**：横版 16:9（1280×720）；视频号 1:1（1080×1080）

## 构图
- 左侧「客户端 App」框，向右发出调用箭头，标注 4 个安全头（AppID / Phone / DeviceID / Token）
- 右侧「PDK 中台」做请求级校验（登录 / 激活 / 权限 / 到期）
- 底部注：无需心跳，每次请求服务端校验

## 配色
客户端 `#1E3A8A`；中台 `#2563EB`；安全头标签 `#22D3A6`；背景 `#0B1020`

## 文案
- **标题**：你的客户端，几步完成合规接入
- **正文**：登录、激活设备、重置密码、到期与权限自检——标准接口 + 请求级安全校验，客户端无需心跳也能保证每一次操作合法。配套 Python 等示例，接入更省心。
- **话题标签**：#SDK #客户端接入 #安全 #技术交付

## 图片方案（可直接喂图生图工具）
> Sequence diagram style illustration, client app on left sending requests with security header tags to a central server on right doing validation, clean lines, blue teal, no text, 16:9

## 合规备注
强调「合规接入 / 服务端校验」；**不出现抓取、绕过、规避**等词，避免灰产联想。
