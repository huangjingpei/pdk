# 客户端升级配置：服务端与客户端分别配置什么

升级系统有两类配置，不能混在一起：

| 放置位置 | 内容 | 是否保密 |
| --- | --- | --- |
| Spring Boot 服务端 | 两把 Ed25519 私钥、三个 HMAC 密钥、文件存储目录和下载地址 | 必须保密，绝不能打进客户端或提交 Git |
| Windows 客户端构建配置 | 两把 Ed25519 公钥及其 keyId、appId、版本、入口 EXE | 可以随客户端发布 |

## 1. 一次性生成本地配置

```powershell
python E:\pdk\scripts\generate_update_keys.py `
  --output-dir C:\Users\Administrator\.pdk\update-config `
  --storage-root C:/pdk-data/client-updates `
  --public-base-url https://update.example.com
```


python E:\pdk\scripts\generate_update_keys.py
  --output-dir C:\Users\Administrator\.pdk\update-config 
  --storage-root C:/pdk-data/client-updates 
  --public-base-url http://127.0.0.1

命令生成两个文件：

```text
C:\Users\Administrator\.pdk\update-config\
├─ application-client-update.yml   # 服务端私密配置
└─ client-update-public-keys.json  # 可以复制到客户端的公钥
```

不要把 `application-client-update.yml` 放入 Git、客户端目录或升级 ZIP。

`public-base-url` 是服务端生成下载地址时使用的基础地址。若管理后台需要把链接发给其他用户，它必须配置成用户能够访问的 HTTPS 域名或服务器地址；`127.0.0.1`、`localhost` 只允许服务端本机访问，不能用于对外发送。

## 2. IntelliJ IDEA 加载服务端 YAML

打开 `Run → Edit Configurations`，选择启动后端的 Maven 配置，在 `Environment variables` 增加：

```text
SPRING_CONFIG_ADDITIONAL_LOCATION=file:C:/Users/Administrator/.pdk/update-config/application-client-update.yml
```

随后完全停止并重新启动 Spring Boot。启动命令仍然是：

```text
compile spring-boot:run
```

不需要把私钥手工复制进仓库中的 `application.yml`。仓库内的 `application.yml` 只是声明字段和环境变量兜底；真正私钥由上面的外部 YAML 注入。

## 3. 配置客户端公钥

打开生成的 `client-update-public-keys.json`，把两个对象复制到准备打包的 `zhibo-ai.json`，同时设置正确版本和入口：

```json
{
  "appId": 2,
  "version": "1.1.0",
  "channel": "STABLE",
  "updaterVersion": "1.0.0",
  "entryPoint": "zhibodou.exe",
  "artifactPublicKeys": {
    "client-release-2026-01": "<生成的构件公钥>"
  },
  "policyPublicKeys": {
    "client-policy-2026-01": "<生成的策略公钥>"
  }
}
```

keyId 必须与服务端 YAML 中的 `artifact-key-id`、`policy-key-id` 完全相同。客户端只拿公钥，不能拿私钥。

## 4. 重新打包和上传

```powershell
python E:\pdk\scripts\build_update_package.py `
  --source E:\zhibodou\dist\zhibodou `
  --output E:\zhibodou\dist\updates\zhibodou-1.1.0-windows-x64.zip `
  --app-id 2 --version 1.1.0 --entry-point zhibodou.exe
```

后台创建 Release 时，版本、协议版本和最低 Updater 必须与打包命令一致。管理后台上传窗口会显示这份契约，并把错误区分为创建会话、ZIP 校验和 Ed25519 签名三个阶段。

若要用 GUI 升级器离线安装或回滚版本，额外加 `--emit-job --private-key <私钥> --public-key <公钥>`，脚本会同时产出 GUI 可直接识别的签名清单 `*.job.json`。

## 5. 删除规则

- `DRAFT` 且从未发布：允许在后台永久删除；同时删除 Release、Artifact 数据库记录、隔离临时文件和已上传构件文件，操作审计保留。
- `READY`：先退回 DRAFT，再删除。
- `PUBLISHED/SUSPENDED/ARCHIVED` 或曾经发布过：禁止物理删除，只允许暂停或归档，防止破坏已下发客户端及历史证据。

## 6. 配置 Windows 原生升级器

`native_updater/` 产出的 GUI 升级器需要一份 `updater-gui.json`，其中 `publicKey` 就是上面第 3 节写入客户端的同一把构件公钥（base64 SPKI-DER）。GUI 用它验签本地版本包，并据此列出可安装与可回滚的版本。

完整字段说明、构建方式和回滚机制见 [Windows 原生升级器 native_updater](./NATIVE_UPDATER.md)。

## 7. 在管理后台生成手动下载地址

版本完成“上传、校验并签名”且状态发布为 `PUBLISHED` 后，进入“版本发布 → 构件 → 生成下载地址”。填写 1–168 小时有效期和生成原因，系统会产生可复制的带签名下载地址，管理员可以手动发送给用户。

- 只有 `PUBLISHED` Release 下状态为 `AVAILABLE` 的构件可以生成新地址。
- 每次生成都会写入管理审计日志；地址到期后自动失效，需要重新生成。
- 暂停版本后不能生成新地址；已经发出的地址可以使用到自身有效期结束，归档后立即不可下载。
- 地址中带有短期访问令牌，不要把它当作永久公开链接或写入公开页面。
