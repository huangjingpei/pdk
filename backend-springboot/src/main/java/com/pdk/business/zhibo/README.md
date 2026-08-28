# ZHIBO 聚合业务

本目录只有一个聚合实现 `ZhiboBusinessHandler`，同时注册两个业务编码：

- `ZHIBO_AI`：`appId=2`、`bizId=2`
- `ZHIBO_LIVE`：`appId=3`、`bizId=3`

两者复用直播业务动作、凭证和失败分类代码，但绝不共用用户、套餐、卡密、小号、租约、消费和财务数据；这些数据均通过服务端解析出的 `bizId` 隔离。
