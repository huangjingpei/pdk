import React, { useState } from 'react';
import { Copy, Check, BookOpen, Layers, ShieldCheck, Database, Sliders, AlertTriangle, TrendingUp, DollarSign, Lock, Zap, FileSpreadsheet, Split, ArrowRight, CheckCircle2, Key } from 'lucide-react';

export const DocumentViewer: React.FC = () => {
  return (
    <div className="space-y-12 text-slate-800 leading-relaxed font-sans max-w-5xl mx-auto pb-20">
      {/* Header Banner */}
      <div className="bg-gradient-to-r from-slate-900 via-slate-800 to-indigo-950 text-white rounded-2xl p-8 shadow-xl border border-slate-700">
        <div className="flex flex-wrap items-center justify-between gap-4 mb-4">
          <div className="inline-flex items-center gap-2 px-3 py-1 bg-emerald-500/20 text-emerald-300 rounded-full text-xs font-mono border border-emerald-400/30">
            <BookOpen className="w-3.5 h-3.5" />
            <span>PRD & TECH SPEC v1.2.0 (财务表结构物理彻底拆分版)</span>
          </div>
          <div className="text-xs text-slate-400 font-mono">
            更新重点: 卡密物理表与财务对账表彻底解耦拆分为独立物理表 + 资产采购支出表 + 财务多维聚合
          </div>
        </div>
        <h1 className="text-3xl font-bold tracking-tight text-white mb-2">
          PDK (拼多客) 服务端、调度与财务中台架构开发文档
        </h1>
        <p className="text-slate-300 text-sm max-w-3xl leading-normal">
          采用完全解耦的 3 大独立财务与卡密物理模型（<span className="text-emerald-300 font-semibold">卡密凭证表 pdk_card_key</span> + <span className="text-emerald-300 font-semibold">财务收入审计流水表 pdk_financial_income</span> + <span className="text-rose-300 font-semibold">资产采购支出表 pdk_company_expense</span>），支持年/季/月公司级对账与单用户消费穿透。
        </p>
      </div>

      {/* 拆表核心架构对比图示 */}
      <div className="bg-indigo-50/80 border border-indigo-200 rounded-xl p-6">
        <h3 className="text-sm font-bold text-indigo-950 uppercase tracking-wider mb-3 flex items-center gap-2">
          <Split className="w-4 h-4 text-indigo-600" />
          【核心设计】卡密业务与财务对账物理表彻底拆分解耦架构
        </h3>
        <p className="text-xs text-indigo-800 mb-4">
          坚决避免“卡密”与“财务”混在一张表中导致的职责混乱。卡密表只负责授权生命周期，财务表专门负责收支流水与审计对账：
        </p>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs font-mono">
          <div className="bg-white p-4 rounded-lg border border-indigo-200 shadow-xs">
            <div className="font-bold text-slate-900 mb-1 flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full bg-blue-500"></span>
              表一：pdk_card_key
            </div>
            <div className="text-[11px] text-blue-700 font-semibold mb-2">【卡密凭证物理表 - 纯业务授权】</div>
            <p className="text-slate-600 text-[11px] font-sans">
              只记录卡密明文、套餐规则(X*Y)、有效天数、核销状态(UNUSED/ACTIVATED/VOIDED)。纯用于客户端校验与授权。
            </p>
          </div>

          <div className="bg-white p-4 rounded-lg border border-emerald-200 shadow-xs">
            <div className="font-bold text-slate-900 mb-1 flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full bg-emerald-500"></span>
              表二：pdk_financial_income
            </div>
            <div className="text-[11px] text-emerald-700 font-semibold mb-2">【财务收入审计流水表 - 收入端】</div>
            <p className="text-slate-600 text-[11px] font-sans">
              核销时事务写入。记录收入流水号、打款凭证号、收款银行/渠道、具体用户手机号、金额。用于<strong>年/季/月公司收入对账与单用户消费统计</strong>。
            </p>
          </div>

          <div className="bg-white p-4 rounded-lg border border-rose-200 shadow-xs">
            <div className="font-bold text-slate-900 mb-1 flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-full bg-rose-500"></span>
              表三：pdk_company_expense
            </div>
            <div className="text-[11px] text-rose-700 font-semibold mb-2">【公司资产采购与支出表 - 支出端】</div>
            <p className="text-slate-600 text-[11px] font-sans">
              记录公司补充购买拼多多账号/Token资产的每一笔一次性支出(单价、数量、总金额、备注)，与收入表对冲核算<strong>公司净利润</strong>。
            </p>
          </div>
        </div>
      </div>

      {/* 目录导览 */}
      <div className="bg-slate-50 border border-slate-200 rounded-xl p-6">
        <h3 className="text-sm font-semibold text-slate-900 uppercase tracking-wider mb-4 flex items-center gap-2">
          <Layers className="w-4 h-4 text-indigo-600" />
          文档章节索引 (Table of Contents)
        </h3>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-sm">
          <a href="#sec-1" className="p-2.5 rounded-lg bg-white border border-slate-200 hover:border-indigo-400 hover:text-indigo-600 transition flex items-center justify-between">
            <span>1. 项目定位与核心目标</span>
            <span className="text-xs text-slate-400 font-mono">01</span>
          </a>
          <a href="#sec-roles" className="p-2.5 rounded-lg bg-white border border-purple-400 text-purple-950 font-medium transition flex items-center justify-between bg-purple-50/50">
            <span>2. 三种用户角色职责范围与产品决策 (ChatGPT融合)</span>
            <span className="text-xs text-purple-700 font-bold font-mono">02 ★新增</span>
          </a>
          <a href="#sec-2" className="p-2.5 rounded-lg bg-white border border-slate-200 hover:border-indigo-400 hover:text-indigo-600 transition flex items-center justify-between">
            <span>3. 手机号真实性注册与1天试用机制</span>
            <span className="text-xs text-slate-400 font-mono">03</span>
          </a>
          <a href="#sec-3" className="p-2.5 rounded-lg bg-white border border-slate-200 hover:border-indigo-400 hover:text-indigo-600 transition flex items-center justify-between">
            <span>4. Token账号资产池调度与精细配额</span>
            <span className="text-xs text-slate-400 font-mono">04</span>
          </a>
          <a href="#sec-4" className="p-2.5 rounded-lg bg-white border border-emerald-400 text-emerald-900 font-medium transition flex items-center justify-between bg-emerald-50/50">
            <span>5. 财务双向独立表设计（收入流水+资产支出+年/季/月/单用户）</span>
            <span className="text-xs text-emerald-700 font-bold font-mono">05 ★重点</span>
          </a>
          <a href="#sec-5" className="p-2.5 rounded-lg bg-white border border-indigo-400 text-indigo-900 font-medium transition flex items-center justify-between bg-indigo-50/50">
            <span>6. 数据库事务编排：卡密核销+财务入库+配额生效原子性</span>
            <span className="text-xs text-indigo-700 font-bold font-mono">06 ★重点</span>
          </a>
          <a href="#sec-6" className="p-2.5 rounded-lg bg-white border border-slate-200 hover:border-indigo-400 hover:text-indigo-600 transition flex items-center justify-between">
            <span>7. 客户端鉴权拦截与强制熔断退出设计</span>
            <span className="text-xs text-slate-400 font-mono">07</span>
          </a>
          <a href="#sec-7" className="p-2.5 rounded-lg bg-white border border-blue-400 text-blue-900 font-medium transition flex items-center justify-between bg-blue-50/50">
            <span>8. 通信加密：客户端零负担一行调用 + 深度防逆向抓包</span>
            <span className="text-xs text-blue-700 font-bold font-mono">08 ★重点</span>
          </a>
          <a href="#sec-8" className="p-2.5 rounded-lg bg-white border border-slate-200 hover:border-indigo-400 hover:text-indigo-600 transition flex items-center justify-between">
            <span>9. MySQL 数据库完整独立表结构 (DDL)</span>
            <span className="text-xs text-slate-400 font-mono">09</span>
          </a>
        </div>
      </div>

      {/* 第一章 */}
      <section id="sec-1" className="bg-white border border-slate-200 rounded-xl p-8 shadow-sm">
        <div className="flex items-center justify-between border-b border-slate-100 pb-4 mb-6">
          <div className="flex items-center gap-3">
            <span className="w-8 h-8 rounded-lg bg-indigo-50 text-indigo-600 font-bold flex items-center justify-center text-sm">1</span>
            <h2 className="text-xl font-bold text-slate-900">项目定位与核心目标</h2>
          </div>
          <span className="text-xs bg-slate-100 text-slate-600 px-2.5 py-1 rounded font-mono">SYSTEM_SCOPE</span>
        </div>

        <div className="space-y-4 text-slate-700 text-sm leading-relaxed">
          <p>
            <strong>PDK (拼多客) 服务端</strong> 是以 <strong>SpringBoot 3.x + Vue 3 + MySQL 8 + Redis 7</strong> 搭建的高并发拼多多公共资产池调度网关、线下收款卡密发行与专业级财务双向中台。
          </p>
          <ul className="list-disc pl-5 space-y-1.5 text-slate-600">
            <li><strong>公共资产池调度：</strong> 公司集中采购拼多多账号与Token，按套餐对用户进行虚拟切片隔离与加权轮巡分流，单账号达到次数自动锁定下线。</li>
            <li><strong>完全解耦的财务双向审计体系：</strong> 彻底拆分<strong>业务卡密表</strong>、<strong>财务收入流水表</strong>与<strong>资产采购支出表</strong>，支持按年、按季度、按月汇总公司收支与净利润，并支持对指定单用户进行全生命周期消费画像分析。</li>
            <li><strong>严格的 ACID 事务机制：</strong> 卡密核销更新、财务流水落库、用户延期、Redis配额池刷新在同一个本地事务 + 分布式防重锁中原子执行。</li>
            <li><strong>极简接入的高抗逆向通信加密：</strong> 客户端通过标准 SDK 一行代码完成无感知加解密，底层采用动态时间窗口派生密钥 + 字节翻转混淆 + HMAC 验签防重放。</li>
          </ul>
        </div>
      </section>

      {/* 新增章节：三种用户角色与产品决策 (ChatGPT 方案融合) */}
      <section id="sec-roles" className="bg-white border-2 border-purple-500 rounded-xl p-8 shadow-sm">
        <div className="flex items-center justify-between border-b border-purple-100 pb-4 mb-6">
          <div className="flex items-center gap-3">
            <span className="w-8 h-8 rounded-lg bg-purple-600 text-white font-bold flex items-center justify-center text-sm">2</span>
            <div>
              <h2 className="text-xl font-bold text-slate-900">三种用户角色职责范围与 4 大核心业务决策 (ChatGPT 方案融合)</h2>
              <p className="text-xs text-purple-700 font-medium">清晰划分超级管理员/财务、代理商销售与终端客户的数据红线与业务边界</p>
            </div>
          </div>
          <span className="text-xs bg-purple-100 text-purple-800 px-2.5 py-1 rounded font-mono font-bold">RBAC_DECISION_MATRIX</span>
        </div>

        <div className="space-y-6 text-sm text-slate-700">
          <p className="text-slate-600">
            为保证平台资金、拼多多资产以及多渠道代理销售数据的绝对安全，系统构建了清晰的三层角色隔离模型：
          </p>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs">
            <div className="p-4 rounded-xl bg-rose-50/60 border border-rose-200">
              <div className="font-bold text-rose-950 text-sm mb-1">1. 超级管理员 / 财务主管</div>
              <div className="text-rose-700 font-mono text-[11px] mb-2">SUPER_ADMIN / FINANCE</div>
              <p className="text-slate-600 mb-2">
                公司终极资产管辖者。统筹拼多多 Token 账号池采购录入、全平台年/季/月收入支出净利润审计、分配代理商权限。
              </p>
              <div className="text-[11px] text-rose-800 font-medium">★ 红线：Token明文自动加密存储，所有敏感操作强制留痕审计。</div>
            </div>

            <div className="p-4 rounded-xl bg-indigo-50/60 border border-indigo-200">
              <div className="font-bold text-indigo-950 text-sm mb-1">2. 代理商 / 渠道销售管理员</div>
              <div className="text-indigo-700 font-mono text-[11px] mb-2">AGENT / SALES_ADMIN</div>
              <p className="text-slate-600 mb-2">
                负责线下售卡拓展客户。批量生成带个人工号的卡密、录入线下转账凭证号、查看名下客户激活与业绩提成报表。
              </p>
              <div className="text-[11px] text-indigo-800 font-medium">★ 红线：严禁查看公司采购成本/总利润，严禁接触真实底层Token池。</div>
            </div>

            <div className="p-4 rounded-xl bg-emerald-50/60 border border-emerald-200">
              <div className="font-bold text-emerald-950 text-sm mb-1">3. 终端客户 (End User)</div>
              <div className="text-emerald-700 font-mono text-[11px] mb-2">END_USER (CLIENT)</div>
              <p className="text-slate-600 mb-2">
                客户端实际使用者。真实手机号短信注册获1天试用，输入卡密一键原子核销，享受底层多账号轮巡与故障自愈调度。
              </p>
              <div className="text-[11px] text-emerald-800 font-medium">★ 红线：配额耗尽/过期强制403熔断退出，多设备同时登录自动互踢。</div>
            </div>
          </div>
        </div>
      </section>

      {/* 第二章 */}
      <section id="sec-2" className="bg-white border border-slate-200 rounded-xl p-8 shadow-sm">
        <div className="flex items-center justify-between border-b border-slate-100 pb-4 mb-6">
          <div className="flex items-center gap-3">
            <span className="w-8 h-8 rounded-lg bg-indigo-50 text-indigo-600 font-bold flex items-center justify-center text-sm">2</span>
            <h2 className="text-xl font-bold text-slate-900">手机号真实性注册与1天试用机制</h2>
          </div>
          <span className="text-xs bg-emerald-50 text-emerald-700 px-2.5 py-1 rounded font-mono border border-emerald-200">USER_AUTH_TRIAL</span>
        </div>

        <div className="space-y-4 text-sm text-slate-700">
          <h4 className="font-semibold text-slate-900 text-base">2.1 业务规则与资产沉淀逻辑</h4>
          <ol className="list-decimal pl-5 space-y-2 text-slate-600">
            <li>
              <strong>真实手机号强制短信校验：</strong> 客户端注册必须发送 SMS 验证码（阿里云/腾讯云）。单IP每小时限发5条，单手机号每日限发10条，5分钟有效。
            </li>
            <li>
              <strong>手机号资产沉淀：</strong> 注册成功即写入 MySQL 用户主表 <code>pdk_user</code>，打上注册渠道、设备Mac/UUID指纹、首次注册时间。手机号归属公司私域资产池。
            </li>
            <li>
              <strong>1天免费试用派发规则（有且仅有这一种情况）：</strong> 注册后立即写入试用配额：
              <div className="mt-2 bg-slate-900 text-slate-200 p-3 rounded-lg font-mono text-xs overflow-x-auto">
                {`试用规则模板（固定标准）：
- 试用有效期: 注册时间 + 24小时 (精准到秒, 例: 2026-08-15 14:30:00 -> 2026-08-16 14:30:00)
- 试用分配账号数 (x_trial): 1 个试用公共Token
- 试用单账号调用上限 (y_trial): 20 次
- 试用总配额: 1 * 20 = 20 次 (有且仅有这一种标准配置)
- 状态: TRIAL (试用中)`}
              </div>
            </li>
          </ol>
        </div>
      </section>

      {/* 第三章 */}
      <section id="sec-3" className="bg-white border border-slate-200 rounded-xl p-8 shadow-sm">
        <div className="flex items-center justify-between border-b border-slate-100 pb-4 mb-6">
          <div className="flex items-center gap-3">
            <span className="w-8 h-8 rounded-lg bg-indigo-50 text-indigo-600 font-bold flex items-center justify-center text-sm">3</span>
            <h2 className="text-xl font-bold text-slate-900">拼多多公共Token资产池调度与精细配额</h2>
          </div>
          <span className="text-xs bg-indigo-50 text-indigo-700 px-2.5 py-1 rounded font-mono border border-indigo-200">TOKEN_POOL_SCHEDULER</span>
        </div>

        <div className="space-y-4 text-sm text-slate-700">
          <h4 className="font-semibold text-slate-900 text-base">3.1 资产池数学模型：X个账号 × Y次/账号 = X*Y 总调用量</h4>
          <p className="text-slate-600">
            公司拥有拼多多公共账号池。通过<strong>用户私有虚拟池绑定 + 轮巡（Round-Robin）调度</strong>，避免单Token被拼多多频控限流。
          </p>

          <div className="bg-emerald-50 border border-emerald-200 rounded-lg p-4">
            <h5 className="text-xs font-semibold text-emerald-900 flex items-center gap-1.5 mb-1">
              <Check className="w-4 h-4 text-emerald-600" />
              【细化例子 2】200元月卡套餐 (X=4个账号, Y=250次/账号) 实际调度全过程
            </h5>
            <div className="text-xs text-emerald-900 space-y-1.5">
              <p><strong>1. 用户购买：</strong> 客户李四支付200元，激活后分配 <code>X=4</code> 个账号，每个账号 <code>Y=250</code> 次，总理论调用量 <code>1000次</code>。</p>
              <p><strong>2. 账号池绑定：</strong> 锁定4个Token：<code>[PDD_001, PDD_002, PDD_003, PDD_004]</code>，在Redis创建 Hash 表记录各账号已用次数。</p>
              <p><strong>3. 轮巡调度：</strong> 第1次分配 PDD_001，第2次分配 PDD_002，第3次分配 PDD_003，第4次分配 PDD_004，第5次回到 PDD_001 均匀循环。</p>
              <p><strong>4. 单项锁定：</strong> PDD_001 达到 250 次上限后，系统立即锁定下线。李四可用账号变为 <code>[PDD_002, PDD_003, PDD_004]</code>，继续在剩下3个账号间轮巡，对外完全透明！</p>
              <p><strong>5. 全量耗尽提示：</strong> 4个账号全部达到 250 次（累计 1000 次），系统返回 <code>CODE=40301 (QUOTA_EXHAUSTED)</code>，阻断交互并提示充值。</p>
            </div>
          </div>
        </div>
      </section>

      {/* 第四章 (重点更新：物理拆表架构) */}
      <section id="sec-4" className="bg-white border-2 border-emerald-500 rounded-xl p-8 shadow-sm">
        <div className="flex items-center justify-between border-b border-emerald-100 pb-4 mb-6">
          <div className="flex items-center gap-3">
            <span className="w-8 h-8 rounded-lg bg-emerald-600 text-white font-bold flex items-center justify-center text-sm">4</span>
            <div>
              <h2 className="text-xl font-bold text-slate-900">财务双向独立表设计（收入审计流水表 + 资产采购支出表）</h2>
              <p className="text-xs text-emerald-700 font-medium">卡密表与财务表彻底物理拆分 · 支持年/季/月公司汇总与单用户精准审计</p>
            </div>
          </div>
          <span className="text-xs bg-emerald-100 text-emerald-800 px-2.5 py-1 rounded font-mono font-bold">FINANCIAL_SPLIT_SCHEMA</span>
        </div>

        <div className="space-y-6 text-sm text-slate-700">
          <p className="text-slate-600">
            针对之前“卡密与财务混在一起”的问题，现已在架构上<strong>彻底拆分为 2 张独立的物理数据库表</strong>，并配合第 3 张资产支出表形成完整闭环：
          </p>

          {/* 表关系流转图 */}
          <div className="bg-slate-900 text-slate-100 rounded-xl p-5 font-mono text-xs overflow-x-auto">
            <div className="text-emerald-400 font-bold mb-3">// 业务卡密表与财务审计表的物理分离与流转时序</div>
            <pre className="text-slate-300 leading-5">
{`[客户线下转账] ---> [管理员后台制卡]
                          |
                          v
         【表 1: pdk_card_key (业务卡密表)】
         - 字段: id, card_key, package_id, face_value, status(UNUSED), generated_at
         - 职责: 仅作为授权凭据，不承担财务审计职责
                          |
                          v (客户在客户端输入卡密点击激活)
         【Spring 数据库事务中触发原子核销】
         1. pdk_card_key 状态置为 ACTIVATED
         2. pdk_user 增加到期时间与配额
         3. 自动生成并插入一条独立的财务收入记录:
                          |
                          v
         【表 2: pdk_financial_income (财务收入审计流水表)】
         - 字段: id, income_no, card_key_id, card_key, user_phone, amount, payment_channel, payment_txn_no, activated_at, audit_admin
         - 职责: 承载财务收入审计、年/季/月度营收报表、单用户终身价值分析
                          |
                          +-------------------------------+
                                                          | (与支出表对冲)
                                                          v
                                        【表 3: pdk_company_expense (公司资产采购支出表)】
                                        - 字段: id, expense_no, expense_type, amount, token_count_added, supplier_channel, expense_date, remark
                                        - 职责: 记录购买补充公共Token资产的一次性支出与运营成本
                                                          |
                                                          v
                                        【公司财务大看板: 净利润 = 收入流水总和 - 资产支出总和】`}
            </pre>
          </div>

          {/* 多维统计功能详解 */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="p-4 bg-slate-50 border border-slate-200 rounded-lg space-y-2">
              <h4 className="font-bold text-slate-900 text-xs flex items-center gap-1.5">
                <FileSpreadsheet className="w-4 h-4 text-emerald-600" />
                1. 公司级年/季/月多周期统计
              </h4>
              <ul className="text-xs text-slate-600 space-y-1 list-disc pl-4">
                <li><strong>年度报表：</strong> 聚合统计某一年（如 2026年）总收入、总制卡数、总激活张数、总采购支出与年度净利润；</li>
                <li><strong>季度报表：</strong> 按照 Q1 / Q2 / Q3 / Q4 季度维度展现收入环比增长与资产投入产出比；</li>
                <li><strong>月度报表：</strong> 细化到每个自然月（如 2026-08），对比当月开卡实收金额 vs 当月补充购买Token的总支出。</li>
              </ul>
            </div>

            <div className="p-4 bg-slate-50 border border-slate-200 rounded-lg space-y-2">
              <h4 className="font-bold text-slate-900 text-xs flex items-center gap-1.5">
                <TrendingUp className="w-4 h-4 text-indigo-600" />
                2. 针对指定单用户的财务穿透分析 (User LTV)
              </h4>
              <ul className="text-xs text-slate-600 space-y-1 list-disc pl-4">
                <li>输入任一客户手机号（如 <code>13800138000</code>），从 <code>pdk_financial_income</code> 检索出该用户历史所有打款流水；</li>
                <li>统计该用户<strong>累计付费总金额、开卡频次、平均复购周期、首次与最近付款时间</strong>，建立高净值客户资产档案。</li>
              </ul>
            </div>
          </div>

          {/* 细化数据报表示例 */}
          <div className="bg-emerald-50/60 border border-emerald-200 rounded-lg p-4">
            <h5 className="text-xs font-semibold text-emerald-950 flex items-center gap-1.5 mb-2">
              <Check className="w-4 h-4 text-emerald-600" />
              【细化例子 3】公司月度综合财务对账报表 (收入流水 + 资产支出)
            </h5>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-[11px] bg-white border border-emerald-200 rounded">
                <thead className="bg-emerald-100/70 text-emerald-900">
                  <tr>
                    <th className="p-2 border-b">统计月份</th>
                    <th className="p-2 border-b">核销激活开卡数</th>
                    <th className="p-2 border-b">实收收入 (Income流水表)</th>
                    <th className="p-2 border-b">资产采购支出 (Expense支出表)</th>
                    <th className="p-2 border-b">月度净利润</th>
                    <th className="p-2 border-b">资产补充支出备注</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-emerald-100 text-slate-700">
                  <tr>
                    <td className="p-2 font-mono font-bold">2026-08</td>
                    <td className="p-2 font-mono">142 张</td>
                    <td className="p-2 font-mono font-bold text-emerald-600">¥28,400.00</td>
                    <td className="p-2 font-mono font-bold text-rose-600">¥3,500.00</td>
                    <td className="p-2 font-mono font-bold text-indigo-600">¥24,900.00</td>
                    <td className="p-2 text-slate-600">批量补充采购50个拼多多高权重Token(单价70元)</td>
                  </tr>
                  <tr>
                    <td className="p-2 font-mono font-bold">2026-07</td>
                    <td className="p-2 font-mono">118 张</td>
                    <td className="p-2 font-mono font-bold text-emerald-600">¥23,600.00</td>
                    <td className="p-2 font-mono font-bold text-rose-600">¥2,800.00</td>
                    <td className="p-2 font-mono font-bold text-indigo-600">¥20,800.00</td>
                    <td className="p-2 text-slate-600">采购40个Token资产 + 阿里云ECS网关节点续费</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </section>

      {/* 第五章 (重点更新：事务编排) */}
      <section id="sec-5" className="bg-white border border-slate-200 rounded-xl p-8 shadow-sm border-l-4 border-l-indigo-600">
        <div className="flex items-center justify-between border-b border-slate-100 pb-4 mb-6">
          <div className="flex items-center gap-3">
            <span className="w-8 h-8 rounded-lg bg-indigo-600 text-white font-bold flex items-center justify-center text-sm">5</span>
            <div>
              <h2 className="text-xl font-bold text-slate-900">数据库事务编排：卡密核销+财务入库+配额生效原子性</h2>
              <p className="text-xs text-indigo-700 font-medium">行级悲观锁 + 固定加锁顺序防死锁 + Redis分布式防重幂等锁</p>
            </div>
          </div>
          <span className="text-xs bg-indigo-100 text-indigo-800 px-2.5 py-1 rounded font-mono font-bold">DB_TRANSACTION_ACID</span>
        </div>

        <div className="space-y-4 text-sm text-slate-700">
          <p className="text-slate-600">
            卡密激活同时跨越了 <strong>业务卡密表 (pdk_card_key)</strong>、<strong>用户表 (pdk_user)</strong> 和 <strong>财务收入流水表 (pdk_financial_income)</strong>。必须严格保证事务一致性，任何一步报错必须全部回滚。
          </p>

          <div className="bg-slate-900 text-slate-100 rounded-xl p-4 font-mono text-xs">
            <pre className="overflow-x-auto whitespace-pre leading-5 text-slate-300">
{`@Service
public class CardKeyActivationServiceImpl implements CardKeyActivationService {

    @Autowired
    private PdkCardKeyMapper cardKeyMapper;
    @Autowired
    private PdkFinancialIncomeMapper financialIncomeMapper; // 独立的财务收入Mapper
    @Autowired
    private PdkUserMapper userMapper;
    @Autowired
    private RedisQuotaService redisQuotaService;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public ActivationResultDTO activateCardKey(String phone, String cardKeyInput, String paymentChannel, String txnNo) {
        
        // 1. 防重幂等分布式锁 (5秒TTL，防止用户快速连击)
        String lockKey = "LOCK:ACTIVATE:" + cardKeyInput.trim();
        Boolean lockSuccess = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(5));
        if (Boolean.FALSE.equals(lockSuccess)) {
            throw new BusinessException("正在处理中，请勿重复点击！");
        }

        try {
            // 2. 加行级排他锁查询卡密 (SELECT * FROM pdk_card_key WHERE card_key = ? FOR UPDATE)
            PdkCardKey card = cardKeyMapper.selectForUpdate(cardKeyInput.trim());
            if (card == null) {
                throw new BusinessException("卡密不存在，请核对后重试！");
            }
            if (!"UNUSED".equals(card.getStatus())) {
                throw new BusinessException("该卡密已被激活使用或已作废！");
            }

            // 3. 锁定并查询当前激活用户
            PdkUser user = userMapper.selectByPhoneForUpdate(phone);
            if (user == null) {
                throw new BusinessException("用户不存在，请先注册！");
            }

            // 4. 计算并顺延有效期
            LocalDateTime baseTime = (user.getExpireTime() != null && user.getExpireTime().isAfter(LocalDateTime.now()))
                                     ? user.getExpireTime() : LocalDateTime.now();
            LocalDateTime newExpireTime = baseTime.plusDays(card.getDurationDays());

            // 5. 更新业务卡密表 (pdk_card_key) 状态
            card.setStatus("ACTIVATED");
            card.setBoundUserPhone(phone);
            card.setActivatedAt(LocalDateTime.now());
            cardKeyMapper.updateById(card);

            // 6. 插入独立的财务收入审计流水表 (pdk_financial_income)
            PdkFinancialIncome income = new PdkFinancialIncome();
            income.setIncomeOrderNo("INC_" + System.currentTimeMillis() + "_" + card.getId());
            income.setCardKeyId(card.getId());
            income.setCardKey(card.getCardKey());
            income.setUserPhone(phone);
            income.setPackageId(card.getPackageId());
            income.setAmount(card.getFaceValue());
            income.setPaymentChannel(paymentChannel);
            income.setPaymentTxnNo(txnNo);
            income.setAuditAdmin(card.getGeneratedByAdmin());
            income.setActivatedAt(LocalDateTime.now());
            financialIncomeMapper.insert(income); // 独立写入财务收入表

            // 7. 更新用户累计消费与到期时间
            user.setExpireTime(newExpireTime);
            user.setStatus("ACTIVE");
            user.setTotalSpentAmount(user.getTotalSpentAmount().add(card.getFaceValue()));
            user.setTotalCardsCount(user.getTotalCardsCount() + 1);
            userMapper.updateById(user);

            // 8. 同步在 Redis 原子性注入可用公共账号池与配额
            redisQuotaService.injectQuota(phone, card.getAccountCountX(), card.getCallsPerAccountY());

            return new ActivationResultDTO(true, "激活成功", newExpireTime, card.getAccountCountX() * card.getCallsPerAccountY());
        } finally {
            redisTemplate.delete(lockKey); // 释放锁
        }
    }
}`}
            </pre>
          </div>
        </div>
      </section>

      {/* 第六章 */}
      <section id="sec-6" className="bg-white border border-slate-200 rounded-xl p-8 shadow-sm">
        <div className="flex items-center justify-between border-b border-slate-100 pb-4 mb-6">
          <div className="flex items-center gap-3">
            <span className="w-8 h-8 rounded-lg bg-indigo-50 text-indigo-600 font-bold flex items-center justify-center text-sm">6</span>
            <h2 className="text-xl font-bold text-slate-900">客户端鉴权拦截与客户端强制退出熔断设计</h2>
          </div>
          <span className="text-xs bg-rose-50 text-rose-700 px-2.5 py-1 rounded font-mono border border-rose-200">INTERCEPTOR_FORCE_KILL</span>
        </div>

        <div className="space-y-4 text-sm text-slate-700">
          <p className="text-slate-600">
            当用户使用次数耗尽或授权时间到达时，服务端拦截器在 2ms 内拦截并返回 <code>403 Forbidden (action: FORCE_EXIT)</code>。客户端检测到该信号后：
          </p>
          <ol className="list-decimal pl-5 space-y-1.5 text-slate-600">
            <li>立即冻结所有后台采集/下单线程；</li>
            <li>弹出全屏不可关闭的模态告警窗口（提示“配额已耗尽或套餐已过期，请充值”）；</li>
            <li>倒计时 5 秒后直接调用底层系统 API 强制杀死客户端进程（如 <code>process.exit(0)</code>），彻底阻止任何客户端绕过。</li>
          </ol>
        </div>
      </section>

      {/* 第七章 (重点更新：极简接入 + 深度防逆向) */}
      <section id="sec-7" className="bg-white border border-slate-200 rounded-xl p-8 shadow-sm border-l-4 border-l-blue-600">
        <div className="flex items-center justify-between border-b border-slate-100 pb-4 mb-6">
          <div className="flex items-center gap-3">
            <span className="w-8 h-8 rounded-lg bg-blue-600 text-white font-bold flex items-center justify-center text-sm">7</span>
            <div>
              <h2 className="text-xl font-bold text-slate-900">通信加密方案（客户端极简 SDK 接入 + 深度防逆向抓包）</h2>
              <p className="text-xs text-blue-700 font-medium">满足两大核心诉求：第一深度抗反编译抓包，第二客户端开发一行代码无痛调用</p>
            </div>
          </div>
          <span className="text-xs bg-blue-100 text-blue-800 px-2.5 py-1 rounded font-mono font-bold">SEC_SIMPLE_SDK</span>
        </div>

        <div className="space-y-4 text-sm text-slate-700">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="p-4 rounded-lg bg-slate-50 border border-slate-200">
              <div className="font-bold text-slate-900 text-sm mb-1 flex items-center gap-1.5">
                <Zap className="w-4 h-4 text-amber-500" />
                诉求 1：客户端极其简便 (零学习负担)
              </div>
              <p className="text-xs text-slate-600 mb-2">
                客户端开发者无需关心任何加密数学原理，业务层正常调 API，底层的 PDK SDK 拦截器自动在发包前加密、收包后解密：
              </p>
              <div className="bg-slate-900 p-2.5 rounded font-mono text-[11px] text-emerald-400">
                {`// 客户端业务代码完全透明
const res = await pdkClient.post('/api/v1/dispatch/acquire-token', { 
    action: 'QUERY_ORDER', 
    orderId: '10086' 
});
console.log(res.data); // 自动解密为原生对象`}
              </div>
            </div>

            <div className="p-4 rounded-lg bg-slate-50 border border-slate-200">
              <div className="font-bold text-slate-900 text-sm mb-1 flex items-center gap-1.5">
                <ShieldCheck className="w-4 h-4 text-indigo-600" />
                诉求 2：深度抗逆向抓包 (抓包全乱码)
              </div>
              <ul className="text-xs text-slate-600 space-y-1 list-disc pl-4">
                <li><strong>动态混淆 AES-128-CBC：</strong> 密钥由动态时间窗口混淆派生，每次随机 IV 向量，密文每次不同；</li>
                <li><strong>私有魔数 + 字节翻转 (Byte-Flip XOR)：</strong> 打上 <code>0x50 0x44</code> 魔数并倒序移位，Fiddler/Charles 识别为未知损坏二进制；</li>
                <li><strong>防重放签名：</strong> 携带 <code>Timestamp + Nonce + HMAC-SHA256</code> 签名，抓包重发直接被网关丢弃。</li>
              </ul>
            </div>
          </div>
          <div className="mt-4 p-4 rounded-xl bg-indigo-50/70 border border-indigo-200">
            <h4 className="font-bold text-indigo-950 text-sm mb-2 flex items-center gap-2">
              <Key className="w-4 h-4 text-indigo-600" />
              <span>Token 传输安全决策：动态下发短效加密 Token (方案 2 深度防护标准)</span>
            </h4>
            <p className="text-xs text-indigo-900 leading-relaxed mb-2">
              客户端需要直接持有加密分发的拼多多 Token 与官方通信。为了确保 Token 绝对安全，服务端调度网关下发的 Token 载荷采用<strong>三重动态铠甲保护</strong>：
            </p>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3 text-xs">
              <div className="bg-white p-3 rounded-lg border border-indigo-100">
                <span className="font-bold text-slate-900 block mb-1">1. 内存混淆注入</span>
                <span className="text-slate-600">Token 下发后仅在客户端运行时内存持有，绝不写入本地磁盘文件，进程结束立即销毁。</span>
              </div>
              <div className="bg-white p-3 rounded-lg border border-indigo-100">
                <span className="font-bold text-slate-900 block mb-1">2. 传输层全载荷加密</span>
                <span className="text-slate-600">下发报文经动态密钥 AES 加密 + 字节混淆，网络传输抓包无法还原 Token 字符串。</span>
              </div>
              <div className="bg-white p-3 rounded-lg border border-indigo-100">
                <span className="font-bold text-slate-900 block mb-1">3. 短效租约与动态置换</span>
                <span className="text-slate-600">下发 Token 绑定槽位与请求租约，达到单账号调用限额 Y 或发生异常时，网关立即废止并置换新 Token。</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section id="sec-8" className="bg-white border border-slate-200 rounded-xl p-8 shadow-sm">
        <div className="flex items-center justify-between border-b border-slate-100 pb-4 mb-6">
          <div className="flex items-center gap-3">
            <span className="w-8 h-8 rounded-lg bg-indigo-50 text-indigo-600 font-bold flex items-center justify-center text-sm">8</span>
            <h2 className="text-xl font-bold text-slate-900">MySQL 数据库核心物理表结构设计 (完全独立拆分版 DDL)</h2>
          </div>
          <span className="text-xs bg-emerald-100 text-emerald-800 px-2.5 py-1 rounded font-mono font-bold">DATABASE_SCHEMA_V1.2</span>
        </div>

        <div className="space-y-4 text-xs font-mono">
          {/* 表 1: 用户表 */}
          <div className="border border-slate-200 rounded-lg overflow-hidden">
            <div className="bg-slate-100 px-4 py-2 font-bold text-slate-800 flex justify-between">
              <span>1. pdk_user (用户主表 - 手机号资产库)</span>
              <span className="text-slate-500 font-normal">记录用户主体与终身消费金额</span>
            </div>
            <pre className="p-3 bg-slate-900 text-slate-200 overflow-x-auto">{`CREATE TABLE \`pdk_user\` (
  \`id\` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  \`phone\` VARCHAR(20) NOT NULL UNIQUE COMMENT '真实手机号(核心资产)',
  \`password_hash\` VARCHAR(128) NOT NULL COMMENT '密码哈希',
  \`status\` ENUM('TRIAL','ACTIVE','EXPIRED','BLOCKED') DEFAULT 'TRIAL' COMMENT '账号状态',
  \`trial_end_time\` DATETIME NULL COMMENT '试用结束时间 (注册+24h)',
  \`expire_time\` DATETIME NOT NULL COMMENT '当前套餐最终到期时间',
  \`device_fingerprint\` VARCHAR(64) NULL COMMENT '首次绑定的客户端设备特征码',
  \`total_spent_amount\` DECIMAL(10,2) DEFAULT 0.00 COMMENT '该用户累计充值总金额(元)',
  \`total_cards_count\` INT DEFAULT 0 COMMENT '该用户累计激活卡密张数',
  \`created_at\` DATETIME DEFAULT CURRENT_TIMESTAMP,
  \`updated_at\` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX \`idx_phone\` (\`phone\`),
  INDEX \`idx_status_expire\` (\`status\`, \`expire_time\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户主体表';`}</pre>
          </div>

          {/* 表 2: 独立的业务卡密凭证表 */}
          <div className="border-2 border-blue-400 rounded-lg overflow-hidden">
            <div className="bg-blue-50 px-4 py-2 font-bold text-blue-950 flex justify-between">
              <span>2. pdk_card_key (业务卡密凭证物理表 - 纯授权凭据)</span>
              <span className="text-blue-700 font-normal">★与财务表完全解耦</span>
            </div>
            <pre className="p-3 bg-slate-900 text-slate-200 overflow-x-auto">{`CREATE TABLE \`pdk_card_key\` (
  \`id\` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  \`card_key\` VARCHAR(64) NOT NULL UNIQUE COMMENT '卡密串(PDK-XXXX-XXXX-XXXX)',
  \`package_id\` INT UNSIGNED NOT NULL COMMENT '关联套餐ID',
  \`package_name\` VARCHAR(64) NOT NULL COMMENT '套餐名称快照(如: 200元月卡标准版)',
  \`face_value\` DECIMAL(10,2) NOT NULL COMMENT '卡密面额定价(元)',
  \`duration_days\` INT NOT NULL DEFAULT 30 COMMENT '有效天数',
  \`account_count_x\` INT NOT NULL DEFAULT 1 COMMENT '分配公共账号数量 X',
  \`calls_per_account_y\` INT NOT NULL DEFAULT 200 COMMENT '单账号使用次数上限 Y',
  \`status\` ENUM('UNUSED','ACTIVATED','VOIDED') DEFAULT 'UNUSED' COMMENT '状态: UNUSED-未核销, ACTIVATED-已核销, VOIDED-作废',
  \`generated_by_admin\` VARCHAR(32) NOT NULL COMMENT '制卡操作管理员',
  \`bound_user_phone\` VARCHAR(20) NULL COMMENT '核销激活的客户手机号',
  \`activated_at\` DATETIME NULL COMMENT '激活核销时间',
  \`created_at\` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX \`idx_card_key\` (\`card_key\`),
  INDEX \`idx_status\` (\`status\`),
  INDEX \`idx_bound_phone\` (\`bound_user_phone\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务卡密凭证表(纯授权)';`}</pre>
          </div>

          {/* 表 3: 独立的财务收入审计流水表 */}
          <div className="border-2 border-emerald-400 rounded-lg overflow-hidden">
            <div className="bg-emerald-50 px-4 py-2 font-bold text-emerald-950 flex justify-between">
              <span>3. pdk_financial_income (财务收入审计流水表 - 独立收入端)</span>
              <span className="text-emerald-700 font-normal">★支持年/季/月公司汇总与单用户精准审计</span>
            </div>
            <pre className="p-3 bg-slate-900 text-slate-200 overflow-x-auto">{`CREATE TABLE \`pdk_financial_income\` (
  \`id\` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  \`income_order_no\` VARCHAR(64) NOT NULL UNIQUE COMMENT '财务收入单号 (INC_20260815_0001)',
  \`card_key_id\` BIGINT UNSIGNED NOT NULL COMMENT '关联的卡密ID',
  \`card_key\` VARCHAR(64) NOT NULL COMMENT '核销的卡密号快照',
  \`user_phone\` VARCHAR(20) NOT NULL COMMENT '付款客户真实手机号 (核心关联)',
  \`package_id\` INT UNSIGNED NOT NULL COMMENT '购买套餐ID',
  \`face_value\` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '套餐标价面额 (元)',
  \`amount\` DECIMAL(10,2) NOT NULL COMMENT '实际到账收款金额 (元, 赠送为0)',
  \`discount_amount\` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '优惠减免/折价金额 (元)',
  \`order_type\` ENUM('NORMAL_SALE','DISCOUNT_SALE','GIFT_FREE') DEFAULT 'NORMAL_SALE' COMMENT '订单性质: 正常售卖, 折扣销售, 商务赠送',
  \`payment_channel\` ENUM('BANK_TRANSFER','ALIPAY','WECHAT_PAY','OTHER') NOT NULL COMMENT '线下打款渠道',
  \`payment_txn_no\` VARCHAR(64) NULL COMMENT '线下银行/支付宝转账流水凭证号',
  \`audit_admin\` VARCHAR(32) NOT NULL COMMENT '对账核销经办管理员',
  \`activated_at\` DATETIME NOT NULL COMMENT '核销到账时间 (用于按年/季/月统计)',
  \`audit_remark\` VARCHAR(255) NULL COMMENT '财务备注',
  \`created_at\` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX \`idx_income_order_no\` (\`income_order_no\`),
  INDEX \`idx_user_phone\` (\`user_phone\`),
  INDEX \`idx_activated_at\` (\`activated_at\`),
  INDEX \`idx_payment_channel\` (\`payment_channel\`),
  INDEX \`idx_order_type\` (\`order_type\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='财务收入审计流水表(独立对账)';`}</pre>
          </div>

          {/* 表 4: 独立的公司资产采购与支出表 */}
          <div className="border-2 border-rose-400 rounded-lg overflow-hidden">
            <div className="bg-rose-50 px-4 py-2 font-bold text-rose-950 flex justify-between">
              <span>4. pdk_company_expense (公司资产采购与支出表 - 独立支出端)</span>
              <span className="text-rose-700 font-normal">★记录购买拼多多账号/Token及运营支出</span>
            </div>
            <pre className="p-3 bg-slate-900 text-slate-200 overflow-x-auto">{`CREATE TABLE \`pdk_company_expense\` (
  \`id\` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  \`expense_no\` VARCHAR(64) NOT NULL UNIQUE COMMENT '支出流水单号 (EXP_20260815_001)',
  \`expense_type\` ENUM('ASSET_TOKEN_PURCHASE','TOKEN_SUPPLEMENT','SERVER_BANDWIDTH','OTHER') NOT NULL COMMENT '支出类型',
  \`amount\` DECIMAL(10,2) NOT NULL COMMENT '支出总金额(元)',
  \`token_count_added\` INT DEFAULT 0 COMMENT '本次补充采购的公共账号/Token数量',
  \`unit_price\` DECIMAL(10,2) DEFAULT 0.00 COMMENT '单账号采购单价(元/个)',
  \`supplier_channel\` VARCHAR(64) NULL COMMENT '采购商户/渠道来源',
  \`recorded_by_admin\` VARCHAR(32) NOT NULL COMMENT '录入经办管理员',
  \`expense_date\` DATE NOT NULL COMMENT '支出记账日期 (用于按年/季/月支出汇总)',
  \`remark\` VARCHAR(255) NOT NULL COMMENT '详细备注(如: 补充采购50个高权重Token)',
  \`created_at\` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX \`idx_expense_date\` (\`expense_date\`),
  INDEX \`idx_expense_type\` (\`expense_type\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公司资产采购与支出表';`}</pre>
          </div>

          {/* 表 5: 拼多多账号资产池 */}
          <div className="border border-slate-200 rounded-lg overflow-hidden">
            <div className="bg-slate-100 px-4 py-2 font-bold text-slate-800 flex justify-between">
              <span>5. pdk_pdd_account_pool (拼多多公共Token资产池)</span>
              <span className="text-slate-500 font-normal">调度与健康度监控池</span>
            </div>
            <pre className="p-3 bg-slate-900 text-slate-200 overflow-x-auto">{`CREATE TABLE \`pdk_pdd_account_pool\` (
  \`id\` INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  \`pdd_account_id\` VARCHAR(64) NOT NULL UNIQUE COMMENT '拼多多账号唯一ID',
  \`nickname\` VARCHAR(64) NULL COMMENT '账号别名',
  \`access_token\` TEXT NOT NULL COMMENT '拼多多Token(AES加密存储)',
  \`refresh_token\` TEXT NULL COMMENT '刷新凭据',
  \`token_expire_time\` DATETIME NOT NULL COMMENT 'Token官方到期时间',
  \`status\` ENUM('HEALTHY','WARNING','INVALID','LOCKED') DEFAULT 'HEALTHY' COMMENT '健康状态',
  \`total_served_calls\` BIGINT DEFAULT 0 COMMENT '历史累计总调度次数',
  \`last_checked_at\` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX \`idx_status\` (\`status\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拼多多Token资产池';`}</pre>
          </div>

          {/* 表 6: 套餐配置模板表 */}
          <div className="border border-slate-200 rounded-lg overflow-hidden">
            <div className="bg-slate-100 px-4 py-2 font-bold text-slate-800 flex justify-between">
              <span>6. pdk_package_template (套餐模板配置表)</span>
              <span className="text-slate-500 font-normal">定义 X*Y 套餐规格与定价</span>
            </div>
            <pre className="p-3 bg-slate-900 text-slate-200 overflow-x-auto">{`CREATE TABLE \`pdk_package_template\` (
  \`id\` INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  \`name\` VARCHAR(64) NOT NULL COMMENT '套餐名称 (如: 标准月卡)',
  \`price\` DECIMAL(10,2) NOT NULL COMMENT '标准售价 (元)',
  \`duration_days\` INT NOT NULL DEFAULT 30 COMMENT '有效期 (天)',
  \`account_count_x\` INT NOT NULL DEFAULT 10 COMMENT '分配逻辑账号槽位数 X',
  \`calls_per_account_y\` INT NOT NULL DEFAULT 30 COMMENT '单账号槽位调用上限 Y',
  \`total_calls\` INT GENERATED ALWAYS AS (\`account_count_x\` * \`calls_per_account_y\`) STORED COMMENT '总配额 X*Y',
  \`is_trial\` TINYINT(1) DEFAULT 0 COMMENT '是否为新用户试用套餐 (0-否, 1-是)',
  \`is_active\` TINYINT(1) DEFAULT 1 COMMENT '是否上架销售 (0-下架, 1-上架)',
  \`created_at\` DATETIME DEFAULT CURRENT_TIMESTAMP,
  \`updated_at\` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='套餐模板配置表';`}</pre>
          </div>

          {/* 表 7: 业务调用与扣费日志表 */}
          <div className="border border-slate-200 rounded-lg overflow-hidden">
            <div className="bg-slate-100 px-4 py-2 font-bold text-slate-800 flex justify-between">
              <span>7. pdk_dispatch_log (业务调度与扣费流水表 - 不可物理删除)</span>
              <span className="text-slate-500 font-normal">记录每一次调度的槽位、真实账号与扣费结果</span>
            </div>
            <pre className="p-3 bg-slate-900 text-slate-200 overflow-x-auto">{`CREATE TABLE \`pdk_dispatch_log\` (
  \`id\` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  \`req_uuid\` VARCHAR(64) NOT NULL UNIQUE COMMENT '客户端请求幂等唯一UUID (防重试重复扣费)',
  \`user_phone\` VARCHAR(20) NOT NULL COMMENT '调用用户手机号',
  \`slot_index\` INT NOT NULL COMMENT '消耗的逻辑账号槽位 (1~X)',
  \`real_pdd_account_id\` VARCHAR(64) NOT NULL COMMENT '实际承载调度的底层公司账号ID',
  \`action_type\` VARCHAR(64) NOT NULL COMMENT '业务操作类型 (如: QUERY_ORDER, GET_GOODS)',
  \`deduct_count\` TINYINT NOT NULL DEFAULT 1 COMMENT '本次扣减次数 (成功扣1, 账号异常/参数错扣0)',
  \`exec_status\` ENUM('SUCCESS','TOKEN_FAIL','PARAM_ERROR','NET_TIMEOUT') NOT NULL COMMENT '执行状态',
  \`response_time_ms\` INT NOT NULL COMMENT '网关处理耗时 (ms)',
  \`created_at\` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX \`idx_user_time\` (\`user_phone\`, \`created_at\`),
  INDEX \`idx_account_stat\` (\`real_pdd_account_id\`, \`exec_status\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务调度与扣费明细表';`}</pre>
          </div>

          {/* 表 8: 管理员不可逆审计操作日志表 */}
          <div className="border-2 border-amber-400 rounded-lg overflow-hidden">
            <div className="bg-amber-50 px-4 py-2 font-bold text-amber-950 flex justify-between">
              <span>8. pdk_admin_audit_log (管理员核心操作不可逆审计日志表)</span>
              <span className="text-amber-700 font-normal">★所有调额、作废、封禁必须永久留痕</span>
            </div>
            <pre className="p-3 bg-slate-900 text-slate-200 overflow-x-auto">{`CREATE TABLE \`pdk_admin_audit_log\` (
  \`id\` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  \`admin_name\` VARCHAR(32) NOT NULL COMMENT '操作管理员账号',
  \`admin_role\` ENUM('SUPER_ADMIN','FINANCE','AGENT') NOT NULL COMMENT '管理员角色',
  \`action_type\` VARCHAR(64) NOT NULL COMMENT '操作类型 (MANUAL_ADJUST_QUOTA, EXTEND_EXPIRE, VOID_CARD, BLOCK_USER)',
  \`target_type\` VARCHAR(32) NOT NULL COMMENT '目标对象类型 (USER, CARD, ACCOUNT, PACKAGE)',
  \`target_id\` VARCHAR(64) NOT NULL COMMENT '目标对象标识 (如手机号或卡密码)',
  \`before_state\` JSON NULL COMMENT '修改前状态快照',
  \`after_state\` JSON NULL COMMENT '修改后状态快照',
  \`reason\` VARCHAR(255) NOT NULL COMMENT '人工操作必须填写的原因备注',
  \`ip_address\` VARCHAR(45) NOT NULL COMMENT '操作人客户端IP',
  \`created_at\` DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX \`idx_admin_created\` (\`admin_name\`, \`created_at\`),
  INDEX \`idx_target\` (\`target_type\`, \`target_id\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员不可逆审计操作日志表';`}</pre>
          </div>
        </div>
      </section>

      {/* 第九章: 10 条产品铁律 */}
      <section id="sec-rules" className="bg-white border border-slate-200 rounded-xl p-8 shadow-sm">
        <div className="flex items-center justify-between border-b border-slate-100 pb-4 mb-6">
          <div className="flex items-center gap-3">
            <span className="w-8 h-8 rounded-lg bg-indigo-50 text-indigo-600 font-bold flex items-center justify-center text-sm">9</span>
            <h2 className="text-xl font-bold text-slate-900">产品设计 10 大不可突破铁律</h2>
          </div>
          <span className="text-xs bg-rose-100 text-rose-800 px-2.5 py-1 rounded font-mono font-bold">10_GOLDEN_RULES</span>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
          <div className="p-3.5 bg-slate-50 border border-slate-200 rounded-lg space-y-1">
            <span className="font-bold text-slate-900">【铁律 1】短信真实性验证</span>
            <p className="text-slate-600">手机号注册必须经过真实短信验证码校验，阻断虚假注册。</p>
          </div>
          <div className="p-3.5 bg-slate-50 border border-slate-200 rounded-lg space-y-1">
            <span className="font-bold text-slate-900">【铁律 2】试用仅限一次</span>
            <p className="text-slate-600">每个手机号原则上只能享受一次新用户 1 天免费试用，过期不可重复领取。</p>
          </div>
          <div className="p-3.5 bg-slate-50 border border-slate-200 rounded-lg space-y-1">
            <span className="font-bold text-slate-900">【铁律 3】鉴权服务端裁决</span>
            <p className="text-slate-600">用户是否有资格使用，完全由服务端网关裁决，绝不可相信客户端本地时间与判断。</p>
          </div>
          <div className="p-3.5 bg-slate-50 border border-slate-200 rounded-lg space-y-1">
            <span className="font-bold text-slate-900">【铁律 4】双条件严格有效性</span>
            <p className="text-slate-600">必须同时满足【未过期】AND【剩余次数 &gt; 0】，任一条件不满足立即拒绝核心业务。</p>
          </div>
          <div className="p-3.5 bg-slate-50 border border-slate-200 rounded-lg space-y-1">
            <span className="font-bold text-slate-900">【铁律 5】X*Y 额度严格守护</span>
            <p className="text-slate-600">购买 X 个账号 × Y 次，必须严格保证最大理论权益 X*Y，不多扣、不超扣、不负数。</p>
          </div>
          <div className="p-3.5 bg-slate-50 border border-slate-200 rounded-lg space-y-1">
            <span className="font-bold text-slate-900">【铁律 6】官方异常用户免责</span>
            <p className="text-slate-600">公司底层账号或 Token 异常，系统自动替换新账号，绝不消耗用户套餐次数。</p>
          </div>
          <div className="p-3.5 bg-slate-50 border border-slate-200 rounded-lg space-y-1">
            <span className="font-bold text-slate-900">【铁律 7】网络重试防重扣</span>
            <p className="text-slate-600">基于请求 UUID 幂等校验，同一个业务请求即使客户端网络超时重试，也绝不多扣费。</p>
          </div>
          <div className="p-3.5 bg-slate-50 border border-slate-200 rounded-lg space-y-1">
            <span className="font-bold text-slate-900">【铁律 8】全链路全盘可审计</span>
            <p className="text-slate-600">钱、套餐、卡密、用户、消费流水五者相互关联穿透，随时可追溯对账。</p>
          </div>
          <div className="p-3.5 bg-slate-50 border border-slate-200 rounded-lg space-y-1">
            <span className="font-bold text-slate-900">【铁律 9】人工调额强制留痕</span>
            <p className="text-slate-600">后台人工补偿、调额、延期、作废，必须强制记录管理员账号、原因并永久落库。</p>
          </div>
          <div className="p-3.5 bg-slate-50 border border-slate-200 rounded-lg space-y-1">
            <span className="font-bold text-slate-900">【铁律 10】数据物理不删除</span>
            <p className="text-slate-600">消费记录、销售财务流水、管理员日志全部采用逻辑状态，严禁物理删除。</p>
          </div>
        </div>
      </section>
    </div>
  );
};
