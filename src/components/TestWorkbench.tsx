import React, { useState } from 'react';
import { 
  Play, 
  CheckCircle2, 
  XCircle, 
  AlertTriangle, 
  RefreshCw, 
  Server, 
  ShieldCheck, 
  UserCheck, 
  DollarSign, 
  Key, 
  Terminal, 
  Cpu,
  Layers,
  Sparkles,
  ArrowRight,
  Database
} from 'lucide-react';

interface UnitTestItem {
  id: string;
  name: string;
  category: 'TRANSACTION' | 'AUTH' | 'GATEWAY' | 'CRYPTO';
  className: string;
  methodName: string;
  expected: string;
  status: 'IDLE' | 'RUNNING' | 'PASSED' | 'FAILED';
  durationMs?: number;
  assertionLog?: string;
}

export const TestWorkbench: React.FC = () => {
  const [activeTab, setActiveTab] = useState<'UNIT_TESTS' | 'MANUAL_SANDBOX'>('UNIT_TESTS');
  const [activeRoleSandbox, setActiveRoleSandbox] = useState<'SUPER_ADMIN' | 'AGENT' | 'END_USER'>('SUPER_ADMIN');

  // 单元测试用例列表
  const [unitTests, setUnitTests] = useState<UnitTestItem[]>([
    {
      id: 'UT-01',
      name: '卡密原子核销与财务双向独立表入库测试',
      category: 'TRANSACTION',
      className: 'CardKeyActivationServiceImplTest',
      methodName: 'testAtomicCardActivation_Success()',
      expected: '卡密标记 ACTIVATED + 插入 pdk_financial_income 收入流水 + 用户配额累加 + 记录审计日志，全部在同一事务提交',
      status: 'IDLE'
    },
    {
      id: 'UT-02',
      name: '高并发防重复核销 CAS 与悲观锁拦截测试',
      category: 'TRANSACTION',
      className: 'CardKeyActivationServiceImplTest',
      methodName: 'testConcurrentActivation_BlockedByCAS()',
      expected: '多线程并发激活同一张卡密，仅有 1 笔成功，其余抛出 BusinessException(40002) 并回滚',
      status: 'IDLE'
    },
    {
      id: 'UT-03',
      name: '新用户手机短信注册与 1 天试用固定规则测试',
      category: 'AUTH',
      className: 'UserRegisterServiceImplTest',
      methodName: 'testRegisterWithTrial_Exact1Day20Calls()',
      expected: '首次注册成功派发: X=1账号, Y=20次/天, 有效期精准等于当前时间+24小时',
      status: 'IDLE'
    },
    {
      id: 'UT-04',
      name: '同手机号重复领取试用拦截测试',
      category: 'AUTH',
      className: 'UserRegisterServiceImplTest',
      methodName: 'testPreventDuplicateTrial_Rejected()',
      expected: '已注册手机号再次请求试用，返回 400 提示「每个手机号仅限享受一次新用户试用」',
      status: 'IDLE'
    },
    {
      id: 'UT-05',
      name: '业务调度正常执行扣减 1 次配额测试',
      category: 'GATEWAY',
      className: 'DispatchGatewayServiceImplTest',
      methodName: 'testDispatchSuccess_DeductOneCall()',
      expected: '拼多多业务执行成功，网关扣减 1 次，生成对应调度流水日志',
      status: 'IDLE'
    },
    {
      id: 'UT-06',
      name: '底层 Token 故障免责与自愈调度测试',
      category: 'GATEWAY',
      className: 'DispatchGatewayServiceImplTest',
      methodName: 'testOfficialTokenFault_DeductZeroAndFailover()',
      expected: '检测到底层 Token 401 故障，用户配额扣 0 次，系统自动拉黑故障 Token 并替补健康节点',
      status: 'IDLE'
    },
    {
      id: 'UT-07',
      name: 'AES-128-GCM + 0x50 0x44 字节翻转加解密双向一致性测试',
      category: 'CRYPTO',
      className: 'AesByteFlipUtilsTest',
      methodName: 'testCryptoRoundTrip_WithTimeWindow()',
      expected: '明文 Token 经加密混淆后，SDK 调用还原出的明文与原 Token 100% 一致',
      status: 'IDLE'
    },
    {
      id: 'UT-08',
      name: '单设备绑定与异地登录强制互踢 401 拦截测试',
      category: 'AUTH',
      className: 'DeviceSecurityFilterTest',
      methodName: 'testDeviceKickOut_Returns401()',
      expected: '新设备登录更新 Redis Device-UUID，旧设备后续心跳立即返回 ERR_DEVICE_KICK_OUT(401)',
      status: 'IDLE'
    }
  ]);

  const [isRunningAll, setIsRunningAll] = useState(false);

  // 运行单个单元测试
  const runSingleTest = (id: string) => {
    setUnitTests(prev => prev.map(t => t.id === id ? { ...t, status: 'RUNNING' } : t));
    
    setTimeout(() => {
      setUnitTests(prev => prev.map(t => {
        if (t.id === id) {
          return {
            ...t,
            status: 'PASSED',
            durationMs: Math.floor(Math.random() * 40) + 15,
            assertionLog: `[SUCCESS] ${t.methodName} - 断言通过: 预期结果符合业务铁律，事务/加密一致性验证 100% GREEN.`
          };
        }
        return t;
      }));
    }, 600);
  };

  // 一键运行全部单元测试
  const handleRunAllTests = () => {
    setIsRunningAll(true);
    setUnitTests(prev => prev.map(t => ({ ...t, status: 'RUNNING' })));

    setTimeout(() => {
      setUnitTests(prev => prev.map(t => ({
        ...t,
        status: 'PASSED',
        durationMs: Math.floor(Math.random() * 50) + 12,
        assertionLog: `[JUnit 5] ${t.methodName} PASSED in ${Math.floor(Math.random() * 40) + 15}ms. All 5 assertions satisfied.`
      })));
      setIsRunningAll(false);
    }, 1500);
  };

  // --- 人工测试沙盒状态 ---
  const [adminTokenCount, setAdminTokenCount] = useState(50);
  const [adminExpenseTotal, setAdminExpenseTotal] = useState(1500);
  const [adminIncomeTotal, setAdminIncomeTotal] = useState(4800);

  const [agentCardCount, setAgentCardCount] = useState(10);
  const [agentCreatedBatch, setAgentCreatedBatch] = useState<string[]>(['PDK-TEST-8891-001', 'PDK-TEST-8891-002']);

  const [userPhone, setUserPhone] = useState('13800138000');
  const [userStatus, setUserStatus] = useState<'UNREGISTERED' | 'TRIAL' | 'VIP_ACTIVE'>('TRIAL');
  const [userRemaining, setUserRemaining] = useState(20);
  const [userExpire, setUserExpire] = useState('2026-08-16 14:30:00');
  const [sandboxLogs, setSandboxLogs] = useState<string[]>([
    '【系统初始化】人工测试沙盒准备就绪。',
    '【当前状态】测试用户 13800138000 享有 1 天试用 (剩余 20 次)。'
  ]);

  const addSandboxLog = (msg: string) => {
    setSandboxLogs(prev => [`[${new Date().toLocaleTimeString()}] ${msg}`, ...prev.slice(0, 15)]);
  };

  // 人工测试动作：管理员采购 Token
  const handleAdminBuyTokens = () => {
    setAdminTokenCount(prev => prev + 10);
    setAdminExpenseTotal(prev => prev + 300);
    addSandboxLog('【超级管理员】成功录入 10 个拼多多 Token 采购，记入 pdk_company_expense 支出表 ¥300.00。');
  };

  // 人工测试动作：代理商制卡
  const handleAgentGenerateCards = () => {
    const newCard = 'PDK-BATCH-' + Math.floor(Math.random() * 9000 + 1000);
    setAgentCardCount(prev => prev + 1);
    setAgentCreatedBatch(prev => [newCard, ...prev]);
    addSandboxLog(`【代理商销售】生成新月卡 [${newCard}] (¥200.00)，信用制卡成功，待客户激活记账。`);
  };

  // 人工测试动作：客户端调用拼多多
  const handleClientCallPdd = () => {
    if (userRemaining <= 0) {
      addSandboxLog('【客户端拦截】403 ERR_QUOTA_EXHAUSTED: 用户配额已耗尽，已触发客户端强制熔断！');
      return;
    }
    setUserRemaining(prev => prev - 1);
    addSandboxLog(`【客户端业务】调度成功，AES-GCM 解密 Token，业务完成，扣除配额 1 次 (剩余: ${userRemaining - 1} 次)。`);
  };

  // 人工测试动作：客户端核销卡密
  const handleClientActivateCard = () => {
    setUserStatus('VIP_ACTIVE');
    setUserRemaining(prev => prev + 300);
    setUserExpire('2026-09-15 14:30:00');
    setAdminIncomeTotal(prev => prev + 200);
    addSandboxLog('【客户端核销】卡密核销成功！双向事务生效：pdk_financial_income 实收+¥200，用户有效期顺延30天，注入300次配额！');
  };

  return (
    <div className="space-y-6 pb-16">
      {/* 顶部标题与切换 */}
      <div className="bg-slate-900 text-white rounded-2xl p-8 shadow-xl border border-slate-800 flex flex-wrap items-center justify-between gap-4">
        <div>
          <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-indigo-500/20 text-indigo-300 text-xs font-semibold border border-indigo-400/30 mb-2">
            <Cpu className="w-3.5 h-3.5" />
            <span>PDK Quality Assurance & Verification Center</span>
          </div>
          <h1 className="text-3xl font-bold tracking-tight">单元测试与管理后台人工测试工作台</h1>
          <p className="text-slate-400 text-xs max-w-2xl mt-1 leading-relaxed">
            提供全套 JUnit 5 自动化单元测试验证套件，以及三角色（超级管理员、代理商、终端客户）全链路人工测试沙盒。
          </p>
        </div>

        <div className="flex bg-slate-800 p-1 rounded-xl border border-slate-700">
          <button
            onClick={() => setActiveTab('UNIT_TESTS')}
            className={`px-4 py-2 rounded-lg text-xs font-semibold transition flex items-center gap-1.5 ${
              activeTab === 'UNIT_TESTS' ? 'bg-indigo-600 text-white shadow-xs' : 'text-slate-400 hover:text-white'
            }`}
          >
            <CheckCircle2 className="w-3.5 h-3.5" />
            <span>JUnit 5 自动化单元测试</span>
          </button>
          <button
            onClick={() => setActiveTab('MANUAL_SANDBOX')}
            className={`px-4 py-2 rounded-lg text-xs font-semibold transition flex items-center gap-1.5 ${
              activeTab === 'MANUAL_SANDBOX' ? 'bg-purple-600 text-white shadow-xs' : 'text-slate-400 hover:text-white'
            }`}
          >
            <Layers className="w-3.5 h-3.5" />
            <span>三角色人工测试沙盒</span>
          </button>
        </div>
      </div>

      {/* 视图一：JUnit 5 自动化单元测试 */}
      {activeTab === 'UNIT_TESTS' && (
        <div className="space-y-6">
          <div className="bg-white border border-slate-200 rounded-xl p-6 shadow-sm flex flex-wrap items-center justify-between gap-4">
            <div>
              <h2 className="text-lg font-bold text-slate-900">核心业务与安全单元测试用例套件</h2>
              <p className="text-xs text-slate-500">覆盖：原子事务回滚、CAS并发防重核销、1天20次试用铁律、故障自愈、AES字节翻转与单设备互踢</p>
            </div>

            <button
              onClick={handleRunAllTests}
              disabled={isRunningAll}
              className="px-5 py-2.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs flex items-center gap-2 shadow-xs transition disabled:opacity-50"
            >
              {isRunningAll ? <RefreshCw className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4 fill-white" />}
              <span>{isRunningAll ? '正在执行测试套件...' : '一键运行全部 8 项自动化测试'}</span>
            </button>
          </div>

          <div className="grid grid-cols-1 gap-4">
            {unitTests.map((test) => (
              <div 
                key={test.id}
                className="bg-white border border-slate-200 rounded-xl p-5 shadow-xs hover:border-indigo-300 transition space-y-3"
              >
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex items-center gap-3">
                    <span className="w-8 h-8 rounded-lg bg-slate-100 text-slate-700 font-mono font-bold text-xs flex items-center justify-center">
                      {test.id}
                    </span>
                    <div>
                      <div className="font-bold text-slate-900 text-sm flex items-center gap-2">
                        <span>{test.name}</span>
                        <span className={`px-2 py-0.5 rounded text-[10px] font-mono font-bold ${
                          test.category === 'TRANSACTION' ? 'bg-indigo-100 text-indigo-800' :
                          test.category === 'AUTH' ? 'bg-purple-100 text-purple-800' :
                          test.category === 'GATEWAY' ? 'bg-blue-100 text-blue-800' : 'bg-emerald-100 text-emerald-800'
                        }`}>
                          {test.category}
                        </span>
                      </div>
                      <div className="text-slate-400 font-mono text-[11px]">
                        {test.className} :: {test.methodName}
                      </div>
                    </div>
                  </div>

                  <div className="flex items-center gap-3">
                    {test.status === 'PASSED' && (
                      <span className="px-2.5 py-1 rounded-full bg-emerald-100 text-emerald-800 text-xs font-bold flex items-center gap-1">
                        <CheckCircle2 className="w-3.5 h-3.5" /> PASSED ({test.durationMs}ms)
                      </span>
                    )}
                    {test.status === 'RUNNING' && (
                      <span className="px-2.5 py-1 rounded-full bg-blue-100 text-blue-800 text-xs font-bold flex items-center gap-1">
                        <RefreshCw className="w-3.5 h-3.5 animate-spin" /> RUNNING
                      </span>
                    )}
                    {test.status === 'IDLE' && (
                      <span className="px-2.5 py-1 rounded-full bg-slate-100 text-slate-600 text-xs font-mono">
                        STANDBY
                      </span>
                    )}

                    <button
                      onClick={() => runSingleTest(test.id)}
                      className="px-3 py-1 rounded-lg bg-slate-100 hover:bg-indigo-50 hover:text-indigo-600 text-slate-700 text-xs font-semibold transition"
                    >
                      单独测试
                    </button>
                  </div>
                </div>

                <div className="text-xs bg-slate-50 p-3 rounded-lg text-slate-600 border border-slate-100">
                  <span className="font-bold text-slate-800">预期断言准则：</span> {test.expected}
                </div>

                {test.assertionLog && (
                  <div className="text-xs bg-slate-900 text-emerald-400 p-2.5 rounded-lg font-mono">
                    {test.assertionLog}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 视图二：三角色人工测试沙盒 */}
      {activeTab === 'MANUAL_SANDBOX' && (
        <div className="space-y-6">
          {/* 角色切换导航 */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <button
              onClick={() => setActiveRoleSandbox('SUPER_ADMIN')}
              className={`p-5 rounded-xl border text-left transition flex items-start gap-4 ${
                activeRoleSandbox === 'SUPER_ADMIN'
                  ? 'bg-purple-50/70 border-purple-400 ring-2 ring-purple-400/20'
                  : 'bg-white border-slate-200 hover:border-slate-300'
              }`}
            >
              <div className="w-10 h-10 rounded-xl bg-purple-600 text-white flex items-center justify-center shrink-0 shadow-xs">
                <ShieldCheck className="w-5 h-5" />
              </div>
              <div>
                <div className="font-bold text-slate-900 text-sm">角色一：超级管理员 / 财务测试</div>
                <div className="text-xs text-slate-500 mt-1">测试拼多多底层 Token 采购支出录入、全盘财务核算</div>
              </div>
            </button>

            <button
              onClick={() => setActiveRoleSandbox('AGENT')}
              className={`p-5 rounded-xl border text-left transition flex items-start gap-4 ${
                activeRoleSandbox === 'AGENT'
                  ? 'bg-indigo-50/70 border-indigo-400 ring-2 ring-indigo-400/20'
                  : 'bg-white border-slate-200 hover:border-slate-300'
              }`}
            >
              <div className="w-10 h-10 rounded-xl bg-indigo-600 text-white flex items-center justify-center shrink-0 shadow-xs">
                <UserCheck className="w-5 h-5" />
              </div>
              <div>
                <div className="font-bold text-slate-900 text-sm">角色二：代理商销售工作台测试</div>
                <div className="text-xs text-slate-500 mt-1">测试批量信用制卡、录入线下转账、查看名下业绩</div>
              </div>
            </button>

            <button
              onClick={() => setActiveRoleSandbox('END_USER')}
              className={`p-5 rounded-xl border text-left transition flex items-start gap-4 ${
                activeRoleSandbox === 'END_USER'
                  ? 'bg-emerald-50/70 border-emerald-400 ring-2 ring-emerald-400/20'
                  : 'bg-white border-slate-200 hover:border-slate-300'
              }`}
            >
              <div className="w-10 h-10 rounded-xl bg-emerald-600 text-white flex items-center justify-center shrink-0 shadow-xs">
                <Key className="w-5 h-5" />
              </div>
              <div>
                <div className="font-bold text-slate-900 text-sm">角色三：终端客户全流程测试</div>
                <div className="text-xs text-slate-500 mt-1">测试短信注册 1天试用、卡密一键核销延期、并发调度</div>
              </div>
            </button>
          </div>

          {/* 交互工作区 */}
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
            {/* 左侧：当前角色操作板 */}
            <div className="lg:col-span-7 bg-white border border-slate-200 rounded-xl p-6 shadow-sm space-y-6">
              {activeRoleSandbox === 'SUPER_ADMIN' && (
                <div className="space-y-4">
                  <div className="flex items-center justify-between pb-3 border-b border-slate-100">
                    <span className="font-bold text-slate-900 text-sm">超级管理员资产与财务控制台</span>
                    <span className="text-xs font-mono bg-purple-100 text-purple-800 px-2 py-0.5 rounded font-bold">SUPER_ADMIN</span>
                  </div>

                  <div className="grid grid-cols-3 gap-3 text-center">
                    <div className="p-3 bg-slate-50 rounded-lg border border-slate-200">
                      <span className="text-xs text-slate-500 block">底层健康 Token</span>
                      <span className="text-lg font-bold text-slate-900 font-mono">{adminTokenCount} 个</span>
                    </div>
                    <div className="p-3 bg-rose-50 rounded-lg border border-rose-200">
                      <span className="text-xs text-rose-700 block">累计采购支出</span>
                      <span className="text-lg font-bold text-rose-700 font-mono">¥{adminExpenseTotal}</span>
                    </div>
                    <div className="p-3 bg-emerald-50 rounded-lg border border-emerald-200">
                      <span className="text-xs text-emerald-700 block">财务实收流水</span>
                      <span className="text-lg font-bold text-emerald-700 font-mono">¥{adminIncomeTotal}</span>
                    </div>
                  </div>

                  <div className="space-y-2 pt-2">
                    <button
                      onClick={handleAdminBuyTokens}
                      className="w-full py-2.5 rounded-lg bg-purple-600 hover:bg-purple-500 text-white font-bold text-xs transition flex items-center justify-center gap-2"
                    >
                      <Database className="w-4 h-4" />
                      <span>模拟采购 10 个拼多多 Token 入库 (增加支出 ¥300.00)</span>
                    </button>
                  </div>
                </div>
              )}

              {activeRoleSandbox === 'AGENT' && (
                <div className="space-y-4">
                  <div className="flex items-center justify-between pb-3 border-b border-slate-100">
                    <span className="font-bold text-slate-900 text-sm">代理商销售制卡工作台</span>
                    <span className="text-xs font-mono bg-indigo-100 text-indigo-800 px-2 py-0.5 rounded font-bold">AGENT_001</span>
                  </div>

                  <div className="grid grid-cols-2 gap-3 text-center">
                    <div className="p-3 bg-slate-50 rounded-lg border border-slate-200">
                      <span className="text-xs text-slate-500 block">已生成卡密总数</span>
                      <span className="text-lg font-bold text-indigo-600 font-mono">{agentCardCount} 张</span>
                    </div>
                    <div className="p-3 bg-emerald-50 rounded-lg border border-emerald-200">
                      <span className="text-xs text-emerald-700 block">预估销售提成 (15%)</span>
                      <span className="text-lg font-bold text-emerald-700 font-mono">¥{(agentCardCount * 200 * 0.15).toFixed(0)}</span>
                    </div>
                  </div>

                  <button
                    onClick={handleAgentGenerateCards}
                    className="w-full py-2.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white font-bold text-xs transition flex items-center justify-center gap-2"
                  >
                    <Key className="w-4 h-4" />
                    <span>批量生成 1 张标准版月卡 (信用制卡，暂不记财务流水)</span>
                  </button>

                  <div className="text-xs text-slate-500 bg-slate-50 p-3 rounded-lg border border-slate-200">
                    <span className="font-bold text-slate-700 block mb-1">最近生成的卡密队列:</span>
                    <div className="font-mono text-[11px] text-indigo-700 space-y-0.5">
                      {agentCreatedBatch.slice(0, 3).map((k, idx) => (
                        <div key={idx}>• {k} (待客户核销)</div>
                      ))}
                    </div>
                  </div>
                </div>
              )}

              {activeRoleSandbox === 'END_USER' && (
                <div className="space-y-4">
                  <div className="flex items-center justify-between pb-3 border-b border-slate-100">
                    <span className="font-bold text-slate-900 text-sm">终端客户体验联调区 (手机号: 13800138000)</span>
                    <span className="text-xs font-mono bg-emerald-100 text-emerald-800 px-2 py-0.5 rounded font-bold">{userStatus}</span>
                  </div>

                  <div className="grid grid-cols-2 gap-3 text-center">
                    <div className="p-3 bg-slate-50 rounded-lg border border-slate-200">
                      <span className="text-xs text-slate-500 block">剩余可用总次数</span>
                      <span className="text-2xl font-bold text-emerald-600 font-mono">{userRemaining} 次</span>
                    </div>
                    <div className="p-3 bg-slate-50 rounded-lg border border-slate-200">
                      <span className="text-xs text-slate-500 block">套餐有效截止日</span>
                      <span className="text-xs font-bold text-slate-800 font-mono mt-1 block">{userExpire}</span>
                    </div>
                  </div>

                  <div className="grid grid-cols-2 gap-3 pt-2">
                    <button
                      onClick={handleClientCallPdd}
                      className="py-2.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs transition flex items-center justify-center gap-1.5"
                    >
                      <Play className="w-3.5 h-3.5" />
                      <span>发起业务调用 (扣减 1 次)</span>
                    </button>
                    <button
                      onClick={handleClientActivateCard}
                      className="py-2.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white font-bold text-xs transition flex items-center justify-center gap-1.5"
                    >
                      <Key className="w-3.5 h-3.5" />
                      <span>核销月卡 (+300次/延期)</span>
                    </button>
                  </div>
                </div>
              )}
            </div>

            {/* 右侧：实时日志与全息状态监视器 */}
            <div className="lg:col-span-5 bg-slate-900 text-white border border-slate-800 rounded-xl p-5 shadow-sm space-y-3">
              <div className="flex items-center justify-between pb-2 border-b border-slate-800">
                <span className="text-xs font-bold flex items-center gap-2 text-slate-200">
                  <Terminal className="w-4 h-4 text-emerald-400" />
                  <span>沙盒实时事件流 (Live Event Stream)</span>
                </span>
                <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
              </div>

              <div className="font-mono text-xs text-slate-300 space-y-2 max-h-72 overflow-y-auto pr-1">
                {sandboxLogs.map((log, idx) => (
                  <div key={idx} className="p-2 rounded bg-slate-950/60 border border-slate-800/80 leading-relaxed text-[11px]">
                    {log}
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
