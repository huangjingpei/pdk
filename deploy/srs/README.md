# SRS 接入 ZHIBO_LIVE

`srs.conf.example` 是后端 SRS Provider/Callback 的最小参考配置。管理后台创建节点时建议填写：

```text
providerType             SRS
nodeCode                 srs-local
publicPublishBaseUrl     rtmp://<公网域名>:1935
internalApiBaseUrl       http://<SRS私网地址>:1985
supportedPublishProtocols RTMP
```

四个 HTTP Hook 必须使用同一个 `/api/v1/internal/srs/callback`，SRS 会在 JSON 的 `action` 字段中发送 `on_publish`、`on_unpublish`、`on_play` 或 `on_stop`。后端在 `on_publish` 中校验短效票据；非 2xx 或 `code != 0` 时 SRS 必须拒绝该推流。

生产环境不要把 token 写入镜像或提交到版本库，应在部署时从 Secret 注入配置；SRS HTTP API 只允许业务后端从私网访问。
