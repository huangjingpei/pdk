import React, { useState } from 'react';
import { 
  CreditCard, 
  PlusCircle, 
  CheckCircle2, 
  Clock, 
  Ban, 
  Copy, 
  Check, 
  ShieldCheck, 
  ArrowRight, 
  Smartphone, 
  Zap, 
  Layers, 
  RefreshCw,
  AlertTriangle,
  Play,
  RotateCcw
} from 'lucide-react';
import { CardKeyEntity, UserAccount, PackageTier } from '../types';

interface CardKeyStudioProps {
  cardKeys: CardKeyEntity[];
  packages: PackageTier[];
  currentUser: UserAccount;
  onGenerateCard: (packageId: number, count: number) => void;
  onActivateCard: (cardKey: string, phone: string, paymentChannel: any, txnNo: string) => { success: boolean; message: string };
  onSimulateDispatch: (phone: string) => { success: boolean; message: string; accountUsed?: string };
}

export const CardKeyStudio: React.FC<CardKeyStudioProps> = ({
  cardKeys,
  packages,
  currentUser,
  onGenerateCard,
  onActivateCard,
  onSimulateDispatch
}) => {
  const [selectedPackageId, setSelectedPackageId] = useState(packages[0].id);
  const [cardCount, setCardCount] = useState(3);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  // 模拟客户端激活状态
  const [inputCardKey, setInputCardKey] = useState('');
  const [activationPhone, setActivationPhone] = useState(currentUser.phone);
  const [paymentChannel, setPaymentChannel] = useState<'BANK_TRANSFER' | 'ALIPAY' | 'WECHAT_PAY'>('BANK_TRANSFER');
  const [txnNo, setTxnNo] = useState(`TXN_${Date.now()}`);
  const [activationResult, setActivationResult] = useState<{ success: boolean; message: string } | null>(null);

  // 调度演示日志
  const [dispatchLogs, setDispatchLogs] = useState<Array<{ id: number; time: string; msg: string; status: 'SUCCESS' | 'BLOCKED' }>>([]);

  const handleCopy = (key: string) => {
    navigator.clipboard.writeText(key);
    setCopiedKey(key);
    setInputCardKey(key);
    setTimeout(() => setCopiedKey(null), 2000);
  };

  const handleGenerate = (e: React.FormEvent) => {
    e.preventDefault();
    onGenerateCard(selectedPackageId, cardCount);
  };

  const handleActivate = (e: React.FormEvent) => {
    e.preventDefault();
    if (!inputCardKey.trim()) return;
    const res = onActivateCard(inputCardKey.trim(), activationPhone, paymentChannel, txnNo);
    setActivationResult(res);
    if (res.success) {
      setTxnNo(`TXN_${Date.now()}`);
    }
  };

  const handleDispatch = () => {
    const res = onSimulateDispatch(currentUser.phone);
    const now = new Date().toLocaleTimeString();
    setDispatchLogs(prev => [
      {
        id: Date.now(),
        time: now,
        msg: res.message,
        status: res.success ? 'SUCCESS' : 'BLOCKED'
      },
      ...prev.slice(0, 15)
    ]);
  };

  return (
    <div className="space-y-8">
      {/* 顶部声明 Banner */}
      <div className="bg-gradient-to-r from-blue-900 to-indigo-900 text-white p-6 rounded-2xl shadow-md border border-blue-700">
        <div className="flex items-center gap-3 mb-2">
          <CreditCard className="w-6 h-6 text-blue-300" />
          <h2 className="text-xl font-bold">业务卡密凭证生成与客户端原子激活 (pdk_card_key)</h2>
        </div>
        <p className="text-xs text-blue-200 max-w-3xl leading-relaxed">
          卡密凭证表专职负责生成纯授权凭据；当客户端调用激活接口时，服务端在 <strong>@Transactional 本地事务</strong> 中原子执行：
          <span className="font-semibold text-emerald-300">「更新卡密状态为 ACTIVATED → 插入独立的 pdk_financial_income 财务收入流水 → 延长用户到期日 → 注入 Redis 配额」</span>。
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* 左侧：管理员制卡中心 */}
        <div className="lg:col-span-5 space-y-6">
          <div className="bg-white border border-slate-200 rounded-xl p-5 shadow-xs">
            <h3 className="font-bold text-slate-900 text-sm flex items-center gap-2 mb-4 border-b border-slate-100 pb-3">
              <PlusCircle className="w-4 h-4 text-indigo-600" />
              <span>后台管理：批量生成卡密凭证</span>
            </h3>

            <form onSubmit={handleGenerate} className="space-y-4 text-xs">
              <div>
                <label className="block text-slate-700 font-semibold mb-1">选择套餐规格 (X * Y 矩阵)</label>
                <select
                  value={selectedPackageId}
                  onChange={(e) => setSelectedPackageId(Number(e.target.value))}
                  className="w-full p-2.5 border border-slate-300 rounded-lg font-medium"
                >
                  {packages.map(pkg => (
                    <option key={pkg.id} value={pkg.id}>
                      {pkg.name} - ¥{pkg.price} (X={pkg.accountCountX}账号 × Y={pkg.callsPerAccountY}次 = {pkg.totalCalls}次)
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label className="block text-slate-700 font-semibold mb-1">单次批量生成数量</label>
                <div className="flex items-center gap-2">
                  <input
                    type="number"
                    min="1"
                    max="50"
                    value={cardCount}
                    onChange={(e) => setCardCount(Number(e.target.value))}
                    className="w-full p-2 border border-slate-300 rounded-lg font-mono"
                  />
                  <button
                    type="submit"
                    className="px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white font-semibold rounded-lg shrink-0 transition"
                  >
                    立即批量制卡
                  </button>
                </div>
              </div>
            </form>
          </div>

          {/* 卡密凭证物理表数据预览 */}
          <div className="bg-white border border-slate-200 rounded-xl p-5 shadow-xs">
            <div className="flex items-center justify-between mb-3 border-b border-slate-100 pb-2">
              <h3 className="font-bold text-slate-900 text-xs flex items-center gap-1.5">
                <Layers className="w-3.5 h-3.5 text-blue-600" />
                <span>卡密凭证表数据 (pdk_card_key)</span>
              </h3>
              <span className="text-[11px] text-slate-400 font-mono">共 {cardKeys.length} 张</span>
            </div>

            <div className="space-y-2 max-h-96 overflow-y-auto pr-1">
              {cardKeys.slice().reverse().map(card => (
                <div 
                  key={card.id} 
                  className={`p-2.5 rounded-lg border text-xs transition ${
                    card.status === 'ACTIVATED' 
                      ? 'bg-slate-50 border-slate-200 opacity-60' 
                      : 'bg-blue-50/50 border-blue-200 hover:border-blue-400'
                  }`}
                >
                  <div className="flex items-center justify-between mb-1">
                    <span className="font-mono font-bold text-slate-900 flex items-center gap-1">
                      {card.cardKey}
                      <button
                        onClick={() => handleCopy(card.cardKey)}
                        className="text-slate-400 hover:text-indigo-600"
                        title="复制卡密并填入激活框"
                      >
                        {copiedKey === card.cardKey ? <Check className="w-3 h-3 text-emerald-600" /> : <Copy className="w-3 h-3" />}
                      </button>
                    </span>
                    <span className={`px-1.5 py-0.5 rounded text-[10px] font-bold ${
                      card.status === 'UNUSED' 
                        ? 'bg-emerald-100 text-emerald-800' 
                        : 'bg-slate-200 text-slate-700'
                    }`}>
                      {card.status === 'UNUSED' ? '待核销' : '已核销'}
                    </span>
                  </div>
                  <div className="flex items-center justify-between text-[11px] text-slate-500">
                    <span>{card.packageName} (¥{card.faceValue})</span>
                    {card.boundUserPhone && (
                      <span className="font-mono text-emerald-700">核销给: {card.boundUserPhone}</span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* 右侧：客户端模拟激活与实时轮巡调度 */}
        <div className="lg:col-span-7 space-y-6">
          {/* 客户端卡密激活模拟 */}
          <div className="bg-white border-2 border-indigo-500 rounded-xl p-6 shadow-sm">
            <div className="flex items-center justify-between border-b border-indigo-100 pb-3 mb-4">
              <div className="flex items-center gap-2">
                <Smartphone className="w-5 h-5 text-indigo-600" />
                <h3 className="font-bold text-slate-900 text-sm">客户端卡密原子激活对账模拟器</h3>
              </div>
              <span className="text-[11px] bg-indigo-50 text-indigo-700 px-2 py-0.5 rounded font-mono font-semibold">
                ACID 事务环境
              </span>
            </div>

            <form onSubmit={handleActivate} className="space-y-4 text-xs">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className="block text-slate-700 font-semibold mb-1">激活客户手机号 (资产主体)</label>
                  <input
                    type="text"
                    value={activationPhone}
                    onChange={(e) => setActivationPhone(e.target.value)}
                    className="w-full p-2 border border-slate-300 rounded-lg font-mono font-bold text-slate-900"
                    required
                  />
                </div>
                <div>
                  <label className="block text-slate-700 font-semibold mb-1">线下付款渠道</label>
                  <select
                    value={paymentChannel}
                    onChange={(e) => setPaymentChannel(e.target.value as any)}
                    className="w-full p-2 border border-slate-300 rounded-lg"
                  >
                    <option value="BANK_TRANSFER">招商银行对公/个人转账</option>
                    <option value="ALIPAY">企业支付宝收款</option>
                    <option value="WECHAT_PAY">微信企业商户收款</option>
                  </select>
                </div>
              </div>

              <div>
                <label className="block text-slate-700 font-semibold mb-1">
                  输入卡密串 (可直接点击左侧列表的复制按钮填入)
                </label>
                <input
                  type="text"
                  placeholder="PDK-XXXX-XXXX-XXXX"
                  value={inputCardKey}
                  onChange={(e) => setInputCardKey(e.target.value)}
                  className="w-full p-2.5 border-2 border-indigo-300 focus:border-indigo-600 rounded-lg font-mono font-bold text-sm tracking-wider text-indigo-950"
                  required
                />
              </div>

              <div className="flex items-center justify-between pt-2">
                <div className="text-[11px] text-slate-500">
                  点击后立即执行行级排他锁核销并同步生成财务收入凭据
                </div>
                <button
                  type="submit"
                  className="px-5 py-2.5 bg-emerald-600 hover:bg-emerald-700 text-white font-bold rounded-lg shadow-sm transition flex items-center gap-1.5"
                >
                  <ShieldCheck className="w-4 h-4" />
                  <span>执行原子事务激活</span>
                </button>
              </div>

              {activationResult && (
                <div className={`p-3 rounded-lg text-xs font-semibold flex items-center gap-2 ${
                  activationResult.success ? 'bg-emerald-50 text-emerald-800 border border-emerald-200' : 'bg-rose-50 text-rose-800 border border-rose-200'
                }`}>
                  {activationResult.success ? <CheckCircle2 className="w-4 h-4 text-emerald-600 shrink-0" /> : <AlertTriangle className="w-4 h-4 text-rose-600 shrink-0" />}
                  <span>{activationResult.message}</span>
                </div>
              )}
            </form>
          </div>

          {/* 实时调度与配额扣减交互区 */}
          <div className="bg-slate-900 text-white rounded-xl p-6 shadow-md border border-slate-800 space-y-4">
            <div className="flex items-center justify-between border-b border-slate-800 pb-3">
              <div className="flex items-center gap-2">
                <Zap className="w-4 h-4 text-amber-400" />
                <h4 className="font-bold text-sm">拼多多公共Token资产池轮巡调度模拟</h4>
              </div>
              <div className="text-xs font-mono text-slate-400">
                当前用户状态: <span className="text-emerald-400 font-bold">{currentUser.status}</span>
              </div>
            </div>

            {/* 当前用户配额仪表盘 */}
            <div className="grid grid-cols-3 gap-3 text-xs font-mono">
              <div className="bg-slate-800/80 p-3 rounded-lg border border-slate-700">
                <div className="text-slate-400 text-[11px]">总授权配额 (X*Y)</div>
                <div className="text-lg font-bold text-white mt-0.5">{currentUser.totalCallsLimit} 次</div>
              </div>
              <div className="bg-slate-800/80 p-3 rounded-lg border border-slate-700">
                <div className="text-slate-400 text-[11px]">已调度使用</div>
                <div className="text-lg font-bold text-amber-400 mt-0.5">{currentUser.usedCalls} 次</div>
              </div>
              <div className="bg-slate-800/80 p-3 rounded-lg border border-slate-700">
                <div className="text-slate-400 text-[11px]">剩余可用次数</div>
                <div className="text-lg font-bold text-emerald-400 mt-0.5">{currentUser.remainingCalls} 次</div>
              </div>
            </div>

            <div className="flex items-center gap-3 pt-2">
              <button
                onClick={handleDispatch}
                disabled={currentUser.remainingCalls <= 0}
                className={`flex-1 py-2.5 px-4 rounded-lg font-bold text-xs flex items-center justify-center gap-2 transition ${
                  currentUser.remainingCalls > 0
                    ? 'bg-amber-500 hover:bg-amber-600 text-slate-950 cursor-pointer shadow-xs'
                    : 'bg-slate-700 text-slate-500 cursor-not-allowed'
                }`}
              >
                <Play className="w-3.5 h-3.5 fill-current" />
                <span>发起一次拼多多 API 调度请求 (消耗 1 次配额并轮巡)</span>
              </button>
            </div>

            {/* 调度日志流 */}
            <div className="bg-slate-950 rounded-lg p-3 border border-slate-800/80 font-mono text-[11px] h-40 overflow-y-auto space-y-1.5">
              <div className="text-slate-500">// 实时网关调度流水日志 (Round-Robin 均衡分流):</div>
              {dispatchLogs.length > 0 ? (
                dispatchLogs.map(log => (
                  <div key={log.id} className={`flex items-start gap-2 ${log.status === 'SUCCESS' ? 'text-emerald-400' : 'text-rose-400'}`}>
                    <span className="text-slate-500">[{log.time}]</span>
                    <span>{log.msg}</span>
                  </div>
                ))
              ) : (
                <div className="text-slate-600 text-center py-8">点击上方按钮发起拼多多Token调度请求</div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
