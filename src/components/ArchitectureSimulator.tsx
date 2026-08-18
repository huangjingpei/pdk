import React, { useState } from 'react';
import { Play, RotateCcw, Shield, CheckCircle2, XCircle, AlertTriangle, Key, Cpu, Zap, CreditCard } from 'lucide-react';
import { PackageTier } from '../types';

export const ArchitectureSimulator: React.FC = () => {
  // Simulator State
  const [selectedTier, setSelectedTier] = useState<number>(200);
  const [accountCountX, setAccountCountX] = useState<number>(4);
  const [callsPerAccountY, setCallsPerAccountY] = useState<number>(250);
  const [durationDays, setDurationDays] = useState<number>(30);

  // Runtime User Simulation
  const [userStatus, setUserStatus] = useState<'TRIAL' | 'ACTIVE' | 'EXPIRED'>('TRIAL');
  const [phone, setPhone] = useState<string>('13800138000');
  const [totalLimit, setTotalLimit] = useState<number>(50); // initial trial 50
  const [totalUsed, setTotalUsed] = useState<number>(0);
  const [currentAccountIndex, setCurrentAccountIndex] = useState<number>(0);

  // Sub-account usage tracker
  const [accountSlots, setAccountSlots] = useState<{ id: string; used: number; max: number; status: 'ACTIVE' | 'LOCKED' }[]>([
    { id: 'PDD_TRIAL_01', used: 0, max: 50, status: 'ACTIVE' }
  ]);

  // Request Execution Log
  const [logs, setLogs] = useState<{ time: string; text: string; type: 'info' | 'success' | 'warn' | 'error'; account?: string }[]>([
    { time: '14:30:00', text: '新用户手机号 13800138000 注册成功，派发24小时免费试用配额 (1账号 * 50次)', type: 'info' }
  ]);

  // Card Key Generator State
  const [generatedKey, setGeneratedKey] = useState<string>('');
  const [keyInput, setKeyInput] = useState<string>('');
  const [auditList, setAuditList] = useState<{ key: string; pkg: string; price: number; phone: string; status: string; channel: string }[]>([
    { key: 'PDK-200M-8FA9-9B21-C410', pkg: '200元月卡(4*250)', price: 200, phone: '13912345678', status: '已核销', channel: '银行转账' }
  ]);

  // Handle Dispatch Call
  const handleMakeCall = (batch: number = 1) => {
    if (userStatus === 'EXPIRED') {
      addLog('【熔断拦截】用户授权已过期或配额已全部耗尽，服务端拒绝请求 (HTTP 403 Forbidden)', 'error');
      return;
    }

    let activeSlots = accountSlots.filter(s => s.status === 'ACTIVE' && s.used < s.max);
    if (activeSlots.length === 0) {
      setUserStatus('EXPIRED');
      addLog('【配额耗尽告警】所有分配的公共账号配额均已达到上限 (X*Y耗尽)，卡密已失效，客户端即将强制退出！', 'error');
      return;
    }

    let currentSlots = [...accountSlots];
    let newUsed = totalUsed;
    let nextIdx = currentAccountIndex;

    for (let i = 0; i < batch; i++) {
      // Find next available slot using Round Robin
      let foundSlot = false;
      for (let step = 0; step < currentSlots.length; step++) {
        let slotCandidate = currentSlots[nextIdx % currentSlots.length];
        if (slotCandidate.status === 'ACTIVE' && slotCandidate.used < slotCandidate.max) {
          slotCandidate.used += 1;
          newUsed += 1;
          if (slotCandidate.used >= slotCandidate.max) {
            slotCandidate.status = 'LOCKED';
            addLog(`【账号单项锁定】子账号 [${slotCandidate.id}] 已达到 ${slotCandidate.max} 次上限，自动锁定下线！`, 'warn', slotCandidate.id);
          }
          nextIdx = (nextIdx + 1) % currentSlots.length;
          foundSlot = true;
          break;
        }
        nextIdx = (nextIdx + 1) % currentSlots.length;
      }

      if (!foundSlot) {
        setUserStatus('EXPIRED');
        addLog('【配额全部耗尽】总调用量已达到上限，服务端已关闭该客户端的全部访问权限。', 'error');
        break;
      }
    }

    setAccountSlots(currentSlots);
    setTotalUsed(newUsed);
    setCurrentAccountIndex(nextIdx);

    const remaining = totalLimit - newUsed;
    if (remaining > 0 && remaining <= Math.max(5, Math.floor(totalLimit * 0.15))) {
      addLog(`【低余量预警】当前套餐剩余可用次数仅剩 ${remaining} 次，请注意及时充值。`, 'warn');
    } else if (remaining > 0) {
      addLog(`请求成功分流至子账号，当前累计已使用 ${newUsed}/${totalLimit} 次 (剩余: ${remaining})`, 'success');
    }
  };

  const addLog = (text: string, type: 'info' | 'success' | 'warn' | 'error', account?: string) => {
    const now = new Date().toLocaleTimeString();
    setLogs(prev => [{ time: now, text, type, account }, ...prev.slice(0, 40)]);
  };

  // Generate Card Key
  const handleGenerateCardKey = () => {
    const randomHex = () => Math.random().toString(36).substring(2, 6).toUpperCase();
    const newKey = `PDK-${selectedTier}M-${randomHex()}-${randomHex()}-${randomHex()}`;
    setGeneratedKey(newKey);
    setKeyInput(newKey);
    setAuditList(prev => [
      { key: newKey, pkg: `${selectedTier}元套餐(${accountCountX}*${callsPerAccountY})`, price: selectedTier, phone: '待核销', status: '已开卡未核销', channel: '线下银行转账' },
      ...prev
    ]);
  };

  // Activate Card Key in Simulator
  const handleActivateKey = () => {
    if (!keyInput.trim()) return;

    const newLimit = accountCountX * callsPerAccountY;
    const newSlots = Array.from({ length: accountCountX }).map((_, idx) => ({
      id: `PDD_TOKEN_POOL_${idx + 1}`,
      used: 0,
      max: callsPerAccountY,
      status: 'ACTIVE' as const
    }));

    setAccountSlots(newSlots);
    setTotalLimit(newLimit);
    setTotalUsed(0);
    setUserStatus('ACTIVE');
    setCurrentAccountIndex(0);

    setAuditList(prev => prev.map(item => item.key === keyInput ? { ...item, status: '已核销', phone: phone } : item));

    addLog(`【卡密激活成功】用户 ${phone} 成功激活卡密 ${keyInput}！注入 ${accountCountX} 个账号 * ${callsPerAccountY}次 = ${newLimit} 次总配额，有效期延期 ${durationDays} 天。`, 'success');
  };

  return (
    <div className="space-y-8 max-w-5xl mx-auto">
      {/* Title */}
      <div className="bg-white border border-slate-200 rounded-xl p-6 shadow-sm">
        <div className="flex items-center gap-3 mb-2">
          <div className="p-2 rounded-lg bg-indigo-50 text-indigo-600">
            <Cpu className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-lg font-bold text-slate-900">PDK 业务与调度逻辑实时交互沙盒 (Interactive Simulator)</h2>
            <p className="text-xs text-slate-500">验证您在文档中描述的：试用机制、X*Y 账号池轮巡调度、单账号锁定、卡密激活与熔断退出。</p>
          </div>
        </div>
      </div>

      {/* Grid: Package Config & Card Gen vs Live Dispatcher */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Left: Package Config & Card Key Generator */}
        <div className="bg-white border border-slate-200 rounded-xl p-6 shadow-sm space-y-6">
          <div className="flex items-center justify-between border-b border-slate-100 pb-3">
            <h3 className="text-sm font-bold text-slate-900 flex items-center gap-2">
              <CreditCard className="w-4 h-4 text-indigo-600" />
              1. 后台套餐配置与卡密生成 (管理员端)
            </h3>
            <span className="text-[11px] bg-slate-100 text-slate-600 px-2 py-0.5 rounded font-mono">ADMIN_ROLE</span>
          </div>

          <div className="space-y-4 text-xs">
            <div>
              <label className="block text-slate-700 font-semibold mb-1">选择/配置套餐类型</label>
              <div className="grid grid-cols-3 gap-2">
                <button
                  onClick={() => { setSelectedTier(200); setAccountCountX(4); setCallsPerAccountY(250); setDurationDays(30); }}
                  className={`p-2.5 rounded-lg border text-left transition ${selectedTier === 200 ? 'border-indigo-600 bg-indigo-50/50 text-indigo-900 font-bold' : 'border-slate-200 hover:border-slate-300'}`}
                >
                  <div className="font-bold">200元 月卡</div>
                  <div className="text-[10px] text-slate-500">4账号 * 250次 = 1000次</div>
                </button>
                <button
                  onClick={() => { setSelectedTier(500); setAccountCountX(10); setCallsPerAccountY(300); setDurationDays(90); }}
                  className={`p-2.5 rounded-lg border text-left transition ${selectedTier === 500 ? 'border-indigo-600 bg-indigo-50/50 text-indigo-900 font-bold' : 'border-slate-200 hover:border-slate-300'}`}
                >
                  <div className="font-bold">500元 季卡</div>
                  <div className="text-[10px] text-slate-500">10账号 * 300次 = 3000次</div>
                </button>
                <button
                  onClick={() => { setSelectedTier(100); setAccountCountX(2); setCallsPerAccountY(200); setDurationDays(15); }}
                  className={`p-2.5 rounded-lg border text-left transition ${selectedTier === 100 ? 'border-indigo-600 bg-indigo-50/50 text-indigo-900 font-bold' : 'border-slate-200 hover:border-slate-300'}`}
                >
                  <div className="font-bold">100元 半月卡</div>
                  <div className="text-[10px] text-slate-500">2账号 * 200次 = 400次</div>
                </button>
              </div>
            </div>

            <div className="grid grid-cols-3 gap-3 bg-slate-50 p-3 rounded-lg border border-slate-200">
              <div>
                <label className="text-slate-600 font-medium">账号数 (X)</label>
                <input
                  type="number"
                  value={accountCountX}
                  onChange={(e) => setAccountCountX(Math.max(1, parseInt(e.target.value) || 1))}
                  className="w-full mt-1 bg-white border border-slate-300 rounded px-2 py-1 font-mono text-xs"
                />
              </div>
              <div>
                <label className="text-slate-600 font-medium">单账号上限 (Y)</label>
                <input
                  type="number"
                  value={callsPerAccountY}
                  onChange={(e) => setCallsPerAccountY(Math.max(1, parseInt(e.target.value) || 1))}
                  className="w-full mt-1 bg-white border border-slate-300 rounded px-2 py-1 font-mono text-xs"
                />
              </div>
              <div>
                <label className="text-slate-600 font-medium">总理论上限 (X*Y)</label>
                <div className="mt-1 bg-white border border-slate-300 rounded px-2 py-1 font-mono font-bold text-indigo-600">
                  {accountCountX * callsPerAccountY} 次
                </div>
              </div>
            </div>

            <button
              onClick={handleGenerateCardKey}
              className="w-full bg-slate-900 hover:bg-slate-800 text-white font-medium py-2 rounded-lg transition flex items-center justify-center gap-2"
            >
              <Key className="w-3.5 h-3.5" />
              生成一次性充值卡密并记录财务对账
            </button>

            {generatedKey && (
              <div className="p-3 bg-emerald-50 border border-emerald-200 rounded-lg text-emerald-900">
                <div className="font-semibold text-[11px] mb-1">最新生成卡密 (发给客户):</div>
                <div className="font-mono font-bold text-sm select-all bg-white px-2 py-1 rounded border border-emerald-300">
                  {generatedKey}
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Right: Client Activation & Live Dispatcher */}
        <div className="bg-white border border-slate-200 rounded-xl p-6 shadow-sm space-y-6">
          <div className="flex items-center justify-between border-b border-slate-100 pb-3">
            <h3 className="text-sm font-bold text-slate-900 flex items-center gap-2">
              <Zap className="w-4 h-4 text-indigo-600" />
              2. 客户端卡密激活与调度消费 (客户端)
            </h3>
            <span className={`text-[11px] px-2 py-0.5 rounded font-mono font-semibold ${userStatus === 'ACTIVE' ? 'bg-emerald-100 text-emerald-800' : userStatus === 'TRIAL' ? 'bg-amber-100 text-amber-800' : 'bg-rose-100 text-rose-800'}`}>
              状态: {userStatus === 'ACTIVE' ? '正式套餐生效中' : userStatus === 'TRIAL' ? '1天免费试用中' : '已过期阻断'}
            </span>
          </div>

          {/* Activation Bar */}
          <div className="space-y-2 text-xs">
            <div className="flex gap-2">
              <input
                type="text"
                placeholder="输入卡密 PDK-XXXX-XXXX..."
                value={keyInput}
                onChange={(e) => setKeyInput(e.target.value)}
                className="flex-1 border border-slate-300 rounded-lg px-3 py-1.5 font-mono text-xs focus:outline-none focus:border-indigo-500"
              />
              <button
                onClick={handleActivateKey}
                className="bg-indigo-600 hover:bg-indigo-700 text-white font-semibold px-4 py-1.5 rounded-lg transition"
              >
                激活
              </button>
            </div>
          </div>

          {/* Quota Progress */}
          <div className="bg-slate-50 border border-slate-200 rounded-lg p-4 space-y-3 text-xs">
            <div className="flex justify-between items-center">
              <span className="font-semibold text-slate-700">总配额消耗进度 (X*Y)</span>
              <span className="font-mono font-bold text-slate-900">{totalUsed} / {totalLimit} 次 ({Math.round((totalUsed / totalLimit) * 100)}%)</span>
            </div>
            <div className="w-full bg-slate-200 rounded-full h-2.5 overflow-hidden">
              <div
                className={`h-2.5 rounded-full transition-all duration-300 ${totalUsed >= totalLimit ? 'bg-rose-500' : totalUsed / totalLimit > 0.85 ? 'bg-amber-500' : 'bg-indigo-600'}`}
                style={{ width: `${Math.min(100, (totalUsed / totalLimit) * 100)}%` }}
              ></div>
            </div>

            {/* Individual Account Slot Visualization */}
            <div className="pt-2">
              <div className="text-[11px] text-slate-500 mb-2 font-medium">当前绑定的公共账号池状态 (轮巡调度):</div>
              <div className="grid grid-cols-2 gap-2">
                {accountSlots.map((slot, idx) => (
                  <div key={idx} className={`p-2 rounded border text-[11px] ${slot.status === 'LOCKED' ? 'bg-slate-100 border-slate-200 text-slate-400' : 'bg-white border-indigo-100 text-slate-800 shadow-2xs'}`}>
                    <div className="flex justify-between items-center font-mono">
                      <span className="font-semibold">{slot.id}</span>
                      <span className={`text-[10px] px-1 rounded ${slot.status === 'LOCKED' ? 'bg-rose-100 text-rose-700 font-bold' : 'bg-emerald-100 text-emerald-700'}`}>
                        {slot.status === 'LOCKED' ? '已锁' : '就绪'}
                      </span>
                    </div>
                    <div className="mt-1 flex justify-between text-slate-500 text-[10px]">
                      <span>已用: {slot.used}/{slot.max}</span>
                      <span>{Math.round((slot.used / slot.max) * 100)}%</span>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>

          {/* Action Triggers */}
          <div className="flex gap-2">
            <button
              onClick={() => handleMakeCall(1)}
              disabled={userStatus === 'EXPIRED'}
              className="flex-1 bg-indigo-600 hover:bg-indigo-700 disabled:bg-slate-300 text-white font-medium py-2 rounded-lg text-xs transition flex items-center justify-center gap-1.5"
            >
              <Play className="w-3.5 h-3.5" />
              模拟发起 1 次调度调用 (轮巡)
            </button>
            <button
              onClick={() => handleMakeCall(50)}
              disabled={userStatus === 'EXPIRED'}
              className="bg-indigo-50 hover:bg-indigo-100 disabled:bg-slate-100 text-indigo-700 font-semibold px-3 py-2 rounded-lg text-xs transition"
            >
              +50次 压力测试
            </button>
          </div>
        </div>
      </div>

      {/* Logs Console */}
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 text-slate-200 font-mono text-xs shadow-md">
        <div className="flex items-center justify-between border-b border-slate-800 pb-3 mb-3">
          <div className="flex items-center gap-2">
            <div className="w-2.5 h-2.5 rounded-full bg-emerald-500 animate-pulse"></div>
            <span className="font-bold text-slate-300">系统调度与风控实时事件监控流 (Event Bus Logs)</span>
          </div>
          <button
            onClick={() => setLogs([])}
            className="text-[11px] text-slate-400 hover:text-slate-200 flex items-center gap-1"
          >
            <RotateCcw className="w-3 h-3" /> 清屏
          </button>
        </div>

        <div className="h-44 overflow-y-auto space-y-1.5 pr-2">
          {logs.map((log, idx) => (
            <div key={idx} className="flex items-start gap-2 leading-relaxed">
              <span className="text-slate-500 shrink-0">[{log.time}]</span>
              <span className={
                log.type === 'error' ? 'text-rose-400 font-semibold' :
                log.type === 'warn' ? 'text-amber-400' :
                log.type === 'success' ? 'text-emerald-400' : 'text-slate-300'
              }>
                {log.text}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};
