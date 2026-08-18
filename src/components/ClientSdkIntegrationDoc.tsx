import React, { useState } from 'react';
import { 
  Terminal, 
  Copy, 
  Check, 
  ShieldCheck, 
  Key, 
  Cpu, 
  Code2, 
  FileText, 
  Smartphone, 
  Lock, 
  AlertCircle, 
  CheckCircle2, 
  RefreshCw, 
  Send, 
  ExternalLink,
  ChevronRight,
  Layers
} from 'lucide-react';

export const ClientSdkIntegrationDoc: React.FC = () => {
  const [activeLang, setActiveLang] = useState<'CSHARP' | 'PYTHON' | 'ELECTRON' | 'JAVA'>('CSHARP');
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const handleCopy = (text: string, id: string) => {
    navigator.clipboard.writeText(text);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  return (
    <div className="space-y-8 pb-16">
      {/* 顶部 Hero */}
      <div className="bg-gradient-to-r from-slate-900 via-indigo-950 to-slate-900 text-white rounded-2xl p-8 shadow-xl border border-indigo-800/40">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="space-y-2">
            <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-indigo-500/20 text-indigo-300 text-xs font-semibold border border-indigo-400/30">
              <Terminal className="w-3.5 h-3.5" />
              <span>PDK Client SDK Integration Manual v1.0</span>
            </div>
            <h1 className="text-3xl font-bold tracking-tight text-white">
              拼多客（PDK）客户端对接与 SDK 开发指南
            </h1>
            <p className="text-slate-300 text-sm max-w-3xl leading-relaxed">
              面向客户端开发团队（C# WinForm/WPF、Electron、Python、Java/Android），提供全套零心智负担的鉴权、卡密激活、Token 动态解密、短效租约自愈以及单设备互踢通信规范。
            </p>
          </div>

          <div className="flex items-center gap-3">
            <span className="px-3 py-1.5 rounded-lg bg-emerald-500/20 text-emerald-300 border border-emerald-500/30 text-xs font-mono font-bold flex items-center gap-1.5">
              <CheckCircle2 className="w-4 h-4" /> 协议规范：HTTPS + AES-GCM
            </span>
          </div>
        </div>

        {/* 快速导航标签 */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-6 pt-6 border-t border-slate-800 text-xs">
          <a href="#doc-overview" className="p-2.5 rounded-lg bg-white/5 hover:bg-white/10 transition border border-white/10 flex items-center justify-between">
            <span>1. 交互时序与通信总览</span>
            <ChevronRight className="w-4 h-4 text-slate-400" />
          </a>
          <a href="#doc-api" className="p-2.5 rounded-lg bg-white/5 hover:bg-white/10 transition border border-white/10 flex items-center justify-between">
            <span>2. 6 大核心 RESTful 接口</span>
            <ChevronRight className="w-4 h-4 text-slate-400" />
          </a>
          <a href="#doc-crypto" className="p-2.5 rounded-lg bg-white/5 hover:bg-white/10 transition border border-white/10 flex items-center justify-between">
            <span>3. Token 传输解密 SDK 源码</span>
            <ChevronRight className="w-4 h-4 text-slate-400" />
          </a>
          <a href="#doc-error" className="p-2.5 rounded-lg bg-white/5 hover:bg-white/10 transition border border-white/10 flex items-center justify-between">
            <span>4. 熔断与错误码规范</span>
            <ChevronRight className="w-4 h-4 text-slate-400" />
          </a>
        </div>
      </div>

      {/* 第一章: 交互时序与通信总览 */}
      <section id="doc-overview" className="bg-white border border-slate-200 rounded-xl p-8 shadow-sm">
        <div className="flex items-center justify-between border-b border-slate-100 pb-4 mb-6">
          <div className="flex items-center gap-3">
            <span className="w-8 h-8 rounded-lg bg-indigo-600 text-white font-bold flex items-center justify-center text-sm">1</span>
            <div>
              <h2 className="text-xl font-bold text-slate-900">交互时序与通信总览 (Protocol Overview)</h2>
              <p className="text-xs text-slate-500">客户端与 PDK 服务端通信的标准生命周期与数据流向</p>
            </div>
          </div>
          <span className="text-xs bg-slate-100 text-slate-700 px-2.5 py-1 rounded font-mono font-bold">LIFECYCLE_STANDARD</span>
        </div>

        <div className="space-y-6 text-sm text-slate-700">
          <div className="p-4 rounded-xl bg-slate-50 border border-slate-200">
            <h3 className="font-bold text-slate-900 mb-2 flex items-center gap-2">
              <Layers className="w-4 h-4 text-indigo-600" />
              <span>客户端生命周期时序图</span>
            </h3>
            <div className="p-4 bg-slate-900 text-slate-200 rounded-lg font-mono text-xs overflow-x-auto leading-relaxed">
              {`[客户端启动] 
     │
     ├── 1. 检查本地是否有 Token? 
     │       ├── 无: 提示用户输入手机号 + 短信验证码 -> 注册并获赠 1天试用(20次)
     │       └── 有: 携带 Device-UUID + PDK-Token 请求 [/api/v1/device/heartbeat]
     │
     ├── 2. 鉴权判定:
     │       ├── 401/403 (过期或被顶号下线): 立即弹出提示并锁定业务界面，引导输入新卡密 [/api/v1/card/activate]
     │       └── 200 OK: 取得剩余次数 X*Y 槽位与有效截止时间
     │
     ├── 3. 用户发起拼多多查询/操作:
     │       ├── 步骤A: 客户端请求服务端网关 [/api/v1/dispatch/acquire-token] 申请槽位
     │       ├── 步骤B: 网关返回经过 AES-GCM + 字节翻转加密的拼多多短效 Token
     │       ├── 步骤C: 客户端调用 PDK SDK 解密出明文 Token，在运行时内存直接请求拼多多接口
     │       └── 步骤D: 客户端将执行结果异步上报网关 [/api/v1/dispatch/report-result]
     │                 - 成功: 网关扣除该槽位 1 次配额
     │                 - 底层Token异常: 网关扣除 0 次，并自动拉黑故障Token触发自愈
     │
     └── 4. 配额耗尽或到期: 服务端返回 HTTP 403 (QUOTA_EXHAUSTED)，客户端 SDK 强制熔断。`}
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs">
            <div className="p-4 rounded-lg bg-blue-50/50 border border-blue-200">
              <span className="font-bold text-blue-950 block mb-1">统一请求头 (Headers)</span>
              <p className="text-slate-600 mb-2">每次 HTTP 请求必须携带：</p>
              <ul className="space-y-1 font-mono text-[11px] text-blue-900">
                <li>• <code>Authorization: Bearer &lt;UserToken&gt;</code></li>
                <li>• <code>X-PDK-Device-ID: &lt;MacOrUUID&gt;</code></li>
                <li>• <code>X-PDK-Timestamp: &lt;13位Unix毫秒&gt;</code></li>
                <li>• <code>X-PDK-Client-Ver: 1.0.0</code></li>
              </ul>
            </div>

            <div className="p-4 rounded-lg bg-emerald-50/50 border border-emerald-200">
              <span className="font-bold text-emerald-950 block mb-1">安全防刷约束</span>
              <p className="text-slate-600 mb-2">服务端严格校验：</p>
              <ul className="space-y-1 text-slate-700">
                <li>• 时间戳偏差超过 <strong>±300秒</strong> 拒绝请求（防重放）</li>
                <li>• 单设备心跳间隔建议 <strong>30秒~60秒</strong></li>
                <li>• 单 IP 限制 100 次/分钟请求频率</li>
              </ul>
            </div>

            <div className="p-4 rounded-lg bg-purple-50/50 border border-purple-200">
              <span className="font-bold text-purple-950 block mb-1">内存安全法则</span>
              <p className="text-slate-600 mb-2">严防逆向被提取：</p>
              <ul className="space-y-1 text-slate-700">
                <li>• 解密后的 Token 仅驻留内存变量</li>
                <li>• <strong>严禁写入本地 ini/json/db 磁盘文件</strong></li>
                <li>• 接口响应完毕后立即清空内存 buffer</li>
              </ul>
            </div>
          </div>
        </div>
      </section>

      {/* 第二章: 6 大核心 RESTful 接口 */}
      <section id="doc-api" className="bg-white border border-slate-200 rounded-xl p-8 shadow-sm">
        <div className="flex items-center justify-between border-b border-slate-100 pb-4 mb-6">
          <div className="flex items-center gap-3">
            <span className="w-8 h-8 rounded-lg bg-indigo-600 text-white font-bold flex items-center justify-center text-sm">2</span>
            <div>
              <h2 className="text-xl font-bold text-slate-900">核心 RESTful API 接口规范</h2>
              <p className="text-xs text-slate-500">网关端点、请求结构体与真实响应 JSON 示例</p>
            </div>
          </div>
          <span className="text-xs bg-indigo-100 text-indigo-800 px-2.5 py-1 rounded font-mono font-bold">API_V1_SPEC</span>
        </div>

        <div className="space-y-6 text-xs">
          {/* 接口 1: 注册与试用 */}
          <div className="border border-slate-200 rounded-xl overflow-hidden">
            <div className="bg-slate-900 text-white p-3.5 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="px-2 py-0.5 rounded bg-emerald-500 text-white font-bold font-mono text-[11px]">POST</span>
                <span className="font-mono text-slate-200 font-bold">/api/v1/auth/register-trial</span>
                <span className="text-slate-400 text-xs">新用户手机短信注册并自动派发 1天试用(20次)</span>
              </div>
              <button 
                onClick={() => handleCopy('/api/v1/auth/register-trial', 'api-1')}
                className="text-slate-400 hover:text-white flex items-center gap-1"
              >
                {copiedId === 'api-1' ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
              </button>
            </div>
            <div className="p-4 grid grid-cols-1 md:grid-cols-2 gap-4 bg-slate-50/50">
              <div>
                <span className="font-bold text-slate-700 block mb-1.5">请求体 JSON:</span>
                <pre className="p-3 bg-slate-900 text-slate-200 rounded-lg font-mono overflow-x-auto">{`{
  "phone": "13800138000",
  "smsCode": "889921",
  "deviceId": "MAC-00-1B-44-11-3A-B7",
  "clientVer": "1.0.0"
}`}</pre>
              </div>
              <div>
                <span className="font-bold text-slate-700 block mb-1.5">响应体 JSON (200 OK):</span>
                <pre className="p-3 bg-slate-900 text-slate-200 rounded-lg font-mono overflow-x-auto">{`{
  "code": 200,
  "message": "注册成功，已获赠1天体验配额",
  "data": {
    "userToken": "pdk_usr_eyJhbGciOiJIUzI1...",
    "status": "TRIAL",
    "expireTime": "2026-08-16 14:30:00",
    "quota": {
      "accountCountX": 1,
      "callsPerAccountY": 20,
      "remainingCalls": 20,
      "totalCalls": 20
    }
  }
}`}</pre>
              </div>
            </div>
          </div>

          {/* 接口 2: 卡密原子核销 */}
          <div className="border border-slate-200 rounded-xl overflow-hidden">
            <div className="bg-slate-900 text-white p-3.5 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="px-2 py-0.5 rounded bg-emerald-500 text-white font-bold font-mono text-[11px]">POST</span>
                <span className="font-mono text-slate-200 font-bold">/api/v1/card/activate</span>
                <span className="text-slate-400 text-xs">用户输入卡密一键原子核销延期并注入套餐</span>
              </div>
              <button 
                onClick={() => handleCopy('/api/v1/card/activate', 'api-2')}
                className="text-slate-400 hover:text-white flex items-center gap-1"
              >
                {copiedId === 'api-2' ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
              </button>
            </div>
            <div className="p-4 grid grid-cols-1 md:grid-cols-2 gap-4 bg-slate-50/50">
              <div>
                <span className="font-bold text-slate-700 block mb-1.5">请求体 JSON:</span>
                <pre className="p-3 bg-slate-900 text-slate-200 rounded-lg font-mono overflow-x-auto">{`{
  "cardKey": "PDK-8891-2041-9982",
  "phone": "13800138000",
  "deviceId": "MAC-00-1B-44-11-3A-B7"
}`}</pre>
              </div>
              <div>
                <span className="font-bold text-slate-700 block mb-1.5">响应体 JSON (200 OK):</span>
                <pre className="p-3 bg-slate-900 text-slate-200 rounded-lg font-mono overflow-x-auto">{`{
  "code": 200,
  "message": "卡密核销成功，权益已实时生效",
  "data": {
    "packageName": "200元月卡多账号防控版",
    "extendedDays": 30,
    "newExpireTime": "2026-09-15 14:30:00",
    "updatedQuota": {
      "accountCountX": 10,
      "callsPerAccountY": 30,
      "remainingCalls": 320,
      "isQueued": false
    }
  }
}`}</pre>
              </div>
            </div>
          </div>

          {/* 接口 3: 动态获取短效 Token 与槽位 */}
          <div className="border border-slate-200 rounded-xl overflow-hidden">
            <div className="bg-slate-900 text-white p-3.5 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="px-2 py-0.5 rounded bg-indigo-500 text-white font-bold font-mono text-[11px]">POST</span>
                <span className="font-mono text-slate-200 font-bold">/api/v1/dispatch/acquire-token</span>
                <span className="text-slate-400 text-xs">申请业务槽位与下发加密保护的拼多多短效 Token</span>
              </div>
              <button 
                onClick={() => handleCopy('/api/v1/dispatch/acquire-token', 'api-3')}
                className="text-slate-400 hover:text-white flex items-center gap-1"
              >
                {copiedId === 'api-3' ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
              </button>
            </div>
            <div className="p-4 grid grid-cols-1 md:grid-cols-2 gap-4 bg-slate-50/50">
              <div>
                <span className="font-bold text-slate-700 block mb-1.5">请求体 JSON:</span>
                <pre className="p-3 bg-slate-900 text-slate-200 rounded-lg font-mono overflow-x-auto">{`{
  "reqUuid": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "actionType": "QUERY_ITEM_DETAIL"
}`}</pre>
              </div>
              <div>
                <span className="font-bold text-slate-700 block mb-1.5">响应体 JSON (200 OK):</span>
                <pre className="p-3 bg-slate-900 text-slate-200 rounded-lg font-mono overflow-x-auto">{`{
  "code": 200,
  "data": {
    "slotIndex": 3,
    "leaseExpireSeconds": 300,
    "cipherPayload": "0x50 0x44 a8 f3 91 bc 77 ...",
    "nonce": "9f11a8c49210",
    "tag": "e4910a2b881c",
    "remainingSlotCalls": 28
  }
}`}</pre>
              </div>
            </div>
          </div>

          {/* 接口 4: 执行结果异步上报与扣费 */}
          <div className="border border-slate-200 rounded-xl overflow-hidden">
            <div className="bg-slate-900 text-white p-3.5 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="px-2 py-0.5 rounded bg-purple-500 text-white font-bold font-mono text-[11px]">POST</span>
                <span className="font-mono text-slate-200 font-bold">/api/v1/dispatch/report-result</span>
                <span className="text-slate-400 text-xs">执行完拼多多业务后上报，网关判定扣 1 还是扣 0</span>
              </div>
              <button 
                onClick={() => handleCopy('/api/v1/dispatch/report-result', 'api-4')}
                className="text-slate-400 hover:text-white flex items-center gap-1"
              >
                {copiedId === 'api-4' ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
              </button>
            </div>
            <div className="p-4 grid grid-cols-1 md:grid-cols-2 gap-4 bg-slate-50/50">
              <div>
                <span className="font-bold text-slate-700 block mb-1.5">请求体 JSON:</span>
                <pre className="p-3 bg-slate-900 text-slate-200 rounded-lg font-mono overflow-x-auto">{`{
  "reqUuid": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "slotIndex": 3,
  "execStatus": "SUCCESS",
  "pddReturnCode": 0,
  "responseTimeMs": 182
}`}</pre>
              </div>
              <div>
                <span className="font-bold text-slate-700 block mb-1.5">响应体 JSON (200 OK):</span>
                <pre className="p-3 bg-slate-900 text-slate-200 rounded-lg font-mono overflow-x-auto">{`{
  "code": 200,
  "data": {
    "deducted": 1,
    "currentRemainingTotal": 319,
    "slotStatus": "HEALTHY"
  }
}`}</pre>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* 第三章: 客户端 SDK 解密源码 (4种语言支持) */}
      <section id="doc-crypto" className="bg-white border border-slate-200 rounded-xl p-8 shadow-sm">
        <div className="flex items-center justify-between border-b border-slate-100 pb-4 mb-6">
          <div className="flex items-center gap-3">
            <span className="w-8 h-8 rounded-lg bg-indigo-600 text-white font-bold flex items-center justify-center text-sm">3</span>
            <div>
              <h2 className="text-xl font-bold text-slate-900">Token 传输解密 SDK 源码（零心智负担）</h2>
              <p className="text-xs text-slate-500">内置时间窗口 AES-GCM + 0x50 0x44 字节翻转还原逻辑</p>
            </div>
          </div>
          
          <div className="flex items-center gap-1 bg-slate-100 p-1 rounded-lg">
            {(['CSHARP', 'PYTHON', 'ELECTRON', 'JAVA'] as const).map(lang => (
              <button
                key={lang}
                onClick={() => setActiveLang(lang)}
                className={`px-3 py-1 rounded text-xs font-mono font-bold transition ${
                  activeLang === lang ? 'bg-indigo-600 text-white shadow-xs' : 'text-slate-600 hover:text-slate-900'
                }`}
              >
                {lang === 'CSHARP' ? 'C# (.NET)' : lang === 'PYTHON' ? 'Python' : lang === 'ELECTRON' ? 'Electron/Node.js' : 'Java/Android'}
              </button>
            ))}
          </div>
        </div>

        <div className="space-y-4">
          <div className="flex items-center justify-between bg-slate-800 text-slate-300 px-4 py-2 rounded-t-lg font-mono text-xs">
            <span>PdkCryptoSdk.{activeLang === 'CSHARP' ? 'cs' : activeLang === 'PYTHON' ? 'py' : activeLang === 'ELECTRON' ? 'ts' : 'java'}</span>
            <button 
              onClick={() => handleCopy(activeLang === 'CSHARP' ? csharpCode : activeLang === 'PYTHON' ? pythonCode : activeLang === 'ELECTRON' ? electronCode : javaCode, 'sdk-code')}
              className="hover:text-white flex items-center gap-1"
            >
              {copiedId === 'sdk-code' ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
              <span>复制 SDK 源码</span>
            </button>
          </div>

          <pre className="p-4 bg-slate-900 text-emerald-400 font-mono text-xs rounded-b-lg overflow-x-auto leading-relaxed max-h-96">
            {activeLang === 'CSHARP' && csharpCode}
            {activeLang === 'PYTHON' && pythonCode}
            {activeLang === 'ELECTRON' && electronCode}
            {activeLang === 'JAVA' && javaCode}
          </pre>

          <div className="p-3 bg-amber-50 border border-amber-200 rounded-lg text-xs text-amber-900 flex items-start gap-2">
            <AlertCircle className="w-4 h-4 text-amber-600 shrink-0 mt-0.5" />
            <div>
              <strong>客户端集成核心铁律：</strong>
              <span>解密出来的明文 Token（如 <code>pdd_tkn_8899120...</code>）必须只在发起 HTTP 请求时的临时内存变量中存在，千万不要写进 <code>Config.ini</code> 或注册表中。一旦客户端进程关闭，内存自动抹除，做到绝对安全！</span>
            </div>
          </div>
        </div>
      </section>

      {/* 第四章: 熔断与错误码规范 */}
      <section id="doc-error" className="bg-white border border-slate-200 rounded-xl p-8 shadow-sm">
        <div className="flex items-center justify-between border-b border-slate-100 pb-4 mb-6">
          <div className="flex items-center gap-3">
            <span className="w-8 h-8 rounded-lg bg-indigo-600 text-white font-bold flex items-center justify-center text-sm">4</span>
            <div>
              <h2 className="text-xl font-bold text-slate-900">服务端状态码与客户端强制熔断规范</h2>
              <p className="text-xs text-slate-500">当遇到到期或配额耗尽时，客户端 SDK 应执行的标准动作</p>
            </div>
          </div>
          <span className="text-xs bg-rose-100 text-rose-800 px-2.5 py-1 rounded font-mono font-bold">ERROR_HANDLING</span>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs border border-slate-200">
            <thead className="bg-slate-100 text-slate-700 font-bold border-b border-slate-200">
              <tr>
                <th className="p-3">HTTP 状态码</th>
                <th className="p-3">业务错误 Code</th>
                <th className="p-3">错误原因</th>
                <th className="p-3">客户端 SDK 标准处理动作 (熔断准则)</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              <tr className="hover:bg-slate-50">
                <td className="p-3 font-mono font-bold text-rose-600">403 Forbidden</td>
                <td className="p-3 font-mono text-slate-700">ERR_ACCOUNT_EXPIRED</td>
                <td className="p-3">用户套餐已过期 (无论是否有剩余次数)</td>
                <td className="p-3 font-bold text-rose-700">【立即熔断退出】：锁定查询按钮，弹出「套餐已过期，请输入新卡密续费」弹窗。</td>
              </tr>
              <tr className="hover:bg-slate-50">
                <td className="p-3 font-mono font-bold text-rose-600">403 Forbidden</td>
                <td className="p-3 font-mono text-slate-700">ERR_QUOTA_EXHAUSTED</td>
                <td className="p-3">套餐剩余可用总次数归零</td>
                <td className="p-3 font-bold text-rose-700">【立即熔断退出】：终止并发任务，弹出「配额已耗尽，请联系客服续费」弹窗。</td>
              </tr>
              <tr className="hover:bg-slate-50">
                <td className="p-3 font-mono font-bold text-amber-600">401 Unauthorized</td>
                <td className="p-3 font-mono text-slate-700">ERR_DEVICE_KICK_OUT</td>
                <td className="p-3">该账号在另一台新设备登录，旧设备被踢下线</td>
                <td className="p-3 font-bold text-amber-800">【防合租拦截】：弹出「您的账号已在其他电脑登录，本设备已安全下线」，并清除本地登录凭证。</td>
              </tr>
              <tr className="hover:bg-slate-50">
                <td className="p-3 font-mono font-bold text-blue-600">429 Too Many Req</td>
                <td className="p-3 font-mono text-slate-700">ERR_RATE_LIMITED</td>
                <td className="p-3">客户端请求超频 (超出 100次/分钟)</td>
                <td className="p-3 text-slate-700">客户端休眠 2 秒后自动重试，无需弹窗打扰用户。</td>
              </tr>
              <tr className="hover:bg-slate-50">
                <td className="p-3 font-mono font-bold text-slate-600">503 Unavailable</td>
                <td className="p-3 font-mono text-slate-700">ERR_TOKEN_POOL_DEPLETED</td>
                <td className="p-3">公共底层 Token 池健康度告警</td>
                <td className="p-3 text-slate-700">提示「云端资产池正在自愈调度，请 3 秒后重试」，网关自动启用备用 Token 池。</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
};

// C# SDK 源码
const csharpCode = `// PDK Client SDK (C# .NET 6 / .NET 8 / WinForm / WPF)
using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace Pdk.Client.Sdk
{
    public static class PdkCryptoSdk
    {
        private const string CLIENT_SALT = "PDK_SECRET_SALT_2026_V1";

        /// <summary>
        /// 客户端一行调用：解密服务端下发的动态加密 Token 载荷
        /// </summary>
        /// <param name="hexCipherPayload">服务端返回的十六进制加密串 (含 0x50 0x44 混淆头)</param>
        /// <param name="nonceHex">12字节 IV Nonce (Hex)</param>
        /// <param name="tagHex">16字节 GCM Auth Tag (Hex)</param>
        /// <returns>解密后的拼多多明文 Token (请仅在内存持有，勿落盘)</returns>
        public static string DecryptTokenPayload(string hexCipherPayload, string nonceHex, string tagHex)
        {
            byte[] rawBytes = Convert.FromHexString(hexCipherPayload.Replace(" ", "").Replace("0x", ""));

            // 1. 去除 0x50 0x44 混淆头并执行字节倒序还原
            if (rawBytes.Length < 2 || rawBytes[0] != 0x50 || rawBytes[1] != 0x44)
            {
                throw new InvalidOperationException("Invalid PDK Packet Header (Anti-Tamper)");
            }

            byte[] cipherBytes = new byte[rawBytes.Length - 2];
            Array.Copy(rawBytes, 2, cipherBytes, 0, cipherBytes.Length);
            Array.Reverse(cipherBytes); // 还原翻转字节

            // 2. 根据当前 10 分钟时间窗口派生对称密钥
            long timeWindow = DateTimeOffset.UtcNow.ToUnixTimeSeconds() / 600;
            string keySeed = $"{CLIENT_SALT}_{timeWindow}";
            byte[] aesKey = SHA256.HashData(Encoding.UTF8.GetBytes(keySeed))[0..16]; // 取前 16 字节

            byte[] nonce = Convert.FromHexString(nonceHex);
            byte[] tag = Convert.FromHexString(tagHex);
            byte[] plaintextBytes = new byte[cipherBytes.Length];

            // 3. 执行 AES-128-GCM 解密
            using var aesGcm = new AesGcm(aesKey);
            aesGcm.Decrypt(nonce, cipherBytes, tag, plaintextBytes);

            return Encoding.UTF8.GetString(plaintextBytes);
        }
    }
}`;

// Python SDK 源码
const pythonCode = `# PDK Client SDK (Python 3.8+)
import time
import hashlib
from cryptography.hazmat.primitives.ciphers.aead import AesGcm

CLIENT_SALT = "PDK_SECRET_SALT_2026_V1"

def decrypt_token_payload(hex_payload: str, nonce_hex: str, tag_hex: str) -> str:
    """
    客户端一行调用解密短效 Token
    """
    clean_hex = hex_payload.replace(" ", "").replace("0x", "")
    raw_bytes = bytes.fromhex(clean_hex)
    
    # 1. 校验魔数并逆向翻转
    if len(raw_bytes) < 2 or raw_bytes[0] != 0x50 or raw_bytes[1] != 0x44:
        raise ValueError("Invalid PDK Packet Header")
    
    cipher_bytes = raw_bytes[2:][::-1] # 倒序翻转
    
    # 2. 时间窗口派生 AES 密钥
    time_window = int(time.time()) // 600
    key_seed = f"{CLIENT_SALT}_{time_window}".encode('utf-8')
    aes_key = hashlib.sha256(key_seed).digest()[:16]
    
    nonce = bytes.fromhex(nonce_hex)
    tag = bytes.fromhex(tag_hex)
    
    # 3. GCM 解密 (AESGCM 要求 ciphertext + tag 拼接)
    aesgcm = AesGcm(aes_key)
    plaintext = aesgcm.decrypt(nonce, cipher_bytes + tag, None)
    
    return plaintext.decode('utf-8')
`;

// Electron / Node.js SDK 源码
const electronCode = `// PDK Client SDK (TypeScript / Electron / Node.js)
import crypto from 'crypto';

const CLIENT_SALT = 'PDK_SECRET_SALT_2026_V1';

export function decryptTokenPayload(hexPayload: string, nonceHex: string, tagHex: string): string {
  const cleanHex = hexPayload.replace(/\\s+|0x/g, '');
  const rawBytes = Buffer.from(cleanHex, 'hex');

  // 1. 校验 0x50 0x44 魔数
  if (rawBytes.length < 2 || rawBytes[0] !== 0x50 || rawBytes[1] !== 0x44) {
    throw new Error('Invalid PDK Packet Header');
  }

  // 2. 截取并字节翻转
  const cipherBytes = Buffer.from(rawBytes.subarray(2)).reverse();

  // 3. 计算时间窗口密钥
  const timeWindow = Math.floor(Date.now() / 1000 / 600);
  const keySeed = \`\${CLIENT_SALT}_\${timeWindow}\`;
  const aesKey = crypto.createHash('sha256').update(keySeed).digest().subarray(0, 16);

  const nonce = Buffer.from(nonceHex, 'hex');
  const tag = Buffer.from(tagHex, 'hex');

  // 4. AES-128-GCM 解密
  const decipher = crypto.createDecipheriv('aes-128-gcm', aesKey, nonce);
  decipher.setAuthTag(tag);
  
  let decrypted = decipher.update(cipherBytes, undefined, 'utf8');
  decrypted += decipher.final('utf8');

  return decrypted;
}`;

// Java SDK 源码
const javaCode = `// PDK Client SDK (Java 17 / Android)
package com.pdk.client.sdk;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public class PdkCryptoSdk {
    private static final String CLIENT_SALT = "PDK_SECRET_SALT_2026_V1";

    public static String decryptTokenPayload(String hexPayload, String nonceHex, String tagHex) throws Exception {
        byte[] rawBytes = HexFormat.of().parseHex(hexPayload.replaceAll("[\\\\s0x]", ""));

        if (rawBytes.length < 2 || rawBytes[0] != (byte) 0x50 || rawBytes[1] != (byte) 0x44) {
            throw new IllegalArgumentException("Invalid PDK Packet Header");
        }

        // 字节翻转还原
        byte[] cipherBytes = new byte[rawBytes.length - 2];
        for (int i = 0; i < cipherBytes.length; i++) {
            cipherBytes[i] = rawBytes[rawBytes.length - 1 - i];
        }

        // 时间窗口密钥派生
        long timeWindow = (System.currentTimeMillis() / 1000) / 600;
        String keySeed = CLIENT_SALT + "_" + timeWindow;
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] keyHash = sha256.digest(keySeed.getBytes(StandardCharsets.UTF_8));
        byte[] aesKey = new byte[16];
        System.arraycopy(keyHash, 0, aesKey, 0, 16);

        byte[] nonce = HexFormat.of().parseHex(nonceHex);
        byte[] tag = HexFormat.of().parseHex(tagHex);

        // 拼接密文与 Tag 用于标准 GCM 解密
        byte[] cipherWithTag = new byte[cipherBytes.length + tag.length];
        System.arraycopy(cipherBytes, 0, cipherWithTag, 0, cipherBytes.length);
        System.arraycopy(tag, 0, cipherWithTag, cipherBytes.length, tag.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
        SecretKeySpec secretKey = new SecretKeySpec(aesKey, "AES");
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

        byte[] plaintext = cipher.doFinal(cipherWithTag);
        return new String(plaintext, StandardCharsets.UTF_8);
    }
}`;
