import React, { useState } from 'react';
import { 
  Users, 
  ShieldAlert, 
  UserCheck, 
  Key, 
  DollarSign, 
  CheckCircle2, 
  XCircle, 
  ArrowRight, 
  Lock, 
  Smartphone, 
  Layers, 
  Sliders, 
  Eye, 
  EyeOff, 
  FileText,
  Building,
  UserX,
  Zap,
  HelpCircle,
  Sparkles
} from 'lucide-react';

export const RoleMatrixView: React.FC = () => {
  const [selectedRole, setSelectedRole] = useState<'SUPER_ADMIN' | 'AGENT' | 'END_USER'>('SUPER_ADMIN');
  const [activeTab, setActiveTab] = useState<'MATRIX' | 'DECISIONS' | 'WORKFLOW' | 'COMPARISON'>('MATRIX');

  // 4 大关键产品决策状态与交互确认
  const [decisions, setDecisions] = useState({
    decision1_cardMode: '1-B', // 1-A 预充值 or 1-B 信用制卡+激活核销记账
    decision2_poolStrategy: '2-B', // 2-A 固定池 or 2-B 动态弹性池+热备自愈
    decision3_devicePolicy: '3-A', // 3-A 单设备互踢 or 3-B IP并发频控
    decision4_encryptionScheme: '4-A' // 4-A 动态Nonce派生+字节混淆 or 4-B RSA握手
  });

  const roleDetails = {
    SUPER_ADMIN: {
      name: '超级管理员 / 财务总监',
      code: 'SUPER_ADMIN / FINANCE',
      badge: 'bg-rose-50 text-rose-700 border-rose-200',
      tagColor: 'text-rose-600',
      icon: ShieldAlert,
      desc: '系统的最高权限掌控者与公司资产总负责人，统揽全局底层账号池采购、套餐战略定价与全公司年/季/月财务审计对账。',
      corePowers: [
        '公司拼多多公共账号池统一采购管理与 Token 录入 (单价、数量、供应商记录)',
        '全平台年/季/月度财务总收入、总支出与公司净利润全盘对账审计',
        '代理商 / 销售管理员账号的创建、指标分配、停用与风控冻结',
        '全局套餐价格模板配置 (账号数 X × 单账号次数 Y = 总次数 X*Y)',
        '人工调整用户额度与补偿延期 (全程强制留痕不可逆操作日志)'
      ],
      forbiddenList: [
        '严禁向客户端或普通人员泄露拼多多 Token 明文秘钥 (自动 AES 加密存储)',
        '严禁进行无审计记录的物理静默删库/改表操作 (所有操作均落审计日志)'
      ],
      dataScope: '全公司所有用户、所有代理商、所有财务收支流水、全部底层拼多多账号池资产'
    },
    AGENT: {
      name: '代理商 / 渠道销售管理员',
      code: 'AGENT / SALES_ADMIN',
      badge: 'bg-indigo-50 text-indigo-700 border-indigo-200',
      tagColor: 'text-indigo-600',
      icon: Users,
      desc: '负责拓展客户、对接线下转账与批量分发卡密的业务人员，拥有独立专属渠道码，专注于客户拓展与自身销售业绩统计。',
      corePowers: [
        '在专属额度内批量生成业务卡密 (自动打上本人工号/渠道标识)',
        '录入客户线下转账凭证号 (银行转账/支付宝/微信收款订单号)',
        '查询名下所售卡密的激活状态与激活手机号',
        '查看自身拓展客户的生命周期消费总额 (LTV) 与销售业绩提成报表',
        '作废名下未激活的失误卡密 (不可作废已被客户激活的卡密)'
      ],
      forbiddenList: [
        '严禁查看公司底层真实拼多多账号与 Token 资产库',
        '严禁查看公司采购 Token 的单价成本与全公司总利润 (仅可看自身销售额)',
        '严禁查看或操作其他代理商/销售的名下客户与卡密流水'
      ],
      dataScope: '仅限于自身名下生成的卡密、自身拓展并激活的客户档案与提成流水'
    },
    END_USER: {
      name: '终端客户 (End User)',
      code: 'END_USER (CLIENT)',
      badge: 'bg-emerald-50 text-emerald-700 border-emerald-200',
      tagColor: 'text-emerald-600',
      icon: UserCheck,
      desc: '实际在 PC/移动客户端运行业务的最终付费客户，通过真实手机号注册使用，享受透明配额与底层公共资产的高可用调度。',
      corePowers: [
        '真实手机号 + 短信验证码注册 (自动获得 1 天免费试用 20 次配额: 1账号×20次/天)',
        '在客户端输入卡密一键原子核销激活 (同套餐顺延有效期，不同套餐进入权益队列)',
        '实时查询当前套餐到期日、剩余总次数、各逻辑账号槽位消耗明细',
        '查询个人历史调用明细日志 (时间、操作名、扣除次数、调度结果)',
        '享受底层多账号轮巡调度与 Token 故障无感自动替补自愈服务'
      ],
      forbiddenList: [
        '严禁绕过服务端鉴权拦截器 (配额耗尽/过期后强制收到 403 熔断退出)',
        '严禁查看底层拼多多账号真实密码、Cookie、敏感 Token 等明文数据',
        '严禁多设备同时并发登录共享账号 (触发单设备互踢与异常风控)'
      ],
      dataScope: '仅能查询自身手机号绑定的当前套餐、槽位使用进度与个人消费日志'
    }
  };

  const currentRole = roleDetails[selectedRole];
  const IconComp = currentRole.icon;

  return (
    <div className="space-y-8 max-w-5xl mx-auto pb-16 font-sans text-slate-800">
      {/* 顶部主横幅 */}
      <div className="bg-gradient-to-r from-slate-900 via-indigo-950 to-slate-900 text-white rounded-2xl p-8 shadow-xl border border-slate-700">
        <div className="flex flex-wrap items-center justify-between gap-4 mb-3">
          <div className="inline-flex items-center gap-2 px-3 py-1 bg-indigo-500/20 text-indigo-300 rounded-full text-xs font-mono border border-indigo-400/30">
            <Users className="w-3.5 h-3.5" />
            <span>PDK V0.1 权限中枢架构与产品决策矩阵</span>
          </div>
          <span className="text-xs text-slate-400 font-mono">
            基于三角色权限隔离 + 4项核心业务决策深度融合
          </span>
        </div>
        <h1 className="text-2xl sm:text-3xl font-bold tracking-tight text-white mb-2">
          PDK 三种用户角色职责范围与产品决策落地
        </h1>
        <p className="text-slate-300 text-sm max-w-3xl leading-relaxed">
          清晰划分 <strong>超级管理员/财务</strong>、<strong>代理商/销售</strong> 与 <strong>终端客户</strong> 的权限边界与数据隔离红线，并对 ChatGPT 方案中的核心决策提供交互式对照与确认。
        </p>

        {/* 顶部导航选项卡 */}
        <div className="flex flex-wrap gap-2 mt-6 pt-4 border-t border-slate-800">
          <button
            onClick={() => setActiveTab('MATRIX')}
            className={`px-4 py-2 rounded-lg text-xs font-semibold flex items-center gap-2 transition ${
              activeTab === 'MATRIX' 
                ? 'bg-indigo-600 text-white shadow-md' 
                : 'bg-slate-800/80 text-slate-300 hover:text-white hover:bg-slate-700'
            }`}
          >
            <Layers className="w-4 h-4" />
            <span>三角色权限职责矩阵</span>
          </button>

          <button
            onClick={() => setActiveTab('DECISIONS')}
            className={`px-4 py-2 rounded-lg text-xs font-semibold flex items-center gap-2 transition ${
              activeTab === 'DECISIONS' 
                ? 'bg-emerald-600 text-white shadow-md' 
                : 'bg-slate-800/80 text-slate-300 hover:text-white hover:bg-slate-700'
            }`}
          >
            <Sliders className="w-4 h-4" />
            <span>4 大核心业务决策对比与确认 ★</span>
          </button>

          <button
            onClick={() => setActiveTab('WORKFLOW')}
            className={`px-4 py-2 rounded-lg text-xs font-semibold flex items-center gap-2 transition ${
              activeTab === 'WORKFLOW' 
                ? 'bg-blue-600 text-white shadow-md' 
                : 'bg-slate-800/80 text-slate-300 hover:text-white hover:bg-slate-700'
            }`}
          >
            <Zap className="w-4 h-4" />
            <span>跨角色业务闭环时序流程</span>
          </button>
        </div>
      </div>

      {/* 视图 1：三角色权限矩阵 */}
      {activeTab === 'MATRIX' && (
        <div className="space-y-6">
          {/* 角色切换卡片 */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {(['SUPER_ADMIN', 'AGENT', 'END_USER'] as const).map(roleKey => {
              const role = roleDetails[roleKey];
              const RoleIcon = role.icon;
              const isSelected = selectedRole === roleKey;
              return (
                <button
                  key={roleKey}
                  onClick={() => setSelectedRole(roleKey)}
                  className={`p-5 rounded-xl border text-left transition-all relative overflow-hidden ${
                    isSelected 
                      ? 'bg-white border-indigo-600 shadow-md ring-2 ring-indigo-500/20' 
                      : 'bg-white border-slate-200 hover:border-slate-300 hover:shadow-xs'
                  }`}
                >
                  <div className="flex items-center justify-between mb-3">
                    <div className={`p-2.5 rounded-lg ${isSelected ? 'bg-indigo-600 text-white' : 'bg-slate-100 text-slate-700'}`}>
                      <RoleIcon className="w-5 h-5" />
                    </div>
                    <span className={`text-[11px] font-mono font-bold px-2 py-0.5 rounded border ${role.badge}`}>
                      {roleKey}
                    </span>
                  </div>
                  <div className="font-bold text-slate-900 text-sm mb-1">{role.name}</div>
                  <div className="text-xs text-slate-500 line-clamp-2">{role.desc}</div>
                </button>
              );
            })}
          </div>

          {/* 选中角色的深度权限画像 */}
          <div className="bg-white border border-slate-200 rounded-xl p-6 sm:p-8 shadow-xs space-y-6">
            <div className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-100 pb-4">
              <div className="flex items-center gap-3">
                <div className="p-3 bg-indigo-50 text-indigo-600 rounded-xl">
                  <IconComp className="w-6 h-6" />
                </div>
                <div>
                  <h3 className="text-lg font-bold text-slate-900 flex items-center gap-2">
                    {currentRole.name}
                    <span className="text-xs font-mono font-normal text-slate-500 bg-slate-100 px-2 py-0.5 rounded">
                      {currentRole.code}
                    </span>
                  </h3>
                  <p className="text-xs text-slate-500 mt-0.5">{currentRole.desc}</p>
                </div>
              </div>
              <div className="text-right">
                <span className="text-xs text-slate-400 font-mono block">数据可见域</span>
                <span className="text-xs font-semibold text-slate-700">{currentRole.dataScope}</span>
              </div>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              {/* 核心允许权限 */}
              <div className="bg-emerald-50/50 border border-emerald-200 rounded-xl p-5 space-y-3">
                <div className="font-bold text-emerald-900 text-xs uppercase tracking-wider flex items-center gap-1.5">
                  <CheckCircle2 className="w-4 h-4 text-emerald-600" />
                  <span>核心职责与允许操作 (Allowed Capabilities)</span>
                </div>
                <ul className="space-y-2 text-xs text-slate-700">
                  {currentRole.corePowers.map((power, idx) => (
                    <li key={idx} className="flex items-start gap-2">
                      <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 mt-1.5 shrink-0"></span>
                      <span>{power}</span>
                    </li>
                  ))}
                </ul>
              </div>

              {/* 严禁越权红线 */}
              <div className="bg-rose-50/50 border border-rose-200 rounded-xl p-5 space-y-3">
                <div className="font-bold text-rose-900 text-xs uppercase tracking-wider flex items-center gap-1.5">
                  <XCircle className="w-4 h-4 text-rose-600" />
                  <span>严禁操作与权限隔离红线 (Forbidden & Security Redline)</span>
                </div>
                <ul className="space-y-2 text-xs text-slate-700">
                  {currentRole.forbiddenList.map((forbid, idx) => (
                    <li key={idx} className="flex items-start gap-2">
                      <span className="w-1.5 h-1.5 rounded-full bg-rose-500 mt-1.5 shrink-0"></span>
                      <span>{forbid}</span>
                    </li>
                  ))}
                </ul>
              </div>
            </div>

            {/* 跨端界面形态映射 */}
            <div className="bg-slate-50 border border-slate-200 rounded-lg p-4 text-xs">
              <div className="font-bold text-slate-800 mb-2 flex items-center gap-1.5">
                <Building className="w-4 h-4 text-indigo-600" />
                <span>该角色操作的前端接入形态：</span>
              </div>
              <div className="text-slate-600">
                {selectedRole === 'SUPER_ADMIN' && '使用 Vue 3 Web 后台完整功能（财务中心、资产采购、全局套餐管理、全平台操作审计、超级管理员指令）。'}
                {selectedRole === 'AGENT' && '使用 Vue 3 Web 后台中的【代理商分销工作台】模块（渠道制卡、打款录入、个人销售提成统计、名下卡密管理）。'}
                {selectedRole === 'END_USER' && '使用 C#/Electron/Qt 等客户端程序（手机号短信注册、卡密输入激活、个人配额余量查询、业务接口调度）。'}
              </div>
            </div>
          </div>

          {/* 三角色对比总表 */}
          <div className="bg-white border border-slate-200 rounded-xl overflow-hidden shadow-xs">
            <div className="px-6 py-4 bg-slate-50 border-b border-slate-200 flex items-center justify-between">
              <h4 className="font-bold text-sm text-slate-900 flex items-center gap-2">
                <Layers className="w-4 h-4 text-indigo-600" />
                <span>三角色全功能维度矩阵对照表</span>
              </h4>
              <span className="text-xs text-slate-500 font-mono">RBAC_PERMISSION_SPEC</span>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="bg-slate-100 text-slate-700 font-semibold border-b border-slate-200">
                  <tr>
                    <th className="p-3">功能模块 / 业务场景</th>
                    <th className="p-3 text-rose-700">1. 超级管理员 / 财务</th>
                    <th className="p-3 text-indigo-700">2. 代理商 / 销售</th>
                    <th className="p-3 text-emerald-700">3. 终端客户 (Client)</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 text-slate-600">
                  <tr>
                    <td className="p-3 font-semibold text-slate-800">手机号短信注册与1天试用</td>
                    <td className="p-3 text-slate-400">可审计查询所有注册记录</td>
                    <td className="p-3 text-slate-400">可查名下邀请注册客户</td>
                    <td className="p-3 text-emerald-600 font-bold">自服务注册 + 自动获赠试用</td>
                  </tr>
                  <tr>
                    <td className="p-3 font-semibold text-slate-800">卡密批量生成 (pdk_card_key)</td>
                    <td className="p-3 text-rose-600 font-bold">全平台任意套餐制卡</td>
                    <td className="p-3 text-indigo-600 font-bold">分配额度内自主制卡(绑定渠道)</td>
                    <td className="p-3 text-slate-400">无权限</td>
                  </tr>
                  <tr>
                    <td className="p-3 font-semibold text-slate-800">卡密核销激活</td>
                    <td className="p-3 text-slate-500">可人工代客核销</td>
                    <td className="p-3 text-slate-500">可协助核销</td>
                    <td className="p-3 text-emerald-600 font-bold">客户端自助输入卡密核销</td>
                  </tr>
                  <tr>
                    <td className="p-3 font-semibold text-slate-800">公共 Token 资产池维护与采购</td>
                    <td className="p-3 text-rose-600 font-bold">录入 Token、采购记账、状态监控</td>
                    <td className="p-3 text-slate-400">严禁接触 (不可见)</td>
                    <td className="p-3 text-slate-400">严禁接触 (无感调度)</td>
                  </tr>
                  <tr>
                    <td className="p-3 font-semibold text-slate-800">财务年/季/月度收支与净利润</td>
                    <td className="p-3 text-rose-600 font-bold">全局财务审计、采购支出与对冲</td>
                    <td className="p-3 text-slate-400">严禁查看公司成本与总利润</td>
                    <td className="p-3 text-slate-400">无权限</td>
                  </tr>
                  <tr>
                    <td className="p-3 font-semibold text-slate-800">销售业绩提成报表</td>
                    <td className="p-3 text-slate-700">可查所有销售的提成与报表</td>
                    <td className="p-3 text-indigo-600 font-bold">仅能查看个人名下销售业绩</td>
                    <td className="p-3 text-slate-400">无权限</td>
                  </tr>
                  <tr>
                    <td className="p-3 font-semibold text-slate-800">配额耗尽与过期强制熔断</td>
                    <td className="p-3 text-slate-500">可人工补偿延期 (留痕)</td>
                    <td className="p-3 text-slate-400">无权限</td>
                    <td className="p-3 text-rose-600 font-bold">服务端 403 强制熔断退出客户端</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {/* 视图 2：4 大核心业务决策对比与确认 */}
      {activeTab === 'DECISIONS' && (
        <div className="space-y-6">
          <div className="bg-emerald-50 border border-emerald-200 rounded-xl p-5">
            <div className="flex items-center gap-2 text-emerald-900 font-bold text-sm mb-1">
              <Sparkles className="w-4 h-4 text-emerald-600" />
              <span>4 大核心业务技术决策确认面板</span>
            </div>
            <p className="text-xs text-emerald-800">
              根据您的需求，以下为融合 ChatGPT 方案提炼出的 4 大关键架构分支。当前系统已默认配置最佳实践方案，您可以直观对比每项决策的优缺点：
            </p>
          </div>

          <div className="space-y-6">
            {/* 决策 1 */}
            <div className="bg-white border border-slate-200 rounded-xl p-6 shadow-xs">
              <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
                <div className="flex items-center gap-2.5">
                  <span className="w-6 h-6 rounded-md bg-indigo-600 text-white font-bold text-xs flex items-center justify-center">1</span>
                  <h4 className="font-bold text-slate-900 text-sm">决策项 1：代理商/销售制卡模式与财务核减方式</h4>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => setDecisions(prev => ({ ...prev, decision1_cardMode: '1-A' }))}
                    className={`px-3 py-1 rounded-md text-xs font-semibold transition ${
                      decisions.decision1_cardMode === '1-A' 
                        ? 'bg-indigo-600 text-white' 
                        : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
                    }`}
                  >
                    方案 1-A (预充值扣额制)
                  </button>
                  <button
                    onClick={() => setDecisions(prev => ({ ...prev, decision1_cardMode: '1-B' }))}
                    className={`px-3 py-1 rounded-md text-xs font-semibold flex items-center gap-1 transition ${
                      decisions.decision1_cardMode === '1-B' 
                        ? 'bg-emerald-600 text-white' 
                        : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
                    }`}
                  >
                    <span>方案 1-B (信用制卡+激活核销记账)</span>
                    <span className="text-[10px] bg-emerald-700 text-emerald-100 px-1 rounded">推荐★</span>
                  </button>
                </div>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
                <div className={`p-4 rounded-lg border ${decisions.decision1_cardMode === '1-A' ? 'bg-indigo-50/60 border-indigo-300' : 'bg-slate-50 border-slate-200'}`}>
                  <div className="font-bold text-slate-900 mb-1">方案 1-A：预充值扣额制</div>
                  <p className="text-slate-600 mb-2">代理商需先向公司对公账户预充资金（如打款1万元），制卡时直接扣除代理商账户余额。</p>
                  <div className="text-slate-500"><strong>适用场景：</strong> 外部大型加盟渠道商，防止销售私自囤卡倒卖。</div>
                </div>
                <div className={`p-4 rounded-lg border ${decisions.decision1_cardMode === '1-B' ? 'bg-emerald-50/60 border-emerald-300 ring-1 ring-emerald-400' : 'bg-slate-50 border-slate-200'}`}>
                  <div className="font-bold text-emerald-950 mb-1 flex items-center justify-between">
                    <span>方案 1-B：信用制卡 + 实际激活核销记账 ★</span>
                    <span className="text-[10px] text-emerald-700 font-bold">当前已集成</span>
                  </div>
                  <p className="text-slate-600 mb-2">销售可直接批量制卡并发给意向客户，只有当客户<strong>在客户端实际输入激活成功时</strong>，才原子写入财务流水并计入业绩。</p>
                  <div className="text-emerald-800"><strong>优势：</strong> 完全以实际到款与激活为准，无卡密积压资金风险，极大降低销售摩擦力！</div>
                </div>
              </div>
            </div>

            {/* 决策 2 */}
            <div className="bg-white border border-slate-200 rounded-xl p-6 shadow-xs">
              <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
                <div className="flex items-center gap-2.5">
                  <span className="w-6 h-6 rounded-md bg-indigo-600 text-white font-bold text-xs flex items-center justify-center">2</span>
                  <h4 className="font-bold text-slate-900 text-sm">决策项 2：多账号池 (X个账号 × Y次) 调度与故障自愈策略</h4>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => setDecisions(prev => ({ ...prev, decision2_poolStrategy: '2-A' }))}
                    className={`px-3 py-1 rounded-md text-xs font-semibold transition ${
                      decisions.decision2_poolStrategy === '2-A' 
                        ? 'bg-indigo-600 text-white' 
                        : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
                    }`}
                  >
                    方案 2-A (固定绑定虚拟池)
                  </button>
                  <button
                    onClick={() => setDecisions(prev => ({ ...prev, decision2_poolStrategy: '2-B' }))}
                    className={`px-3 py-1 rounded-md text-xs font-semibold flex items-center gap-1 transition ${
                      decisions.decision2_poolStrategy === '2-B' 
                        ? 'bg-emerald-600 text-white' 
                        : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
                    }`}
                  >
                    <span>方案 2-B (动态弹性池+热备自愈)</span>
                    <span className="text-[10px] bg-emerald-700 text-emerald-100 px-1 rounded">推荐★</span>
                  </button>
                </div>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
                <div className={`p-4 rounded-lg border ${decisions.decision2_poolStrategy === '2-A' ? 'bg-indigo-50/60 border-indigo-300' : 'bg-slate-50 border-slate-200'}`}>
                  <div className="font-bold text-slate-900 mb-1">方案 2-A：固定绑定虚拟池轮巡</div>
                  <p className="text-slate-600 mb-2">用户开卡时死锁 X 个固定账号，某个账号用满 Y 次后在该用户剩余可用账号中轮巡。</p>
                  <div className="text-slate-500"><strong>缺点：</strong> 若官方风控导致其中一个账号失效，用户可用总账号数减少。</div>
                </div>
                <div className={`p-4 rounded-lg border ${decisions.decision2_poolStrategy === '2-B' ? 'bg-emerald-50/60 border-emerald-300 ring-1 ring-emerald-400' : 'bg-slate-50 border-slate-200'}`}>
                  <div className="font-bold text-emerald-950 mb-1 flex items-center justify-between">
                    <span>方案 2-B：动态弹性池 + 自动热备自愈 ★</span>
                    <span className="text-[10px] text-emerald-700 font-bold">当前已集成</span>
                  </div>
                  <p className="text-slate-600 mb-2">用户享有 X*Y 理论总配额。网关动态加权调度健康 Token，一旦某个 Token 报异常，<strong>毫秒级自动热剔除并补入备用健康 Token</strong>。</p>
                  <div className="text-emerald-800"><strong>优势：</strong> 可用性高达 99.99%，客户对拼多多底层风控完全无感知，极大减少售后退款纠纷！</div>
                </div>
              </div>
            </div>

            {/* 决策 3 */}
            <div className="bg-white border border-slate-200 rounded-xl p-6 shadow-xs">
              <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
                <div className="flex items-center gap-2.5">
                  <span className="w-6 h-6 rounded-md bg-indigo-600 text-white font-bold text-xs flex items-center justify-center">3</span>
                  <h4 className="font-bold text-slate-900 text-sm">决策项 3：手机号唯一性与多设备防倒卖风控规则</h4>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => setDecisions(prev => ({ ...prev, decision3_devicePolicy: '3-A' }))}
                    className={`px-3 py-1 rounded-md text-xs font-semibold flex items-center gap-1 transition ${
                      decisions.decision3_devicePolicy === '3-A' 
                        ? 'bg-emerald-600 text-white' 
                        : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
                    }`}
                  >
                    <span>方案 3-A (单设备绑定/互踢下线)</span>
                    <span className="text-[10px] bg-emerald-700 text-emerald-100 px-1 rounded">推荐★</span>
                  </button>
                  <button
                    onClick={() => setDecisions(prev => ({ ...prev, decision3_devicePolicy: '3-B' }))}
                    className={`px-3 py-1 rounded-md text-xs font-semibold transition ${
                      decisions.decision3_devicePolicy === '3-B' 
                        ? 'bg-indigo-600 text-white' 
                        : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
                    }`}
                  >
                    方案 3-B (IP并发限速)
                  </button>
                </div>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
                <div className={`p-4 rounded-lg border ${decisions.decision3_devicePolicy === '3-A' ? 'bg-emerald-50/60 border-emerald-300 ring-1 ring-emerald-400' : 'bg-slate-50 border-slate-200'}`}>
                  <div className="font-bold text-emerald-950 mb-1 flex items-center justify-between">
                    <span>方案 3-A：单设备强制互踢下线 ★</span>
                    <span className="text-[10px] text-emerald-700 font-bold">当前已集成</span>
                  </div>
                  <p className="text-slate-600 mb-2">一个手机号同一时刻仅允许在一台 PC 客户端登录，新设备登录后旧设备在下一次心跳或请求时被服务端 401 强制踢出。</p>
                  <div className="text-emerald-800"><strong>优势：</strong> 彻底杜绝多个人合租合买一个卡密共享使用的羊毛行为！</div>
                </div>
                <div className={`p-4 rounded-lg border ${decisions.decision3_devicePolicy === '3-B' ? 'bg-indigo-50/60 border-indigo-300' : 'bg-slate-50 border-slate-200'}`}>
                  <div className="font-bold text-slate-900 mb-1">方案 3-B：IP 并发频控限速</div>
                  <p className="text-slate-600 mb-2">不限制客户端登录台数，但限制单手机号总 QPS（如 5次/秒）。</p>
                  <div className="text-slate-500"><strong>缺点：</strong> 存在客户多人拼单使用一个账号的漏洞，损伤公司长期收入。</div>
                </div>
              </div>
            </div>

            {/* 决策 4 */}
            <div className="bg-white border border-slate-200 rounded-xl p-6 shadow-xs">
              <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
                <div className="flex items-center gap-2.5">
                  <span className="w-6 h-6 rounded-md bg-indigo-600 text-white font-bold text-xs flex items-center justify-center">4</span>
                  <h4 className="font-bold text-slate-900 text-sm">决策项 4：客户端 SDK 通信加解密机制</h4>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => setDecisions(prev => ({ ...prev, decision4_encryptionScheme: '4-A' }))}
                    className={`px-3 py-1 rounded-md text-xs font-semibold flex items-center gap-1 transition ${
                      decisions.decision4_encryptionScheme === '4-A' 
                        ? 'bg-emerald-600 text-white' 
                        : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
                    }`}
                  >
                    <span>方案 4-A (动态时间派生+字节混淆)</span>
                    <span className="text-[10px] bg-emerald-700 text-emerald-100 px-1 rounded">推荐★</span>
                  </button>
                  <button
                    onClick={() => setDecisions(prev => ({ ...prev, decision4_encryptionScheme: '4-B' }))}
                    className={`px-3 py-1 rounded-md text-xs font-semibold transition ${
                      decisions.decision4_encryptionScheme === '4-B' 
                        ? 'bg-indigo-600 text-white' 
                        : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
                    }`}
                  >
                    方案 4-B (RSA 动态握手交换)
                  </button>
                </div>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
                <div className={`p-4 rounded-lg border ${decisions.decision4_encryptionScheme === '4-A' ? 'bg-emerald-50/60 border-emerald-300 ring-1 ring-emerald-400' : 'bg-slate-50 border-slate-200'}`}>
                  <div className="font-bold text-emerald-950 mb-1 flex items-center justify-between">
                    <span>方案 4-A：动态时间窗口派生密钥 + 字节翻转混淆 ★</span>
                    <span className="text-[10px] text-emerald-700 font-bold">当前已集成</span>
                  </div>
                  <p className="text-slate-600 mb-2">客户端 SDK 内部自动基于时间窗口派生 AES 密钥并执行私有魔数翻转，开发接入无需握手，调用仅需一行代码，抓包直接为未知二进制损坏数据。</p>
                  <div className="text-emerald-800"><strong>优势：</strong> 零开发心智负担，抓包工具无法分析，且网络无额外往返延迟！</div>
                </div>
                <div className={`p-4 rounded-lg border ${decisions.decision4_encryptionScheme === '4-B' ? 'bg-indigo-50/60 border-indigo-300' : 'bg-slate-50 border-slate-200'}`}>
                  <div className="font-bold text-slate-900 mb-1">方案 4-B：RSA 非对称握手下发临时 SessionKey</div>
                  <p className="text-slate-600 mb-2">每次客户端启动先用 RSA 公钥与服务器协商一次专属会话秘钥。</p>
                  <div className="text-slate-500"><strong>缺点：</strong> 每次启动增加一次网络往返握手，网络抖动时握手可能超时失败。</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 视图 3：跨角色完整业务时序 */}
      {activeTab === 'WORKFLOW' && (
        <div className="bg-white border border-slate-200 rounded-xl p-8 shadow-xs space-y-6">
          <div className="border-b border-slate-100 pb-4">
            <h3 className="text-lg font-bold text-slate-900 flex items-center gap-2">
              <Zap className="w-5 h-5 text-indigo-600" />
              <span>PDK 跨角色标准业务闭环时序模型</span>
            </h3>
            <p className="text-xs text-slate-500 mt-1">从资产采购、渠道制卡、客户核销到调度消费的全流程跨角色协同：</p>
          </div>

          <div className="relative border-l-2 border-indigo-200 ml-4 space-y-8 text-xs font-sans">
            {/* Step 1 */}
            <div className="relative pl-6">
              <div className="absolute -left-2.5 top-0 w-5 h-5 rounded-full bg-rose-600 text-white font-bold text-[10px] flex items-center justify-center">1</div>
              <div className="font-bold text-slate-900 text-sm">公司资产采购与底层账号池扩容</div>
              <span className="text-[11px] font-mono text-rose-700 bg-rose-50 px-2 py-0.5 rounded">执行主体：超级管理员 / 财务主管</span>
              <p className="text-slate-600 mt-1.5 leading-relaxed">
                超管集中采购 100 个拼多多账号 Token，在后台录入采购单价（如 70元/个），系统写入支出表 <code>pdk_company_expense</code>，Token 经 AES-256 加密存入 <code>pdk_pdd_account_pool</code>。
              </p>
            </div>

            {/* Step 2 */}
            <div className="relative pl-6">
              <div className="absolute -left-2.5 top-0 w-5 h-5 rounded-full bg-indigo-600 text-white font-bold text-[10px] flex items-center justify-center">2</div>
              <div className="font-bold text-slate-900 text-sm">线下收款与渠道批量制卡</div>
              <span className="text-[11px] font-mono text-indigo-700 bg-indigo-50 px-2 py-0.5 rounded">执行主体：代理商 / 渠道销售管理员</span>
              <p className="text-slate-600 mt-1.5 leading-relaxed">
                客户通过微信/银行转账 200 元给销售。销售在后台选定【200元月卡 (4*250)】生成卡密 <code>PDK-8821-9920-1123</code>，卡密被打上该销售的工号并录入付款流水号，写入 <code>pdk_card_key (状态: UNUSED)</code>。
              </p>
            </div>

            {/* Step 3 */}
            <div className="relative pl-6">
              <div className="absolute -left-2.5 top-0 w-5 h-5 rounded-full bg-emerald-600 text-white font-bold text-[10px] flex items-center justify-center">3</div>
              <div className="font-bold text-slate-900 text-sm">客户手机号注册与卡密原子核销</div>
              <span className="text-[11px] font-mono text-emerald-700 bg-emerald-50 px-2 py-0.5 rounded">执行主体：终端客户 (Client)</span>
              <p className="text-slate-600 mt-1.5 leading-relaxed">
                客户通过手机短信注册并输入卡密。Spring 服务端开启本地事务：
                1. 锁卡密并将状态改为 <code>ACTIVATED</code>；
                2. 写入独立的 <code>pdk_financial_income</code> 财务收入表（记录到账 200 元）；
                3. 顺延客户 <code>pdk_user</code> 到期时间 30 天；
                4. 向 Redis 注入 4 个虚拟账号槽位（总额度 1000 次）。
              </p>
            </div>

            {/* Step 4 */}
            <div className="relative pl-6">
              <div className="absolute -left-2.5 top-0 w-5 h-5 rounded-full bg-blue-600 text-white font-bold text-[10px] flex items-center justify-center">4</div>
              <div className="font-bold text-slate-900 text-sm">业务透明加解密与网关弹性调度</div>
              <span className="text-[11px] font-mono text-blue-700 bg-blue-50 px-2 py-0.5 rounded">执行主体：PDK 网关调度引擎 + 终端客户 SDK</span>
              <p className="text-slate-600 mt-1.5 leading-relaxed">
                客户端 SDK 自动进行时间混淆 AES 加密。PDK 网关在 2ms 内鉴权、轮巡调度健康 Token、扣减 1 次配额；若某 Token 失效，网关自动替换备用 Token，客户业务零中断。
              </p>
            </div>

            {/* Step 5 */}
            <div className="relative pl-6">
              <div className="absolute -left-2.5 top-0 w-5 h-5 rounded-full bg-slate-700 text-white font-bold text-[10px] flex items-center justify-center">5</div>
              <div className="font-bold text-slate-900 text-sm">到期熔断退出与年/季/月财务全盘对账</div>
              <span className="text-[11px] font-mono text-slate-700 bg-slate-100 px-2 py-0.5 rounded">执行主体：超级管理员 / 财务总监 + 客户端熔断器</span>
              <p className="text-slate-600 mt-1.5 leading-relaxed">
                额度用尽时客户端收到 403 信号强制退出。财务总监在 Web 后台一键按年/季/月聚合 <code>pdk_financial_income (实收)</code> 对冲 <code>pdk_company_expense (支出)</code>，精准核算公司实际净利润与渠道销售提成。
              </p>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
