# PDD 业务模块

业务身份：

```text
appId=1
bizId=1（多业务表结构落地后）
bizCode=PDD
```

文件职责：

- `PddBusinessHandler`：PDD 模块统一入口。
- `PddActionValidator`：允许的 PDD 客户端动作。
- `PddCredentialCodec`：保持现有客户端所需的 `token/leaseId/expire` 明文结构，外层加密仍由平台处理。
- `PddFailureClassifier`：把 PDD 客户端上报归一化为平台扣次、免责、拉黑决定。

数据库中的 `token_val`、`real_pdd_account_id` 等历史字段当前保持不变，避免破坏已有 schema、MyBatis 映射和历史数据。公共代码通过 Handler 访问业务差异；等 `bizId` 数据迁移完成后再按方案逐步泛化字段名。
