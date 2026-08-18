# 拼多多采集与分发云控商业化平台 - 客户端对接开发指南 (V1.0 Enterprise)

本文档面向 **桌面采集客户端 / 机器人脚本 / 第三方集成开发者**，详细阐述客户端如何接入拼多多云控商业化平台，完成卡密核销、单设备安全认证、AES-128-GCM 加密调度、短效 Token 租借与免责自愈上报。

---

## 目录
1. [架构与安全交互流程](#1-架构与安全交互流程)
2. [通信加密协议与解密算法规范](#2-通信加密协议与解密算法规范)
3. [核心 API 接口定义](#3-核心-api-接口定义)
   - [3.1 新人 1 天体验试用注册](#31-新人-1-天体验试用注册)
   - [3.2 卡密原子核销与有效期顺延](#32-卡密原子核销与有效期顺延)
   - [3.3 申请短效加密 Token (核心调度)](#33-申请短效加密-token-核心调度)
   - [3.4 异步业务上报与免责扣费](#34-异步业务上报与免责扣费)
4. [单设备互踢与安全拦截处理](#4-单设备互踢与安全拦截处理)
5. [客户端多语言解密代码示例 (C# / Python / Java)](#5-客户端多语言解密代码示例)
6. [全局错误码字典](#6-全局错误码字典)

---

## 1. 架构与安全交互流程

```
+-----------------------------------------------------------------------------------+
|                                 客户端 (Client App)                               |
+-----------------------------------------------------------------------------------+
        | (1) 注册 / 核销卡密 (原子事务 + 财务实收独立记账)
        v
+-----------------------------+               +-------------------------------------+
| 服务端: 卡密与财务核心系统  | ------------> | MySQL (pdk_card_key + 独立财务表)   |
+-----------------------------+               +-------------------------------------+
        |
        | (2) 携带 X-PDK-Phone + X-PDK-Device-ID 申请短效 Token
        v
+-----------------------------+               +-------------------------------------+
| 服务端: 安全网关调度中心    | ------------> | Redis (单机互踢 / 5分钟租约 / 槽位) |
+-----------------------------+               +-------------------------------------+
        | (3) AES-128-GCM + 0x50 0x44 字节倒序翻转密文下发
        v
+-----------------------------+
| 客户端解密并向拼多多官方发包|
+-----------------------------+
        | (4) 异步上报结果 (成功扣1次; 若官方封号报 FAIL_ACCOUNT_BANNED 免责扣0次并自愈)
        v
+-----------------------------+
| 网关自动结算与底层槽位拉黑  |
+-----------------------------+
```

---

## 2. 通信加密协议与解密算法规范

为了防止抓包脱机和内存挂钩窃取底层拼多多 Session Token，系统采用 **动态时间窗口派生密钥 + AES-128-GCM + 魔数前缀 + 全报文字节反转** 算法：

### 2.1 加解密要素
- **Root Salt (根密钥种子)**: `PDK_SECRET_SALT_2026_ENTERPRISE`
- **动态时间窗口**: 每 10 分钟为一个轮转周期，`window = epochSeconds / 60 / 10`
- **密钥派生 (Key)**: `Key = SHA256(RootSalt + "_" + window).substring(0, 16)` (128位)
- **加密算法**: `AES/GCM/NoPadding` (IV 长度 12 字节，Tag 长度 128 位)
- **魔数包头**: `0x50 0x44` (ASCII 对应字符 `'P'`, `'D'`)
- **字节逆序翻转 (Byte Flip)**: 整个 `[Magic(2B) + IV(12B) + Ciphertext(with Tag)]` 进行首尾字节逆序颠倒后输出 Base64。

---

## 3. 核心 API 接口定义

服务端基础路径：`http://api.yourdomain.com` (本地调试: `http://localhost:8080`)

### 3.1 新人 1 天体验试用注册
- **接口路径**: `POST /api/v1/card/register-trial`
- **说明**: 手机号新用户领取 1 天体验版（系统规格：1 个买家账号，20 次/天）。

**请求报文 (Request Body):**
```json
{
  "phone": "13800138000",
  "deviceId": "MAC-00-1B-44-11-3A-B7",
  "smsCode": "882103"
}
```

**响应报文 (Response Body):**
```json
{
  "code": 200,
  "message": "新人1天体验权益已激活",
  "data": {
    "userPhone": "13800138000",
    "packageName": "新人1天体验版 (1账号×20次/天)",
    "newExpireTime": "2026-08-16 15:00:00",
    "extendedDays": 1,
    "totalRemainingCalls": 20,
    "totalAddedCalls": 20,
    "queueActionType": "TRIAL_CLAIMED"
  },
  "timestamp": 1771120019283
}
```

---

### 3.2 卡密原子核销与有效期顺延
- **接口路径**: `POST /api/v1/card/activate`
- **说明**: 用户输入卡密充值或提前续费。同套餐自动顺延天数与累加配额，并在独立财务表写入入账流水。

**请求报文 (Request Body):**
```json
{
  "cardKey": "PDK-8891-2041-9982",
  "userPhone": "13800138000",
  "deviceId": "MAC-00-1B-44-11-3A-B7",
  "actualAmount": 200.00,
  "orderType": "NORMAL_SALE",
  "paymentChannel": "ALIPAY"
}
```

**响应报文 (Response Body):**
```json
{
  "code": 200,
  "message": "卡密核销成功，权益已实时到账",
  "data": {
    "userPhone": "13800138000",
    "cardKey": "PDK-8891-2041-9982",
    "packageName": "200元月卡（多账号防控版）",
    "newExpireTime": "2026-09-15 15:00:00",
    "extendedDays": 30,
    "totalRemainingCalls": 320,
    "totalAddedCalls": 300,
    "incomeOrderNo": "INC-177112001928-8812",
    "queueActionType": "DIRECT_EXTEND"
  },
  "timestamp": 1771120019283
}
```

---

### 3.3 申请短效加密 Token (核心调度)
- **接口路径**: `POST /api/v1/dispatch/acquire-token`
- **请求头 (Headers - 必须携带)**:
  - `X-PDK-Phone`: 用户手机号 (例如: `13800138000`)
  - `X-PDK-Device-ID`: 物理机 UUID (例如: `MAC-00-1B-44-11-3A-B7`)

**请求报文 (Request Body):**
```json
{
  "actionType": "GOODS_COLLECT",
  "goodsId": "881920391204",
  "timestamp": 1771120019000
}
```

**响应报文 (Response Body):**
```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "encryptedPayload": "UEQEe80192jfasdkj81923as9df81203912asdfkj91238491823==",
    "leaseTraceId": "TRACE-100293810293",
    "expireAtTimestamp": 1771120319000,
    "remainingUserQuota": 320,
    "dailyQuotaLimit": 300
  },
  "timestamp": 1771120019283
}
```

---

### 3.4 异步业务上报与免责扣费
- **接口路径**: `POST /api/v1/dispatch/report-result`
- **请求头 (Headers)**: `X-PDK-Phone: 13800138000`

**请求报文 (Request Body):**
```json
{
  "leaseTraceId": "TRACE-100293810293",
  "status": "FAIL_ACCOUNT_BANNED",
  "responseDurationMs": 1420,
  "errorMessage": "官方返回: 账号存在异常需滑块验证 (40029)"
}
```
> **注意**：
> - 当 `status` 为 `SUCCESS` 时，扣减 1 次配额；
> - 当 `status` 为 `FAIL_ACCOUNT_BANNED` 时，触发**免责自愈机制**，不扣除用户配额，服务端自动拉黑故障底层 Token。

---

## 4. 单设备互踢与安全拦截处理

系统严格执行单设备物理绑定原则：
- 客户端发起调度时，拦截器校验当前 `deviceId` 是否与 Redis 绑定的活跃 `deviceId` 一致；
- 若检测到用户在另一台电脑登录，服务端将直接返回 `40103` 错误码；
- **客户端处理建议**: 捕获 `40103` 后，弹出警告模态框 `"账号已在其他电脑登录，本设备已被迫下线"`，并清除内存中已缓存的 Token 和租约。

---

## 5. 客户端多语言解密代码示例

### 5.1 C# / .NET 客户端解密实现
```csharp
using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;

public class PdkSecurityClient
{
    private const string ROOT_SALT = "PDK_SECRET_SALT_2026_ENTERPRISE";

    public static byte[] DeriveKey(long epochMinuteWindow)
    {
        using (var sha = SHA256.Create())
        {
            var raw = Encoding.UTF8.GetBytes($"{ROOT_SALT}_{epochMinuteWindow}");
            var hash = sha.ComputeHash(raw);
            var key = new byte[16];
            Array.Copy(hash, key, 16);
            return key;
        }
    }

    public static string DecryptPayload(string base64Payload)
    {
        byte[] flipped = Convert.FromBase64String(base64Payload);
        // 1. 还原字节正序
        Array.Reverse(flipped);
        byte[] raw = flipped;

        // 2. 校验魔数
        if (raw[0] != 0x50 || raw[1] != 0x44)
            throw new Exception("魔数校验失败: 非有效 PDK 报文");

        // 3. 提取 12 字节 IV 与密文
        byte[] iv = new byte[12];
        Array.Copy(raw, 2, iv, 0, 12);
        int cipherLen = raw.Length - 14 - 16;
        byte[] cipherText = new byte[cipherLen];
        Array.Copy(raw, 14, cipherText, 0, cipherLen);
        byte[] tag = new byte[16];
        Array.Copy(raw, 14 + cipherLen, tag, 0, 16);

        // 4. 当前时间窗口
        long window = DateTimeOffset.UtcNow.ToUnixTimeSeconds() / 60 / 10;
        byte[] key = DeriveKey(window);

        using (var aesGcm = new AesGcm(key))
        {
            byte[] decrypted = new byte[cipherLen];
            aesGcm.Decrypt(iv, cipherText, tag, decrypted);
            return Encoding.UTF8.GetString(decrypted);
        }
    }
}
```

### 5.2 Python 客户端解密实现
```python
import base64
import time
import hashlib
from cryptography.hazmat.primitives.ciphers.aead import AesGcm

ROOT_SALT = "PDK_SECRET_SALT_2026_ENTERPRISE"

def derive_key(window_minute: int) -> bytes:
    raw = f"{ROOT_SALT}_{window_minute}".encode("utf-8")
    return hashlib.sha256(raw).digest()[:16]

def decrypt_pdk_payload(base64_str: str) -> str:
    flipped = base64.b64decode(base64_str)
    raw = flipped[::-1] # 翻转字节

    if raw[0] != 0x50 or raw[1] != 0x44:
        raise ValueError("魔数校验失败: 非 PDK 数据包")

    iv = raw[2:14]
    ciphertext_with_tag = raw[14:]

    current_window = int(time.time() // 60 // 10)
    for w in [current_window, current_window - 1, current_window + 1]:
        try:
            key = derive_key(w)
            aesgcm = AesGcm(key)
            decrypted = aesgcm.decrypt(iv, ciphertext_with_tag, None)
            return decrypted.decode("utf-8")
        except Exception:
            continue
    raise Exception("解密失败: 密钥已过期或数据损坏")
```

---

## 6. 全局错误码字典

| 错误码 (Code) | 错误常量 | 描述与客户端处理指引 |
| :--- | :--- | :--- |
| `200` | `SUCCESS` | 请求处理成功 |
| `40001` | `ERR_CARD_NOT_FOUND` | 卡密不存在或格式输入错误 |
| `40002` | `ERR_CARD_ALREADY_USED` | 卡密已被他人核销或已作废 |
| `40004` | `ERR_CONCURRENT_CONFLICT` | 并发冲突，卡密正在被另一并发线程核销 |
| `40010` | `ERR_TRIAL_ALREADY_CLAIMED`| 该手机号已领取过 1 天体验，不可重复领取 |
| `40101` | `ERR_MISSING_AUTH_HEADERS` | 缺少 `X-PDK-Phone` 或 `X-PDK-Device-ID` 鉴权头 |
| `40103` | `ERR_DEVICE_KICK_OUT` | 账号已在其他电脑登录，强制本端下线 |
| `40301` | `ERR_SUBSCRIPTION_EXPIRED` | 订阅已到期，阻断业务请求，引导核销新卡密 |
| `40302` | `ERR_QUOTA_EXHAUSTED` | 今日可用调用总配额已耗尽 |
| `50301` | `ERR_NO_AVAILABLE_TOKEN` | 拼多多官方底层槽位瞬时繁忙，提示稍后重试 |
