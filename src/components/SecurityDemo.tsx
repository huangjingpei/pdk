import React, { useState } from 'react';
import { 
  ShieldCheck, 
  Terminal, 
  Lock, 
  Unlock, 
  ArrowRight, 
  Cpu, 
  RefreshCw, 
  FileCode, 
  EyeOff, 
  Binary, 
  CheckCircle2, 
  AlertCircle,
  Copy,
  Check
} from 'lucide-react';

export const SecurityDemo: React.FC = () => {
  const [copied, setCopied] = useState(false);
  const [requestPayload, setRequestPayload] = useState(
    JSON.stringify(
      {
        action: 'PDD_GOODS_SEARCH',
        keyword: '品牌女装夏季连衣裙',
        page: 1,
        pageSize: 20,
        clientTimestamp: Date.now()
      },
      null,
      2
    )
  );

  // 模拟抓包二进制流
  const [isEncrypted, setIsEncrypted] = useState(true);
  const [nonce, setNonce] = useState('8f9a12c499');

  const handleCopySDK = () => {
    const code = `// 客户端业务代码：零负担，直接像普通 axios 一样调用
import { pdkClient } from '@pdk/client-sdk';

const response = await pdkClient.post('/api/v1/dispatch/acquire-token', {
  action: 'PDD_GOODS_SEARCH',
  keyword: '品牌女装夏季连衣裙',
  page: 1
});

// 响应已由 SDK 底层拦截器透明解密为原生 JSON
console.log('商品搜索结果:', response.data);`;
    navigator.clipboard.writeText(code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="space-y-6">
      <div className="bg-gradient-to-r from-slate-900 to-indigo-950 text-white rounded-2xl p-6 shadow-md border border-slate-800">
        <div className="flex items-center gap-3 mb-2">
          <ShieldCheck className="w-6 h-6 text-emerald-400" />
          <h2 className="text-xl font-bold">通信加密方案实测：客户端极简调用 vs 深度防逆向抓包</h2>
        </div>
        <p className="text-xs text-slate-300 max-w-3xl leading-relaxed">
          解决用户最关心的两大痛点：<strong>① 客户端业务层零感知、一行代码调用</strong>；<strong>② 网络抓包工具（Fiddler / Charles）与反编译器只能看到损坏的混淆二进制流</strong>。
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* 左侧：客户端体验 (极简透明) */}
        <div className="bg-white border border-slate-200 rounded-xl p-5 shadow-xs space-y-4">
          <div className="flex items-center justify-between border-b border-slate-100 pb-3">
            <div className="flex items-center gap-2">
              <Terminal className="w-4 h-4 text-indigo-600" />
              <h3 className="font-bold text-slate-900 text-sm">客户端开发视角 (极简 SDK 接入)</h3>
            </div>
            <button
              onClick={handleCopySDK}
              className="text-xs text-indigo-600 hover:text-indigo-700 font-semibold flex items-center gap-1"
            >
              {copied ? <Check className="w-3.5 h-3.5 text-emerald-600" /> : <Copy className="w-3.5 h-3.5" />}
              <span>{copied ? '已复制' : '复制客户端调用示例'}</span>
            </button>
          </div>

          <div className="text-xs text-slate-600 space-y-2">
            <p>
              业务开发人员无需编写复杂的加解密与签名算法，直接向服务端发起标准对象调用：
            </p>
            <div className="bg-slate-900 text-emerald-400 p-4 rounded-xl font-mono text-xs overflow-x-auto leading-relaxed">
              <pre>{`// 1. 初始化客户端 (全局仅需配置一次)
import { createPdkClient } from '@pdk/client-sdk';

const pdkClient = createPdkClient({
  baseURL: 'https://api.pdk-server.com',
  clientSecret: 'PDK_SEC_2026_PROD'
});

// 2. 业务发起调用 (底层拦截器自动完成 AES+字节混淆)
const res = await pdkClient.post('/api/v1/dispatch/acquire-token', {
  action: 'QUERY_ORDER',
  orderSn: '260815-998120391203'
});

// 3. 自动解密为正常对象，无任何额外处理
console.log(res.data.status); // "SUCCESS"`}</pre>
            </div>
          </div>
        </div>

        {/* 右侧：抓包工具视角 (完全无法破解的二进制混淆) */}
        <div className="bg-slate-900 text-slate-100 border border-slate-800 rounded-xl p-5 shadow-md space-y-4">
          <div className="flex items-center justify-between border-b border-slate-800 pb-3">
            <div className="flex items-center gap-2">
              <EyeOff className="w-4 h-4 text-rose-400" />
              <h3 className="font-bold text-white text-sm">Fiddler / Charles 抓包工具捕获视角</h3>
            </div>
            <span className="text-[10px] bg-rose-950 text-rose-400 border border-rose-800 px-2 py-0.5 rounded font-mono font-bold">
              UNREADABLE_STREAM
            </span>
          </div>

          <div className="space-y-3 font-mono text-xs">
            <div className="text-slate-400 text-[11px]">
              HTTP 请求抓包抓取到的实际网络报文：
            </div>

            <div className="bg-slate-950 p-3 rounded-lg border border-slate-800 text-slate-300 space-y-1.5 text-[11px]">
              <div className="text-indigo-400">POST /api/v1/dispatch/acquire-token HTTP/1.1</div>
              <div className="text-slate-500">Host: api.pdk-server.com</div>
              <div className="text-slate-500">Content-Type: application/octet-stream</div>
              <div className="text-amber-400">X-PDK-Phone: 13800138000</div>
              <div className="text-slate-500">X-PDK-Device-ID: MAC-00-1B-44-11-3A-B7</div>
              <div className="text-slate-500">tokenName: &lt;tokenValue&gt;</div>
              <div className="border-t border-slate-800 pt-2 text-rose-300 break-all leading-4">
                50 44 24 31 21 9F E3 12 6B 38 23 38 66 5F 61 39 31 30 78 30 61 39 66 38 32 31 37 33 39 00 FF A1 B2 C3 D4 E5 F6 07 18 29 3A 4B 5C 6D 7E 8F 90 A1 B2 C3 D4 E5 F6
              </div>
            </div>

            <div className="p-3 bg-slate-800/60 rounded-lg text-[11px] text-slate-300 space-y-1">
              <div className="font-bold text-amber-300 flex items-center gap-1">
                <Lock className="w-3.5 h-3.5 text-amber-400" />
                <span>防抓包三大核心防护屏障：</span>
              </div>
              <ol className="list-decimal pl-4 space-y-0.5 text-slate-400">
                <li><strong>动态时间窗口派生密钥：</strong> 密钥每 10 分钟（600 秒）动态轮转，杜绝硬编码静态 Key；</li>
                <li><strong>字节翻转与魔数异或 (Byte-Flip)：</strong> 打上 <code>PD$1!</code> 私有魔数，抓包软件识别为损坏协议；</li>
                <li><strong>防重放 HMAC 校验：</strong> 每次携带毫秒时间戳与一次性 Nonce，任何重放攻击直接 403 丢弃。</li>
              </ol>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
