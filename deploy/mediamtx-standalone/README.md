# 独立 MediaMTX 流媒体服务器部署指南 (物理分离架构)

本目录为独立流媒体服务器（例如公网 IP: `43.248.187.207`）专属交付包，用于实现 **流媒体推拉流** 与 **业务后台（121.43.150.109 / pdk.graddu.com）** 的物理分离。

---

## 一、 网络端口规划与防火墙策略

在独立流媒体服务器（`43.248.187.207`）的安全组 / 防火墙中开放以下端口：

| 端口 | 协议 | 用途 | 访问来源安全策略 |
| :--- | :--- | :--- | :--- |
| **1935** | TCP | RTMP 推流与拉流 | **允许所有公网访问**（主播 App 与播放端） |
| **8888** | TCP | HLS 流媒体播放切片 | **允许所有公网访问**（Web / 观众端） |
| **9997** | TCP | MediaMTX Control API | **仅允许业务后端 IP**（`121.43.150.109/32`）及本地回环访问 |
| **9998** | TCP | Prometheus Metrics 采集 | **仅允许业务后端 IP**（`121.43.150.109/32`）及本地回环访问 |

---

## 二、 部署步骤（在 43.248.187.207 上执行）

### 方式 A：原生二进制部署（推荐，资源消耗极低）

1. 将本目录打包上传至 `43.248.187.207`，例如 `/root/mediamtx-standalone/`；
2. 复制配置文件并填入安全密钥：
   ```bash
   cd /root/mediamtx-standalone
   cp env.example .env
   vim .env
   ```
   > ⚠️ **关键**：将 `.env` 中的 `PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN` 填写为与业务服务器 `/opt/pdk/.env` 中完全相同的安全令牌。
3. 执行一键安装脚本（自动下载二进制、配置 systemd 并启动服务）：
   ```bash
   chmod +x *.sh
   sudo bash install-native.sh
   ```
4. 运行连通性测试脚本验证：
   ```bash
   bash test-mediamtx-link.sh
   ```

---

### 方式 B：Docker Compose 容器化部署

1. 同样在目录中配置好 `.env`；
2. 启动容器：
   ```bash
   docker compose up -d
   ```
3. 查看日志：
   ```bash
   docker compose logs -f
   ```

---

## 三、 业务后端（121.43.150.109）部署配合

在开发机执行后端部署时，只需确保 `deploy/aliyun/env.sh` 中包含对应节点配置：

```bash
PDK_MEDIAMTX_HOST="43.248.187.207"
PDK_MEDIAMTX_NODE_CODE="mediamtx-remote-1"
PDK_MEDIAMTX_NODE_NAME="专网流媒体节点 (43.248.187.207)"
PDK_MEDIAMTX_PUBLIC_RTMP="rtmp://43.248.187.207:1935"
PDK_MEDIAMTX_PUBLIC_HLS="http://43.248.187.207:8888"
PDK_MEDIAMTX_INTERNAL_API="http://43.248.187.207:9997"
```

部署脚本 `04-deploy.sh` 会自动：
1. 更新数据库 `pdk_media_server_node` 表，启用 `43.248.187.207` 节点；
2. 停用旧的 `mediamtx-local`（127.0.0.1）测试节点；
3. 解除对流媒体节点离线的一票否决，确保发版与重启平滑完成。
