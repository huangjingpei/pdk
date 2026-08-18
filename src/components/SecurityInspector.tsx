import React, { useState } from 'react';
import { ShieldCheck, Lock, Unlock, RefreshCw, KeyRound, Terminal } from 'lucide-react';

export const SecurityInspector: React.FC = () => {
  const [plainPayload, setPlainPayload] = useState<string>(
    JSON.stringify({
      userId: 10086,
      phone: "13800138000",
      action: "FETCH_PDD_ORDER_STREAM",
      timestamp: Date.now(),
      targetUrl: "https://mobile.yangkeduo.com/api/item/query"
    }, null, 2)
  );

  const [sessionKey, setSessionKey] = useState<string>("PDK_SEC_KEY_8F92A0B7C6E14");
  const [encryptedPacket, setEncryptedPacket] = useState<string>("");
  const [signature, setSignature] = useState<string>("");
  const [nonce, setNonce] = useState<string>("");
  const [decryptedResult, setDecryptedResult] = useState<string>("");

  const handleSimulateEncrypt = () => {
    const generatedNonce = Math.random().toString(36).substring(2, 18);
    const ts = Date.now().toString();
    
    // Simple mock reversible visual encryption logic to demonstrate the wire format
    const str = plainPayload;
    let enc = "";
    for (let i = 0; i < str.length; i++) {
      const charCode = str.charCodeAt(i) ^ 0x5A ^ (sessionKey.charCodeAt(i % sessionKey.length));
      enc += charCode.toString(16).padStart(2, '0');
    }
    
    const fakeSign = "HMAC_" + Math.abs(Math.sin(parseInt(ts)) * 1000000000).toString(16).substring(0, 16);
    
    setNonce(generatedNonce);
    setSignature(fakeSign);
    setEncryptedPacket(`0x5044_V1_${enc.substring(0, 48)}...[LENGTH=${enc.length / 2}B]`);
    setDecryptedResult(plainPayload);
  };

  return (
    <div className="space-y-6 max-w-5xl mx-auto">
      <div className="bg-white border border-slate-200 rounded-xl p-6 shadow-sm">
        <div className="flex items-center gap-3 mb-2">
          <div className="p-2 rounded-lg bg-indigo-50 text-indigo-600">
            <Lock className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-lg font-bold text-slate-900">通信加密与防抓包逆向演示中心 (Security & Anti-Reverse Protocol)</h2>
            <p className="text-xs text-slate-500">模拟客户端发包前的多层混淆加密与服务端 SpringBoot Filter 解密验签过程。</p>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {/* Client Side: Plaintext to Encrypted Packet */}
        <div className="bg-white border border-slate-200 rounded-xl p-6 shadow-sm space-y-4">
          <div className="flex items-center justify-between border-b border-slate-100 pb-3">
            <h3 className="text-sm font-bold text-slate-900 flex items-center gap-2">
              <Terminal className="w-4 h-4 text-indigo-600" />
              客户端发包前处理 (AES-GCM + XOR 混淆)
            </h3>
            <span className="text-[11px] bg-slate-100 text-slate-600 px-2 py-0.5 rounded font-mono">CLIENT_OUTBOUND</span>
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-700 mb-1">原始业务请求体 (JSON)</label>
            <textarea
              value={plainPayload}
              onChange={(e) => setPlainPayload(e.target.value)}
              rows={7}
              className="w-full bg-slate-900 text-emerald-400 font-mono text-xs p-3 rounded-lg border border-slate-800 focus:outline-none"
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-700 mb-1">动态协商会话秘钥 (Session Secret)</label>
            <div className="flex gap-2">
              <input
                type="text"
                value={sessionKey}
                onChange={(e) => setSessionKey(e.target.value)}
                className="flex-1 bg-slate-50 border border-slate-300 rounded px-2.5 py-1 text-xs font-mono"
              />
              <button
                onClick={() => setSessionKey("PDK_SEC_KEY_" + Math.random().toString(36).substring(2, 10).toUpperCase())}
                className="p-1.5 border border-slate-300 rounded hover:bg-slate-50"
                title="重新生成密钥"
              >
                <RefreshCw className="w-3.5 h-3.5 text-slate-600" />
              </button>
            </div>
          </div>

          <button
            onClick={handleSimulateEncrypt}
            className="w-full bg-indigo-600 hover:bg-indigo-700 text-white font-semibold py-2 rounded-lg text-xs transition flex items-center justify-center gap-2"
          >
            <Lock className="w-3.5 h-3.5" />
            执行加密封包并生成防重放签名
          </button>
        </div>

        {/* Wire & Server Side */}
        <div className="bg-white border border-slate-200 rounded-xl p-6 shadow-sm space-y-4">
          <div className="flex items-center justify-between border-b border-slate-100 pb-3">
            <h3 className="text-sm font-bold text-slate-900 flex items-center gap-2">
              <ShieldCheck className="w-4 h-4 text-emerald-600" />
              抓包工具捕获形态 vs 服务端还原
            </h3>
            <span className="text-[11px] bg-slate-100 text-slate-600 px-2 py-0.5 rounded font-mono">SERVER_INBOUND</span>
          </div>

          {encryptedPacket ? (
            <div className="space-y-3 text-xs">
              <div className="bg-rose-50 border border-rose-200 rounded-lg p-3">
                <div className="font-semibold text-rose-900 mb-1">Charles / Fiddler 抓包工具视角 (不可破解的密文流):</div>
                <div className="font-mono text-rose-800 break-all text-[11px] bg-white p-2 rounded border border-rose-300">
                  {encryptedPacket}
                </div>
                <div className="mt-1.5 text-[10px] text-rose-700 flex justify-between font-mono">
                  <span>Nonce: {nonce}</span>
                  <span>Sign: {signature}</span>
                </div>
              </div>

              <div className="bg-emerald-50 border border-emerald-200 rounded-lg p-3">
                <div className="font-semibold text-emerald-900 mb-1">SpringBoot 服务端过滤器验签与解密还原结果:</div>
                <pre className="font-mono text-emerald-900 text-[11px] bg-white p-2 rounded border border-emerald-300 overflow-x-auto">
                  {decryptedResult}
                </pre>
                <div className="mt-1 text-[10px] text-emerald-700">
                  ✅ 签名校验通过 (HMAC-SHA256) | Nonce 防重放检查通过 (Redis TTL 30s)
                </div>
              </div>
            </div>
          ) : (
            <div className="p-8 text-center text-slate-400 text-xs border border-dashed border-slate-200 rounded-lg">
              请点击左侧「执行加密封包」按钮查看抓包与还原演示
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
