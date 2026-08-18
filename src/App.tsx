import React, { useState } from 'react';
import { 
  BookOpen, 
  CreditCard, 
  DollarSign, 
  ShieldCheck, 
  Sparkles, 
  Layers,
  Database,
  Lock,
  Building2,
  Split,
  Server,
  CheckCircle2,
  Terminal,
  Code2
} from 'lucide-react';
import { DocumentViewer } from './components/DocumentViewer';
import { FinancialCenter } from './components/FinancialCenter';
import { CardKeyStudio } from './components/CardKeyStudio';
import { SecurityDemo } from './components/SecurityDemo';
import { RoleMatrixView } from './components/RoleMatrixView';
import { SpringBootProjectViewer } from './components/SpringBootProjectViewer';
import { TestWorkbench } from './components/TestWorkbench';
import { ClientSdkIntegrationDoc } from './components/ClientSdkIntegrationDoc';
import { 
  PackageTier, 
  UserAccount, 
  CardKeyEntity, 
  FinancialIncomeEntity, 
  CompanyExpenseEntity 
} from './types';

export const App: React.FC = () => {
  const [activeView, setActiveView] = useState<
    'DOCS' | 'ROLES' | 'SPRINGBOOT' | 'TESTING' | 'CLIENT_SDK' | 'FINANCE' | 'CARD_STUDIO' | 'SECURITY'
  >('DOCS');

  // 套餐列表
  const [packages] = useState<PackageTier[]>([
    {
      id: 1,
      name: '200元月卡标准版',
      price: 200,
      durationDays: 30,
      accountCountX: 1,
      callsPerAccountY: 200,
      totalCalls: 200,
      description: '分配1个账号，单账号200次，总计200次'
    },
    {
      id: 2,
      name: '200元月卡多账号防控版',
      price: 200,
      durationDays: 30,
      accountCountX: 4,
      callsPerAccountY: 250,
      totalCalls: 1000,
      description: '分配4个账号轮巡，单账号250次，总计1000次'
    },
    {
      id: 3,
      name: '500元季卡尊享版',
      price: 500,
      durationDays: 90,
      accountCountX: 10,
      callsPerAccountY: 300,
      totalCalls: 3000,
      description: '分配10个账号矩阵轮巡，总计3000次'
    }
  ]);

  // 模拟用户资产列表
  const [users, setUsers] = useState<UserAccount[]>([
    {
      id: 1,
      phone: '13800138000',
      registeredAt: '2026-07-01 10:20:00',
      status: 'ACTIVE',
      expireTime: '2026-11-15 23:59:59',
      totalSpentAmount: 800,
      totalCardsCount: 4,
      totalCallsLimit: 1000,
      usedCalls: 380,
      remainingCalls: 620
    },
    {
      id: 2,
      phone: '13911223344',
      registeredAt: '2026-08-10 14:00:00',
      status: 'TRIAL',
      expireTime: '2026-08-16 14:00:00',
      totalSpentAmount: 0,
      totalCardsCount: 0,
      totalCallsLimit: 50,
      usedCalls: 12,
      remainingCalls: 38
    }
  ]);

  // 表 1: 业务卡密凭证表 (pdk_card_key)
  const [cardKeys, setCardKeys] = useState<CardKeyEntity[]>([
    {
      id: 101,
      cardKey: 'PDK-8821-9920-1123',
      packageId: 2,
      packageName: '200元月卡多账号防控版',
      faceValue: 200,
      status: 'ACTIVATED',
      generatedByAdmin: 'admin_master',
      generatedAt: '2026-08-01 09:30:00',
      boundUserPhone: '13800138000',
      activatedAt: '2026-08-01 10:15:20'
    },
    {
      id: 102,
      cardKey: 'PDK-4412-8819-2231',
      packageId: 2,
      packageName: '200元月卡多账号防控版',
      faceValue: 200,
      status: 'ACTIVATED',
      generatedByAdmin: 'admin_master',
      generatedAt: '2026-08-05 11:20:00',
      boundUserPhone: '13800138000',
      activatedAt: '2026-08-05 11:45:10'
    },
    {
      id: 103,
      cardKey: 'PDK-7719-3301-4491',
      packageId: 1,
      packageName: '200元月卡标准版',
      faceValue: 200,
      status: 'UNUSED',
      generatedByAdmin: 'admin_master',
      generatedAt: '2026-08-15 08:00:00'
    },
    {
      id: 104,
      cardKey: 'PDK-9921-6620-8812',
      packageId: 3,
      packageName: '500元季卡尊享版',
      faceValue: 500,
      status: 'UNUSED',
      generatedByAdmin: 'admin_master',
      generatedAt: '2026-08-15 08:00:00'
    }
  ]);

  // 表 2: 独立的财务收入审计流水表 (pdk_financial_income)
  const [incomeRecords, setIncomeRecords] = useState<FinancialIncomeEntity[]>([
    {
      id: 1,
      incomeOrderNo: 'INC_20260801_0001',
      cardKeyId: 101,
      cardKey: 'PDK-8821-9920-1123',
      userPhone: '13800138000',
      packageId: 2,
      packageName: '200元月卡多账号防控版',
      faceValue: 200,
      amount: 200,
      discountAmount: 0,
      orderType: 'NORMAL_SALE',
      paymentChannel: 'BANK_TRANSFER',
      paymentTxnNo: '招商银行转账流水 #88192019',
      auditAdmin: 'finance_admin',
      activatedAt: '2026-08-01 10:15:20',
      auditRemark: '招商银行确认到账无误'
    },
    {
      id: 2,
      incomeOrderNo: 'INC_20260805_0002',
      cardKeyId: 102,
      cardKey: 'PDK-4412-8819-2231',
      userPhone: '13800138000',
      packageId: 2,
      packageName: '200元月卡多账号防控版',
      faceValue: 200,
      amount: 180,
      discountAmount: 20,
      orderType: 'DISCOUNT_SALE',
      paymentChannel: 'ALIPAY',
      paymentTxnNo: '支付宝对公订单 #2026080599182',
      auditAdmin: 'finance_admin',
      activatedAt: '2026-08-05 11:45:10',
      auditRemark: '老客户9折特惠已审核'
    },
    {
      id: 3,
      incomeOrderNo: 'INC_20260810_0003',
      cardKeyId: 105,
      cardKey: 'PDK-1102-9931-4421',
      userPhone: '13911223344',
      packageId: 1,
      packageName: '200元月卡标准版',
      faceValue: 200,
      amount: 0,
      discountAmount: 200,
      orderType: 'GIFT_FREE',
      paymentChannel: 'WECHAT_PAY',
      paymentTxnNo: 'GIFT_VIP_TEST_001',
      auditAdmin: 'super_admin',
      activatedAt: '2026-08-10 14:05:00',
      auditRemark: '大客户商务合作测试卡(免费赠送)'
    }
  ]);

  // 表 3: 独立的公司资产采购与支出表 (pdk_company_expense)
  const [expenseRecords, setExpenseRecords] = useState<CompanyExpenseEntity[]>([
    {
      id: 1,
      expenseNo: 'EXP_20260801_001',
      expenseType: 'ASSET_TOKEN_PURCHASE',
      amount: 3500,
      tokenCountAdded: 50,
      unitPrice: 70,
      supplierChannel: '官方合作分销渠道A',
      recordedByAdmin: 'finance_admin',
      expenseDate: '2026-08-01',
      remark: '批量补充采购50个高权重拼多多Token资产'
    },
    {
      id: 2,
      expenseNo: 'EXP_20260810_002',
      expenseType: 'SERVER_BANDWIDTH',
      amount: 800,
      tokenCountAdded: 0,
      unitPrice: 0,
      supplierChannel: '阿里云ECS',
      recordedByAdmin: 'tech_ops',
      expenseDate: '2026-08-10',
      remark: '8月份网关调度高防BGP带宽月度续费'
    }
  ]);

  // 批量制卡 (写 pdk_card_key)
  const handleGenerateCard = (packageId: number, count: number) => {
    const pkg = packages.find(p => p.id === packageId) || packages[0];
    const newCards: CardKeyEntity[] = [];
    for (let i = 0; i < count; i++) {
      const randomPart = Array.from({ length: 3 }, () => Math.floor(1000 + Math.random() * 9000)).join('-');
      newCards.push({
        id: Date.now() + i,
        cardKey: `PDK-${randomPart}`,
        packageId: pkg.id,
        packageName: pkg.name,
        faceValue: pkg.price,
        status: 'UNUSED',
        generatedByAdmin: 'admin_master',
        generatedAt: new Date().toISOString().replace('T', ' ').substring(0, 19)
      });
    }
    setCardKeys(prev => [...prev, ...newCards]);
  };

  // 客户端激活卡密 (执行数据库事务：写 pdk_card_key + 写 pdk_financial_income + 延期 pdk_user)
  const handleActivateCard = (
    cardKeyStr: string, 
    phone: string, 
    channel: 'BANK_TRANSFER' | 'ALIPAY' | 'WECHAT_PAY', 
    txnNoStr: string
  ) => {
    const card = cardKeys.find(c => c.cardKey === cardKeyStr);
    if (!card) {
      return { success: false, message: '【事务回滚】卡密不存在，请核对后重试！' };
    }
    if (card.status !== 'UNUSED') {
      return { success: false, message: '【事务回滚】该卡密已被核销使用或已作废！' };
    }

    const nowStr = new Date().toISOString().replace('T', ' ').substring(0, 19);

    // 1. 更新卡密凭证物理表 (pdk_card_key)
    setCardKeys(prev => prev.map(c => c.id === card.id ? {
      ...c,
      status: 'ACTIVATED',
      boundUserPhone: phone,
      activatedAt: nowStr
    } : c));

    // 2. 写入独立的财务收入审计流水表 (pdk_financial_income)
    const newIncome: FinancialIncomeEntity = {
      id: Date.now(),
      incomeOrderNo: `INC_${Date.now()}`,
      cardKeyId: card.id,
      cardKey: card.cardKey,
      userPhone: phone,
      packageId: card.packageId,
      packageName: card.packageName,
      amount: card.faceValue,
      paymentChannel: channel,
      paymentTxnNo: txnNoStr,
      auditAdmin: 'auto_audit_tx',
      activatedAt: nowStr,
      auditRemark: '客户端事务原子核销入库'
    };
    setIncomeRecords(prev => [newIncome, ...prev]);

    // 3. 更新用户表 (pdk_user)
    setUsers(prev => prev.map(u => {
      if (u.phone === phone) {
        const pkg = packages.find(p => p.id === card.packageId) || packages[0];
        return {
          ...u,
          status: 'ACTIVE',
          totalSpentAmount: u.totalSpentAmount + card.faceValue,
          totalCardsCount: u.totalCardsCount + 1,
          totalCallsLimit: u.totalCallsLimit + pkg.totalCalls,
          remainingCalls: u.remainingCalls + pkg.totalCalls
        };
      }
      return u;
    }));

    return { 
      success: true, 
      message: `【事务原子提交成功】卡密已核销！已向 pdk_financial_income 财务表独立写入收款记录 ¥${card.faceValue}，到期日已顺延！` 
    };
  };

  // 新增资产支出
  const handleAddExpense = (expense: Omit<CompanyExpenseEntity, 'id'>) => {
    setExpenseRecords(prev => [{ id: Date.now(), ...expense }, ...prev]);
  };

  // 模拟调度
  const handleSimulateDispatch = (phone: string) => {
    const user = users.find(u => u.phone === phone);
    if (!user) return { success: false, message: '用户不存在' };
    if (user.remainingCalls <= 0) {
      return { success: false, message: '【拦截熔断】用户剩余配额已耗尽，已触发客户端强制退出保护！' };
    }

    const accounts = ['PDD_TOKEN_001', 'PDD_TOKEN_002', 'PDD_TOKEN_003', 'PDD_TOKEN_004'];
    const chosen = accounts[user.usedCalls % accounts.length];

    setUsers(prev => prev.map(u => u.phone === phone ? {
      ...u,
      usedCalls: u.usedCalls + 1,
      remainingCalls: u.remainingCalls - 1
    } : u));

    return {
      success: true,
      message: `【网关调度成功】成功轮巡命中公共账号: [${chosen}]，扣减1次配额 (剩余: ${user.remainingCalls - 1}次)`,
      accountUsed: chosen
    };
  };

  return (
    <div className="min-h-screen bg-slate-100 text-slate-900 font-sans flex flex-col">
      {/* 顶部主导航栏 */}
      <header className="bg-slate-900 text-white border-b border-slate-800 sticky top-0 z-40 shadow-md">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-indigo-500 to-emerald-500 flex items-center justify-center font-black text-white text-lg shadow-inner">
              P
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="font-extrabold tracking-tight text-white text-base">PDK (拼多客) 服务端中台</span>
                <span className="text-[10px] bg-emerald-500/20 text-emerald-300 px-2 py-0.5 rounded font-mono border border-emerald-500/30">
                  v1.2 独立物理表版
                </span>
              </div>
              <p className="text-[11px] text-slate-400">
                卡密业务凭证表 (pdk_card_key) 与财务收入流水表 (pdk_financial_income) 彻底解耦
              </p>
            </div>
          </div>

          {/* 顶栏 Tab 导航 */}
          <nav className="flex items-center gap-1 bg-slate-800/80 p-1 rounded-xl border border-slate-700 overflow-x-auto">
            <button
              onClick={() => setActiveView('DOCS')}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1.5 transition whitespace-nowrap ${
                activeView === 'DOCS'
                  ? 'bg-indigo-600 text-white shadow-xs'
                  : 'text-slate-300 hover:text-white hover:bg-slate-700/50'
              }`}
            >
              <BookOpen className="w-3.5 h-3.5" />
              <span>架构与PRD文档</span>
            </button>

            <button
              onClick={() => setActiveView('SPRINGBOOT')}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1.5 transition whitespace-nowrap ${
                activeView === 'SPRINGBOOT'
                  ? 'bg-emerald-600 text-white shadow-xs'
                  : 'text-slate-300 hover:text-white hover:bg-slate-700/50'
              }`}
            >
              <Server className="w-3.5 h-3.5" />
              <span>SpringBoot3+Vue3源码 ★</span>
            </button>

            <button
              onClick={() => setActiveView('TESTING')}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1.5 transition whitespace-nowrap ${
                activeView === 'TESTING'
                  ? 'bg-blue-600 text-white shadow-xs'
                  : 'text-slate-300 hover:text-white hover:bg-slate-700/50'
              }`}
            >
              <CheckCircle2 className="w-3.5 h-3.5" />
              <span>单元与人工测试工作台 ★</span>
            </button>

            <button
              onClick={() => setActiveView('CLIENT_SDK')}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1.5 transition whitespace-nowrap ${
                activeView === 'CLIENT_SDK'
                  ? 'bg-indigo-500 text-white shadow-xs'
                  : 'text-slate-300 hover:text-white hover:bg-slate-700/50'
              }`}
            >
              <Terminal className="w-3.5 h-3.5" />
              <span>客户端对接SDK指南 ★</span>
            </button>

            <button
              onClick={() => setActiveView('ROLES')}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1.5 transition whitespace-nowrap ${
                activeView === 'ROLES'
                  ? 'bg-purple-600 text-white shadow-xs'
                  : 'text-slate-300 hover:text-white hover:bg-slate-700/50'
              }`}
            >
              <Layers className="w-3.5 h-3.5" />
              <span>三角色权限矩阵</span>
            </button>

            <button
              onClick={() => setActiveView('FINANCE')}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1.5 transition whitespace-nowrap ${
                activeView === 'FINANCE'
                  ? 'bg-emerald-700 text-white shadow-xs'
                  : 'text-slate-300 hover:text-white hover:bg-slate-700/50'
              }`}
            >
              <DollarSign className="w-3.5 h-3.5" />
              <span>财务双向审计中心</span>
            </button>

            <button
              onClick={() => setActiveView('CARD_STUDIO')}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1.5 transition whitespace-nowrap ${
                activeView === 'CARD_STUDIO'
                  ? 'bg-blue-700 text-white shadow-xs'
                  : 'text-slate-300 hover:text-white hover:bg-slate-700/50'
              }`}
            >
              <CreditCard className="w-3.5 h-3.5" />
              <span>卡密凭证沙盒</span>
            </button>

            <button
              onClick={() => setActiveView('SECURITY')}
              className={`px-3 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1.5 transition whitespace-nowrap ${
                activeView === 'SECURITY'
                  ? 'bg-amber-600 text-white shadow-xs'
                  : 'text-slate-300 hover:text-white hover:bg-slate-700/50'
              }`}
            >
              <ShieldCheck className="w-3.5 h-3.5" />
              <span>通信加密实验室</span>
            </button>
          </nav>
        </div>
      </header>

      {/* 主体工作区 */}
      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-8">
        {activeView === 'DOCS' && <DocumentViewer />}

        {activeView === 'SPRINGBOOT' && <SpringBootProjectViewer />}

        {activeView === 'TESTING' && <TestWorkbench />}

        {activeView === 'CLIENT_SDK' && <ClientSdkIntegrationDoc />}

        {activeView === 'ROLES' && <RoleMatrixView />}

        {activeView === 'FINANCE' && (
          <FinancialCenter
            incomeRecords={incomeRecords}
            expenseRecords={expenseRecords}
            cardKeys={cardKeys}
            users={users}
            onAddExpense={handleAddExpense}
          />
        )}

        {activeView === 'CARD_STUDIO' && (
          <CardKeyStudio
            cardKeys={cardKeys}
            packages={packages}
            currentUser={users[0]}
            onGenerateCard={handleGenerateCard}
            onActivateCard={handleActivateCard}
            onSimulateDispatch={handleSimulateDispatch}
          />
        )}

        {activeView === 'SECURITY' && <SecurityDemo />}
      </main>

      {/* 页脚声明 */}
      <footer className="bg-white border-t border-slate-200 py-4 text-center text-xs text-slate-500 font-mono">
        PDK (拼多客) 高并发资产调度与财务双向中台 · Spring Boot 3 + MySQL 8 物理拆表架构已生效
      </footer>
    </div>
  );
};

export default App;
