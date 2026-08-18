import React, { useState } from 'react';
import { 
  DollarSign, 
  CreditCard, 
  TrendingUp, 
  Calendar, 
  UserCheck, 
  Search, 
  PlusCircle, 
  FileText, 
  CheckCircle2, 
  AlertCircle, 
  Building2, 
  ArrowUpRight, 
  ArrowDownRight, 
  ShoppingBag,
  Clock,
  Filter
} from 'lucide-react';
import { FinancialIncomeEntity, CompanyExpenseEntity, CardKeyEntity, UserAccount } from '../types';

interface FinancialCenterProps {
  incomeRecords: FinancialIncomeEntity[];
  expenseRecords: CompanyExpenseEntity[];
  cardKeys: CardKeyEntity[];
  users: UserAccount[];
  onAddExpense: (expense: Omit<CompanyExpenseEntity, 'id'>) => void;
}

export const FinancialCenter: React.FC<FinancialCenterProps> = ({
  incomeRecords,
  expenseRecords,
  cardKeys,
  users,
  onAddExpense
}) => {
  const [activeTab, setActiveTab] = useState<'OVERVIEW' | 'INCOME_LIST' | 'EXPENSE_LIST' | 'USER_LTV'>('OVERVIEW');
  const [periodFilter, setPeriodFilter] = useState<'YEAR' | 'QUARTER' | 'MONTH'>('MONTH');
  
  // 单用户 LTV 查询状态
  const [searchPhone, setSearchPhone] = useState('13800138000');
  const [selectedUser, setSelectedUser] = useState<UserAccount | null>(() => users.find(u => u.phone === '13800138000') || users[0]);

  // 新增支出表单状态
  const [showAddExpenseModal, setShowAddExpenseModal] = useState(false);
  const [newExpense, setNewExpense] = useState({
    expenseNo: `EXP_${Date.now()}`,
    expenseType: 'ASSET_TOKEN_PURCHASE' as CompanyExpenseEntity['expenseType'],
    amount: 3500,
    tokenCountAdded: 50,
    unitPrice: 70,
    supplierChannel: '官方合作分销渠道A',
    recordedByAdmin: 'finance_admin',
    expenseDate: new Date().toISOString().split('T')[0],
    remark: '批量补充采购50个高权重拼多多Token资产'
  });

  // 汇总计算
  const totalIncome = incomeRecords.reduce((sum, item) => sum + item.amount, 0);
  const totalExpense = expenseRecords.reduce((sum, item) => sum + item.amount, 0);
  const netProfit = totalIncome - totalExpense;
  const activatedCardsCount = incomeRecords.length;
  const totalIssuedCards = cardKeys.length;

  // 处理新增支出
  const handleCreateExpense = (e: React.FormEvent) => {
    e.preventDefault();
    onAddExpense({
      ...newExpense,
      expenseNo: `EXP_${Date.now()}`
    });
    setShowAddExpenseModal(false);
  };

  // 处理用户搜索
  const handleSearchUser = () => {
    const found = users.find(u => u.phone.includes(searchPhone.trim()));
    if (found) {
      setSelectedUser(found);
    } else {
      alert('未找到该手机号的用户记录！');
    }
  };

  // 该用户的专属收入流水
  const userIncomeList = selectedUser 
    ? incomeRecords.filter(item => item.userPhone === selectedUser.phone) 
    : [];

  return (
    <div className="space-y-6">
      {/* 顶部导航与核心指标看板 */}
      <div className="bg-white border border-slate-200 rounded-xl p-6 shadow-xs">
        <div className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-100 pb-5 mb-6">
          <div>
            <div className="flex items-center gap-2">
              <Building2 className="w-5 h-5 text-indigo-600" />
              <h2 className="text-xl font-bold text-slate-900">财务双向独立审计与对账中台</h2>
            </div>
            <p className="text-xs text-slate-500 mt-1">
              业务卡密物理表 (pdk_card_key) 与财务收入流水物理表 (pdk_financial_income) 彻底解耦 · 包含资产采购支出与单用户画像
            </p>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={() => setShowAddExpenseModal(true)}
              className="inline-flex items-center gap-1.5 px-3 py-2 bg-rose-600 hover:bg-rose-700 text-white rounded-lg text-xs font-semibold shadow-xs transition"
            >
              <PlusCircle className="w-4 h-4" />
              <span>录入资产采购支出</span>
            </button>
          </div>
        </div>

        {/* 4 大核心指标 */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <div className="bg-emerald-50/70 border border-emerald-200 rounded-xl p-4">
            <div className="flex items-center justify-between text-xs text-emerald-800 font-semibold mb-1">
              <span>【收入端】累计实收金额</span>
              <ArrowUpRight className="w-4 h-4 text-emerald-600" />
            </div>
            <div className="text-2xl font-extrabold text-emerald-700 font-mono">
              ¥{totalIncome.toLocaleString('zh-CN', { minimumFractionDigits: 2 })}
            </div>
            <div className="text-[11px] text-emerald-600/80 mt-1">
              来自 <code>pdk_financial_income</code> 独立流水表 ({activatedCardsCount} 笔核销)
            </div>
          </div>

          <div className="bg-rose-50/70 border border-rose-200 rounded-xl p-4">
            <div className="flex items-center justify-between text-xs text-rose-800 font-semibold mb-1">
              <span>【支出端】资产采购与运营支出</span>
              <ArrowDownRight className="w-4 h-4 text-rose-600" />
            </div>
            <div className="text-2xl font-extrabold text-rose-700 font-mono">
              ¥{totalExpense.toLocaleString('zh-CN', { minimumFractionDigits: 2 })}
            </div>
            <div className="text-[11px] text-rose-600/80 mt-1">
              来自 <code>pdk_company_expense</code> 资产采购表 ({expenseRecords.length} 笔支出)
            </div>
          </div>

          <div className="bg-indigo-50/70 border border-indigo-200 rounded-xl p-4">
            <div className="flex items-center justify-between text-xs text-indigo-800 font-semibold mb-1">
              <span>【毛利核算】期间真实净利润</span>
              <TrendingUp className="w-4 h-4 text-indigo-600" />
            </div>
            <div className="text-2xl font-extrabold text-indigo-900 font-mono">
              ¥{netProfit.toLocaleString('zh-CN', { minimumFractionDigits: 2 })}
            </div>
            <div className="text-[11px] text-indigo-600/80 mt-1">
              实收收入 - 资产采购支出 (利润率: {totalIncome > 0 ? ((netProfit / totalIncome) * 100).toFixed(1) : 0}%)
            </div>
          </div>

          <div className="bg-slate-50 border border-slate-200 rounded-xl p-4">
            <div className="flex items-center justify-between text-xs text-slate-700 font-semibold mb-1">
              <span>【卡密池】总发卡 / 激活率</span>
              <CreditCard className="w-4 h-4 text-slate-500" />
            </div>
            <div className="text-2xl font-extrabold text-slate-800 font-mono">
              {activatedCardsCount} / {totalIssuedCards}
            </div>
            <div className="text-[11px] text-slate-500 mt-1">
              核销转化率: {totalIssuedCards > 0 ? ((activatedCardsCount / totalIssuedCards) * 100).toFixed(1) : 0}%
            </div>
          </div>
        </div>
      </div>

      {/* 模块选项卡导航 */}
      <div className="flex border-b border-slate-200 gap-2">
        <button
          onClick={() => setActiveTab('OVERVIEW')}
          className={`px-4 py-2.5 text-xs font-semibold rounded-t-lg transition flex items-center gap-1.5 ${
            activeTab === 'OVERVIEW'
              ? 'bg-white border-t-2 border-indigo-600 text-indigo-600 border-x border-slate-200'
              : 'text-slate-500 hover:text-slate-700'
          }`}
        >
          <Calendar className="w-4 h-4" />
          <span>年 / 季 / 月 周期对账汇总报表</span>
        </button>

        <button
          onClick={() => setActiveTab('INCOME_LIST')}
          className={`px-4 py-2.5 text-xs font-semibold rounded-t-lg transition flex items-center gap-1.5 ${
            activeTab === 'INCOME_LIST'
              ? 'bg-white border-t-2 border-emerald-600 text-emerald-600 border-x border-slate-200'
              : 'text-slate-500 hover:text-slate-700'
          }`}
        >
          <DollarSign className="w-4 h-4" />
          <span>财务收入审计流水表 (pdk_financial_income)</span>
        </button>

        <button
          onClick={() => setActiveTab('EXPENSE_LIST')}
          className={`px-4 py-2.5 text-xs font-semibold rounded-t-lg transition flex items-center gap-1.5 ${
            activeTab === 'EXPENSE_LIST'
              ? 'bg-white border-t-2 border-rose-600 text-rose-600 border-x border-slate-200'
              : 'text-slate-500 hover:text-slate-700'
          }`}
        >
          <ShoppingBag className="w-4 h-4" />
          <span>公司资产采购与支出表 (pdk_company_expense)</span>
        </button>

        <button
          onClick={() => setActiveTab('USER_LTV')}
          className={`px-4 py-2.5 text-xs font-semibold rounded-t-lg transition flex items-center gap-1.5 ${
            activeTab === 'USER_LTV'
              ? 'bg-white border-t-2 border-purple-600 text-purple-600 border-x border-slate-200'
              : 'text-slate-500 hover:text-slate-700'
          }`}
        >
          <UserCheck className="w-4 h-4" />
          <span>单用户 (手机号) 终身价值与穿透对账 (LTV)</span>
        </button>
      </div>

      {/* 选项卡内容 1: 周期对账汇总 */}
      {activeTab === 'OVERVIEW' && (
        <div className="bg-white border border-slate-200 rounded-xl p-6 space-y-6">
          <div className="flex items-center justify-between">
            <h3 className="font-bold text-slate-900 text-sm flex items-center gap-2">
              <Calendar className="w-4 h-4 text-indigo-600" />
              <span>多周期综合收益核算大表</span>
            </h3>
            <div className="flex bg-slate-100 p-0.5 rounded-lg text-xs font-medium">
              <button 
                onClick={() => setPeriodFilter('MONTH')} 
                className={`px-3 py-1 rounded-md transition ${periodFilter === 'MONTH' ? 'bg-white text-indigo-600 shadow-xs font-bold' : 'text-slate-600'}`}
              >
                自然月度
              </button>
              <button 
                onClick={() => setPeriodFilter('QUARTER')} 
                className={`px-3 py-1 rounded-md transition ${periodFilter === 'QUARTER' ? 'bg-white text-indigo-600 shadow-xs font-bold' : 'text-slate-600'}`}
              >
                季度汇总 (Q1-Q4)
              </button>
              <button 
                onClick={() => setPeriodFilter('YEAR')} 
                className={`px-3 py-1 rounded-md transition ${periodFilter === 'YEAR' ? 'bg-white text-indigo-600 shadow-xs font-bold' : 'text-slate-600'}`}
              >
                年度总决算
              </button>
            </div>
          </div>

          <div className="overflow-x-auto border border-slate-200 rounded-xl">
            <table className="w-full text-left text-xs">
              <thead className="bg-slate-50 text-slate-600 border-b border-slate-200">
                <tr>
                  <th className="p-3">统计周期</th>
                  <th className="p-3">激活核销卡密数</th>
                  <th className="p-3">实收总收入 (Income表)</th>
                  <th className="p-3">资产采购支出 (Expense表)</th>
                  <th className="p-3">净利润额</th>
                  <th className="p-3">利润率</th>
                  <th className="p-3">资产采购与运营详情说明</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 font-mono text-slate-700">
                <tr className="hover:bg-slate-50/80">
                  <td className="p-3 font-bold text-slate-900">2026-08 (当月)</td>
                  <td className="p-3">142 张</td>
                  <td className="p-3 font-bold text-emerald-600">¥28,400.00</td>
                  <td className="p-3 font-bold text-rose-600">¥3,500.00</td>
                  <td className="p-3 font-bold text-indigo-600">¥24,900.00</td>
                  <td className="p-3"><span className="px-2 py-0.5 bg-emerald-100 text-emerald-800 rounded font-sans">87.7%</span></td>
                  <td className="p-3 font-sans text-slate-600">批量补充采购 50 个高权重 Token (单价 70元)</td>
                </tr>
                <tr className="hover:bg-slate-50/80">
                  <td className="p-3 font-bold text-slate-900">2026-07 (上月)</td>
                  <td className="p-3">118 张</td>
                  <td className="p-3 font-bold text-emerald-600">¥23,600.00</td>
                  <td className="p-3 font-bold text-rose-600">¥2,800.00</td>
                  <td className="p-3 font-bold text-indigo-600">¥20,800.00</td>
                  <td className="p-3"><span className="px-2 py-0.5 bg-emerald-100 text-emerald-800 rounded font-sans">88.1%</span></td>
                  <td className="p-3 font-sans text-slate-600">采购 40 个 Token 资产 + 阿里云 ECS 节点续费</td>
                </tr>
                <tr className="hover:bg-slate-50/80">
                  <td className="p-3 font-bold text-slate-900">2026-06</td>
                  <td className="p-3">95 张</td>
                  <td className="p-3 font-bold text-emerald-600">¥19,000.00</td>
                  <td className="p-3 font-bold text-rose-600">¥2,100.00</td>
                  <td className="p-3 font-bold text-indigo-600">¥16,900.00</td>
                  <td className="p-3"><span className="px-2 py-0.5 bg-emerald-100 text-emerald-800 rounded font-sans">88.9%</span></td>
                  <td className="p-3 font-sans text-slate-600">采购 30 个 Token 资产</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* 选项卡内容 2: 财务收入流水表 */}
      {activeTab === 'INCOME_LIST' && (
        <div className="bg-white border border-slate-200 rounded-xl p-6 space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <h3 className="font-bold text-slate-900 text-sm flex items-center gap-2">
                <DollarSign className="w-4 h-4 text-emerald-600" />
                <span>财务收入审计流水明细 (表名: pdk_financial_income)</span>
              </h3>
              <p className="text-xs text-slate-500 mt-0.5">
                每当卡密被成功激活核销时，由数据库事务自动写入一条收入凭据流水
              </p>
            </div>
            <span className="text-xs bg-emerald-50 text-emerald-700 px-3 py-1 rounded font-mono font-bold">
              共计 {incomeRecords.length} 笔收款记录
            </span>
          </div>

          <div className="overflow-x-auto border border-slate-200 rounded-xl">
            <table className="w-full text-left text-xs">
              <thead className="bg-slate-50 text-slate-600 border-b border-slate-200">
                <tr>
                  <th className="p-3">财务收入单号</th>
                  <th className="p-3">核销卡密串</th>
                  <th className="p-3">客户手机号</th>
                  <th className="p-3">购买套餐</th>
                  <th className="p-3">订单类型</th>
                  <th className="p-3">面额 / 优惠</th>
                  <th className="p-3">实收金额</th>
                  <th className="p-3">线下打款渠道</th>
                  <th className="p-3">转账流水凭证号</th>
                  <th className="p-3">经办审核员</th>
                  <th className="p-3">到账核销时间</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 font-mono text-slate-700">
                {incomeRecords.map(item => (
                  <tr key={item.id} className="hover:bg-slate-50/80">
                    <td className="p-3 font-semibold text-slate-900">{item.incomeOrderNo}</td>
                    <td className="p-3 text-blue-600 font-semibold">{item.cardKey}</td>
                    <td className="p-3 font-bold text-slate-800">{item.userPhone}</td>
                    <td className="p-3 font-sans">{item.packageName}</td>
                    <td className="p-3 font-sans">
                      <span className={`px-2 py-0.5 rounded text-[11px] font-semibold ${
                        item.orderType === 'GIFT_FREE' 
                          ? 'bg-amber-100 text-amber-800' 
                          : item.orderType === 'DISCOUNT_SALE' 
                          ? 'bg-purple-100 text-purple-800' 
                          : 'bg-emerald-100 text-emerald-800'
                      }`}>
                        {item.orderType === 'GIFT_FREE' ? '商务赠送(0元)' : item.orderType === 'DISCOUNT_SALE' ? '折扣优惠' : '正价销售'}
                      </span>
                    </td>
                    <td className="p-3 text-slate-500 font-sans">
                      <div>面额: ¥{(item.faceValue || item.amount).toFixed(2)}</div>
                      {(item.discountAmount && item.discountAmount > 0) ? (
                        <div className="text-rose-500 text-[10px]">优惠: -¥{item.discountAmount.toFixed(2)}</div>
                      ) : null}
                    </td>
                    <td className="p-3 font-bold text-emerald-600">¥{item.amount.toFixed(2)}</td>
                    <td className="p-3 font-sans">
                      <span className="px-2 py-0.5 bg-slate-100 text-slate-700 rounded text-[11px]">
                        {item.paymentChannel === 'BANK_TRANSFER' ? '招商银行转账' : item.paymentChannel === 'ALIPAY' ? '企业支付宝' : '微信企业支付'}
                      </span>
                    </td>
                    <td className="p-3 text-slate-500">{item.paymentTxnNo || 'TXN_OFFLINE_AUTO'}</td>
                    <td className="p-3 font-sans text-slate-600">{item.auditAdmin}</td>
                    <td className="p-3 text-slate-500">{item.activatedAt}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* 选项卡内容 3: 公司资产采购与支出表 */}
      {activeTab === 'EXPENSE_LIST' && (
        <div className="bg-white border border-slate-200 rounded-xl p-6 space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <h3 className="font-bold text-slate-900 text-sm flex items-center gap-2">
                <ShoppingBag className="w-4 h-4 text-rose-600" />
                <span>公司资产采购与支出流水 (表名: pdk_company_expense)</span>
              </h3>
              <p className="text-xs text-slate-500 mt-0.5">
                记录公司购买和补充拼多多公共账号/Token资产的每一笔一次性支出与服务器成本
              </p>
            </div>
            <button
              onClick={() => setShowAddExpenseModal(true)}
              className="inline-flex items-center gap-1 px-3 py-1.5 bg-rose-600 hover:bg-rose-700 text-white rounded-lg text-xs font-semibold transition"
            >
              <PlusCircle className="w-3.5 h-3.5" />
              <span>记一笔支出</span>
            </button>
          </div>

          <div className="overflow-x-auto border border-slate-200 rounded-xl">
            <table className="w-full text-left text-xs">
              <thead className="bg-slate-50 text-slate-600 border-b border-slate-200">
                <tr>
                  <th className="p-3">支出流水单号</th>
                  <th className="p-3">支出类型</th>
                  <th className="p-3">支出金额</th>
                  <th className="p-3">补充Token数</th>
                  <th className="p-3">采购单价</th>
                  <th className="p-3">渠道来源</th>
                  <th className="p-3">经办管理员</th>
                  <th className="p-3">记账日期</th>
                  <th className="p-3">详细备注</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 font-mono text-slate-700">
                {expenseRecords.map(item => (
                  <tr key={item.id} className="hover:bg-slate-50/80">
                    <td className="p-3 font-semibold text-slate-900">{item.expenseNo}</td>
                    <td className="p-3 font-sans">
                      <span className="px-2 py-0.5 bg-rose-100 text-rose-800 rounded text-[11px] font-medium">
                        {item.expenseType === 'ASSET_TOKEN_PURCHASE' ? 'Token账号批量采购' : item.expenseType === 'SERVER_BANDWIDTH' ? '服务器与带宽续费' : '日常运维支出'}
                      </span>
                    </td>
                    <td className="p-3 font-bold text-rose-600">¥{item.amount.toFixed(2)}</td>
                    <td className="p-3 font-bold text-slate-800">{item.tokenCountAdded > 0 ? `+${item.tokenCountAdded} 个` : '-'}</td>
                    <td className="p-3 text-slate-600">{item.unitPrice > 0 ? `¥${item.unitPrice}/个` : '-'}</td>
                    <td className="p-3 font-sans text-slate-600">{item.supplierChannel}</td>
                    <td className="p-3 font-sans text-slate-600">{item.recordedByAdmin}</td>
                    <td className="p-3 text-slate-500">{item.expenseDate}</td>
                    <td className="p-3 font-sans text-slate-600 max-w-xs truncate">{item.remark}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* 选项卡内容 4: 单用户 LTV 穿透分析 */}
      {activeTab === 'USER_LTV' && (
        <div className="bg-white border border-slate-200 rounded-xl p-6 space-y-6">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div>
              <h3 className="font-bold text-slate-900 text-sm flex items-center gap-2">
                <UserCheck className="w-4 h-4 text-purple-600" />
                <span>单用户全生命周期 (LTV) 财务穿透审计</span>
              </h3>
              <p className="text-xs text-slate-500 mt-0.5">
                输入指定客户手机号，实时穿透检索该用户历史所有开卡次数、累计打款金额与复购画像
              </p>
            </div>

            {/* 手机号搜索框 */}
            <div className="flex items-center gap-2">
              <div className="relative">
                <input
                  type="text"
                  value={searchPhone}
                  onChange={(e) => setSearchPhone(e.target.value)}
                  placeholder="输入客户手机号 (如 13800138000)"
                  className="px-3 py-1.5 pl-8 border border-slate-300 rounded-lg text-xs font-mono w-60 focus:outline-hidden focus:border-purple-500"
                />
                <Search className="w-3.5 h-3.5 text-slate-400 absolute left-2.5 top-2.5" />
              </div>
              <button
                onClick={handleSearchUser}
                className="px-3 py-1.5 bg-purple-600 hover:bg-purple-700 text-white rounded-lg text-xs font-semibold transition"
              >
                精准穿透查询
              </button>
            </div>
          </div>

          {selectedUser && (
            <div className="space-y-6">
              {/* 用户核心财务资产卡片 */}
              <div className="bg-purple-50/60 border border-purple-200 rounded-xl p-5">
                <div className="flex flex-wrap items-center justify-between gap-4 border-b border-purple-200/60 pb-4 mb-4">
                  <div className="flex items-center gap-3">
                    <div className="w-10 h-10 rounded-full bg-purple-600 text-white font-bold flex items-center justify-center text-sm font-mono">
                      {selectedUser.phone.slice(-4)}
                    </div>
                    <div>
                      <div className="font-bold text-slate-900 font-mono text-base flex items-center gap-2">
                        <span>{selectedUser.phone}</span>
                        <span className="text-[11px] px-2 py-0.5 rounded bg-purple-200 text-purple-900 font-sans font-medium">
                          公司核心客户资产
                        </span>
                      </div>
                      <div className="text-xs text-slate-500 mt-0.5">
                        注册时间: {selectedUser.registeredAt} · 当前状态: <span className="font-semibold text-emerald-700">{selectedUser.status}</span>
                      </div>
                    </div>
                  </div>

                  <div className="flex items-center gap-6">
                    <div className="text-right">
                      <div className="text-xs text-slate-500">累计付费总额 (LTV)</div>
                      <div className="text-xl font-extrabold text-purple-900 font-mono">
                        ¥{selectedUser.totalSpentAmount.toFixed(2)}
                      </div>
                    </div>
                    <div className="text-right border-l border-purple-200 pl-6">
                      <div className="text-xs text-slate-500">累计激活卡密数</div>
                      <div className="text-xl font-extrabold text-slate-800 font-mono">
                        {selectedUser.totalCardsCount} 张
                      </div>
                    </div>
                    <div className="text-right border-l border-purple-200 pl-6">
                      <div className="text-xs text-slate-500">当前套餐到期日</div>
                      <div className="text-sm font-bold text-slate-800 font-mono mt-1">
                        {selectedUser.expireTime}
                      </div>
                    </div>
                  </div>
                </div>

                <div className="text-xs text-purple-900 flex items-center gap-2">
                  <CheckCircle2 className="w-4 h-4 text-purple-600" />
                  <span>
                    该客户累计贡献营收 <strong>¥{selectedUser.totalSpentAmount.toFixed(2)}</strong>，剩余总调用配额: <strong>{selectedUser.remainingCalls} 次</strong>
                  </span>
                </div>
              </div>

              {/* 该用户的历史打款对账流水明细 */}
              <div className="space-y-3">
                <h4 className="font-bold text-slate-900 text-xs flex items-center gap-1.5">
                  <FileText className="w-4 h-4 text-slate-600" />
                  <span>该客户历史充值与打款流水 (数据源: pdk_financial_income)</span>
                </h4>

                <div className="overflow-x-auto border border-slate-200 rounded-xl">
                  <table className="w-full text-left text-xs">
                    <thead className="bg-slate-50 text-slate-600 border-b border-slate-200">
                      <tr>
                        <th className="p-3">收入单号</th>
                        <th className="p-3">核销卡密串</th>
                        <th className="p-3">套餐名称</th>
                        <th className="p-3">付款金额</th>
                        <th className="p-3">收款渠道</th>
                        <th className="p-3">银行/转账流水单号</th>
                        <th className="p-3">核销到账时间</th>
                        <th className="p-3">经办审核员</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100 font-mono text-slate-700">
                      {userIncomeList.length > 0 ? (
                        userIncomeList.map(item => (
                          <tr key={item.id} className="hover:bg-slate-50/80">
                            <td className="p-3 font-semibold text-slate-900">{item.incomeOrderNo}</td>
                            <td className="p-3 text-blue-600 font-semibold">{item.cardKey}</td>
                            <td className="p-3 font-sans">{item.packageName}</td>
                            <td className="p-3 font-bold text-emerald-600">¥{item.amount.toFixed(2)}</td>
                            <td className="p-3 font-sans">
                              {item.paymentChannel === 'BANK_TRANSFER' ? '招商银行转账' : item.paymentChannel === 'ALIPAY' ? '企业支付宝' : '微信企业支付'}
                            </td>
                            <td className="p-3 text-slate-500">{item.paymentTxnNo || 'TXN_OFFLINE_AUTO'}</td>
                            <td className="p-3 text-slate-500">{item.activatedAt}</td>
                            <td className="p-3 font-sans text-slate-600">{item.auditAdmin}</td>
                          </tr>
                        ))
                      ) : (
                        <tr>
                          <td colSpan={8} className="p-6 text-center text-slate-400 font-sans">
                            该客户暂未核销付费卡密 (当前处于试用状态)
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}
        </div>
      )}

      {/* 录入支出弹窗 */}
      {showAddExpenseModal && (
        <div className="fixed inset-0 bg-black/50 backdrop-blur-xs flex items-center justify-center p-4 z-50">
          <div className="bg-white rounded-2xl p-6 max-w-lg w-full shadow-2xl border border-slate-200">
            <div className="flex items-center justify-between border-b border-slate-100 pb-3 mb-4">
              <h3 className="font-bold text-slate-900 text-sm flex items-center gap-2">
                <ShoppingBag className="w-4 h-4 text-rose-600" />
                <span>录入公司资产采购/补充支出 (写入 pdk_company_expense)</span>
              </h3>
              <button
                onClick={() => setShowAddExpenseModal(false)}
                className="text-slate-400 hover:text-slate-600 text-lg leading-none"
              >
                &times;
              </button>
            </div>

            <form onSubmit={handleCreateExpense} className="space-y-3 text-xs">
              <div>
                <label className="block text-slate-700 font-semibold mb-1">支出类型</label>
                <select
                  value={newExpense.expenseType}
                  onChange={(e) => setNewExpense({ ...newExpense, expenseType: e.target.value as any })}
                  className="w-full p-2 border border-slate-300 rounded-lg"
                >
                  <option value="ASSET_TOKEN_PURCHASE">购买/补充拼多多公共Token资产</option>
                  <option value="SERVER_BANDWIDTH">服务器/网络带宽运营支出</option>
                  <option value="OTHER">其他技术/运营支出</option>
                </select>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-slate-700 font-semibold mb-1">补充Token账号数 (个)</label>
                  <input
                    type="number"
                    value={newExpense.tokenCountAdded}
                    onChange={(e) => {
                      const count = Number(e.target.value);
                      setNewExpense({
                        ...newExpense,
                        tokenCountAdded: count,
                        amount: count * newExpense.unitPrice
                      });
                    }}
                    className="w-full p-2 border border-slate-300 rounded-lg font-mono"
                  />
                </div>
                <div>
                  <label className="block text-slate-700 font-semibold mb-1">采购单价 (元/个)</label>
                  <input
                    type="number"
                    value={newExpense.unitPrice}
                    onChange={(e) => {
                      const price = Number(e.target.value);
                      setNewExpense({
                        ...newExpense,
                        unitPrice: price,
                        amount: price * newExpense.tokenCountAdded
                      });
                    }}
                    className="w-full p-2 border border-slate-300 rounded-lg font-mono"
                  />
                </div>
              </div>

              <div>
                <label className="block text-slate-700 font-semibold mb-1">支出总金额 (元)</label>
                <input
                  type="number"
                  value={newExpense.amount}
                  onChange={(e) => setNewExpense({ ...newExpense, amount: Number(e.target.value) })}
                  className="w-full p-2 border border-slate-300 rounded-lg font-mono font-bold text-rose-600"
                  required
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-slate-700 font-semibold mb-1">商户/渠道来源</label>
                  <input
                    type="text"
                    value={newExpense.supplierChannel}
                    onChange={(e) => setNewExpense({ ...newExpense, supplierChannel: e.target.value })}
                    className="w-full p-2 border border-slate-300 rounded-lg"
                    placeholder="如: 分销商A"
                    required
                  />
                </div>
                <div>
                  <label className="block text-slate-700 font-semibold mb-1">记账日期</label>
                  <input
                    type="date"
                    value={newExpense.expenseDate}
                    onChange={(e) => setNewExpense({ ...newExpense, expenseDate: e.target.value })}
                    className="w-full p-2 border border-slate-300 rounded-lg font-mono"
                    required
                  />
                </div>
              </div>

              <div>
                <label className="block text-slate-700 font-semibold mb-1">详细备注</label>
                <input
                  type="text"
                  value={newExpense.remark}
                  onChange={(e) => setNewExpense({ ...newExpense, remark: e.target.value })}
                  className="w-full p-2 border border-slate-300 rounded-lg"
                  placeholder="如: 批量补充采购50个高权重Token"
                  required
                />
              </div>

              <div className="flex justify-end gap-2 pt-3 border-t border-slate-100">
                <button
                  type="button"
                  onClick={() => setShowAddExpenseModal(false)}
                  className="px-4 py-2 text-slate-600 hover:bg-slate-100 rounded-lg"
                >
                  取消
                </button>
                <button
                  type="submit"
                  className="px-4 py-2 bg-rose-600 hover:bg-rose-700 text-white font-semibold rounded-lg shadow-xs"
                >
                  确认录入支出表
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
