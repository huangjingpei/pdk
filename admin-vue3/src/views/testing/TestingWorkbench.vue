<template>
  <div class="space-y-6">
    <div>
      <h2 class="text-xl font-bold text-slate-800">全链路真实测试工作台 (8 大场景)</h2>
      <p class="text-xs text-slate-500 mt-1">
        本页所有场景均<strong>真实调用后端接口</strong>（非 Mock）：客户端会话/卡密核销/AES-128-GCM 加密调度/单设备互踢/免责自愈上报。
        下方「客户端会话」登录后，其余场景会携带真实客户端 token 与 X-PDK-Phone / X-PDK-Device-ID 头执行。
      </p>
    </div>

    <!-- 客户端会话（供场景 2~8 复用） -->
    <el-card shadow="never" class="border-slate-200">
      <template #header>
        <div class="flex justify-between items-center">
          <span class="font-semibold text-sm text-slate-800">客户端会话（场景 1 注册 / 场景 2 登录后自动填充）</span>
          <el-tag v-if="clientSession.tokenValue" size="small" type="success">已就绪：{{ clientSession.phone }} @ {{ clientSession.deviceId }}</el-tag>
          <el-tag v-else size="small" type="info">未登录</el-tag>
        </div>
      </template>
      <el-descriptions :column="4" size="small" border v-if="clientSession.tokenValue">
        <el-descriptions-item label="手机号">{{ clientSession.phone }}</el-descriptions-item>
        <el-descriptions-item label="设备ID">{{ clientSession.deviceId }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ clientSession.status }}</el-descriptions-item>
        <el-descriptions-item label="剩余次数">{{ clientSession.remainingCalls }}</el-descriptions-item>
      </el-descriptions>
      <el-alert v-else class="!mt-0" type="warning" :closable="false" title="请先执行场景 1 或场景 2 拿到客户端会话，否则涉及客户端鉴权的场景会返回 40100" />
    </el-card>

    <el-row :gutter="16">
      <!-- 场景 1: 客户端注册（试用资源） -->
      <el-col :span="12">
        <el-card shadow="hover" class="border-slate-200 h-full">
          <template #header>
            <div class="flex justify-between items-center">
              <span class="font-semibold text-sm text-slate-800">场景 1：客户端注册（试用资源分配）</span>
              <el-tag size="small" type="primary">客户端</el-tag>
            </div>
          </template>
          <div class="text-xs text-slate-500 mb-2">自动发送验证码（debug 模式直接回显），再完成注册，返回试用权益与客户端 token。</div>
          <el-form label-width="92px" size="small">
            <el-form-item label="手机号"><el-input v-model="reg.phone" placeholder="13800138000" /></el-form-item>
            <el-form-item label="设备ID"><el-input v-model="reg.deviceId" placeholder="MAC-00-1B-44-11-3A-B7" /></el-form-item>
            <el-form-item label="密码"><el-input v-model="reg.password" placeholder="登录密码" /></el-form-item>
            <el-form-item label="邀请码"><el-input v-model="reg.invitationCode" placeholder="可选" /></el-form-item>
            <el-button type="primary" class="w-full" :loading="reg.loading" @click="runRegister">注册并领取试用</el-button>
          </el-form>
          <expect-result v-if="reg.result" :res="reg.result" :expected="'code=200，返回 tokenName/tokenValue，status=TRIAL，remainingCalls>0'" :expect-code="200" />
        </el-card>
      </el-col>

      <!-- 场景 2: 客户端登录（设备绑定） -->
      <el-col :span="12">
        <el-card shadow="hover" class="border-slate-200 h-full">
          <template #header>
            <div class="flex justify-between items-center">
              <span class="font-semibold text-sm text-slate-800">场景 2：客户端登录（设备绑定）</span>
              <el-tag size="small" type="primary">客户端</el-tag>
            </div>
          </template>
          <div class="text-xs text-slate-500 mb-2">登录成功即绑定当前设备；后续设备不一致将触发互踢（场景 5）。</div>
          <el-form label-width="92px" size="small">
            <el-form-item label="手机号"><el-input v-model="login.phone" placeholder="13800138000" /></el-form-item>
            <el-form-item label="设备ID"><el-input v-model="login.deviceId" placeholder="MAC-00-1B-44-11-3A-B7" /></el-form-item>
            <el-form-item label="密码"><el-input v-model="login.password" placeholder="登录密码" /></el-form-item>
            <el-button type="primary" class="w-full" :loading="login.loading" @click="runLogin">登录并绑定设备</el-button>
          </el-form>
          <expect-result v-if="login.result" :res="login.result" :expected="'code=200，返回 tokenName/tokenValue，并绑定 deviceId'" :expect-code="200" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <!-- 场景 3: 卡密核销 -->
      <el-col :span="12">
        <el-card shadow="hover" class="border-slate-200 h-full">
          <template #header>
            <div class="flex justify-between items-center">
              <span class="font-semibold text-sm text-slate-800">场景 3：卡密原子核销（权益到账 + 独立财务）</span>
              <el-tag size="small" type="success">开放接口</el-tag>
            </div>
          </template>
          <div class="text-xs text-slate-500 mb-2">POST /api/v1/card/activate（无需客户端登录）。核销后小号独占绑定、到期顺延、写入独立财务流水。</div>
          <el-form label-width="92px" size="small">
            <el-form-item label="卡密序列号"><el-input v-model="act.cardKey" placeholder="PDK-8891-2041-9982" /></el-form-item>
            <el-form-item label="充值手机号"><el-input v-model="act.userPhone" placeholder="13800138000" /></el-form-item>
            <el-form-item label="设备ID"><el-input v-model="act.deviceId" placeholder="MAC-00-1B-44-11-3A-B7" /></el-form-item>
            <el-form-item label="实收金额"><el-input-number v-model="act.actualAmount" :min="0" :precision="2" style="width: 100%" /></el-form-item>
            <el-button type="success" class="w-full" :loading="act.loading" @click="runActivate">执行核销</el-button>
          </el-form>
          <expect-result v-if="act.result" :res="act.result" :expected="'code=200，返回 packageName/extendedDays/totalAddedCalls/incomeOrderNo；卡密状态变 ACTIVATED'" :expect-code="200" />
        </el-card>
      </el-col>

      <!-- 场景 4: 加密下发 -->
      <el-col :span="12">
        <el-card shadow="hover" class="border-slate-200 h-full">
          <template #header>
            <div class="flex justify-between items-center">
              <span class="font-semibold text-sm text-slate-800">场景 4：短效加密 Token 下发（AES-128-GCM + 字节翻转）</span>
              <el-tag size="small" type="success">安全通道</el-tag>
            </div>
          </template>
          <div class="text-xs text-slate-500 mb-2">需客户端会话。服务端加密下发短效租约，底层小号槽位标记为 BUSY。</div>
          <el-form label-width="92px" size="small">
            <el-form-item label="采集动作">
              <el-select v-model="acq.actionType" style="width: 100%">
                <el-option value="GOODS_COLLECT" label="商品批量采集" />
                <el-option value="ORDER_PULL" label="订单实时拉取" />
                <el-option value="DETAIL_QUERY" label="商品详情查询" />
              </el-select>
            </el-form-item>
            <el-form-item label="商品ID"><el-input v-model="acq.goodsId" placeholder="可选，如 1001" /></el-form-item>
            <el-button type="success" class="w-full" :loading="acq.loading" @click="runAcquire">申请加密 Token</el-button>
          </el-form>
          <expect-result v-if="acq.result" :res="acq.result" :expected="'code=200，返回 encryptedPayload(密文)/leaseTraceId/expireAtTimestamp/remainingUserQuota'" :expect-code="200" />
          <div v-if="lastLease.leaseTraceId" class="mt-2 text-xs text-emerald-600">已缓存 leaseTraceId：<code>{{ lastLease.leaseTraceId }}</code>（场景 6/7 复用）</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <!-- 场景 5: 设备互踢 -->
      <el-col :span="12">
        <el-card shadow="hover" class="border-slate-200 h-full">
          <template #header>
            <span class="font-semibold text-sm text-slate-800">场景 5：单设备互踢拦截（ERR_DEVICE_KICK_OUT）</span>
          </template>
          <div class="text-xs text-slate-500 mb-2">客户端已在设备 A 登录。改用「入侵设备 B」发起请求，网关应拦截并返回 40103。</div>
          <el-form label-width="92px" size="small">
            <el-form-item label="入侵设备ID"><el-input v-model="kick.intruderDevice" placeholder="MAC-00-99-99-99-99-99" /></el-form-item>
            <el-button type="warning" class="w-full" :loading="kick.loading" @click="runKick">模拟异地设备 B 调用</el-button>
          </el-form>
          <expect-result v-if="kick.result" :res="kick.result" :expected="'拦截命中：code=40103，message 含 ERR_DEVICE_KICK_OUT'" :expect-code="40103" />
        </el-card>
      </el-col>

      <!-- 场景 6: 成功上报扣费 -->
      <el-col :span="12">
        <el-card shadow="hover" class="border-slate-200 h-full">
          <template #header>
            <span class="font-semibold text-sm text-slate-800">场景 6：业务成功上报扣费（SUCCESS）</span>
          </template>
          <div class="text-xs text-slate-500 mb-2">用场景 4 返回的 leaseTraceId 上报成功，应扣 1 次并写成功流水，槽位释放为 HEALTHY。</div>
          <el-form label-width="92px" size="small">
            <el-form-item label="leaseTraceId"><el-input v-model="rep.leaseTraceId" :placeholder="lastLease.leaseTraceId || '先用场景4领取'" /></el-form-item>
            <el-button type="success" class="w-full" :loading="rep.loading" @click="runReportSuccess">上报成功并扣费</el-button>
          </el-form>
          <expect-result v-if="rep.result" :res="rep.result" :expected="'code=200，dispatch 流水 deductCount=1、execStatus=SUCCESS；用户剩余次数 -1'" :expect-code="200" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <!-- 场景 7: 故障免责拉黑 -->
      <el-col :span="12">
        <el-card shadow="hover" class="border-slate-200 h-full">
          <template #header>
            <span class="font-semibold text-sm text-slate-800">场景 7：故障免责扣费与自动拉黑（FAIL_ACCOUNT_BANNED）</span>
          </template>
          <div class="text-xs text-slate-500 mb-2">内部先领取一笔新租约，再上报官方账号被封：本次扣 0 次，槽位标记 FAULT_BLACK。</div>
          <el-button type="danger" class="w-full" :loading="fault.loading" @click="runFault">模拟官方账号失效自愈</el-button>
          <expect-result v-if="fault.result" :res="fault.result.report" :expected="'code=200，token 槽位 healthStatus=FAULT_BLACK，用户剩余次数不变，execStatus=TOKEN_FAIL'" :expect-code="200" />
        </el-card>
      </el-col>

      <!-- 场景 8: 解绑设备 -->
      <el-col :span="12">
        <el-card shadow="hover" class="border-slate-200 h-full">
          <template #header>
            <span class="font-semibold text-sm text-slate-800">场景 8：解绑设备（需重新登录）</span>
          </template>
          <div class="text-xs text-slate-500 mb-2">解绑后 user.deviceId 置空并注销客户端会话；旧设备再次请求将触发 40103，直至重新登录。</div>
          <el-button type="warning" class="w-full" :loading="unbind.loading" @click="runUnbind">解绑当前电脑</el-button>
          <expect-result v-if="unbind.result" :res="unbind.result" :expected="'code=200，message 含「解绑」；随后客户端会话失效'" :expect-code="200" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { reactive } from 'vue';
import axios from 'axios';
import ExpectResult from '../../components/ExpectResult.vue';

// 专用于客户端接口的 axios：不挂载 admin 拦截器，手动注入客户端 satoken 与安全头。
const clientHttp = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 15000,
});

interface ApiResp {
  ok: boolean;
  code: number;
  message: string;
  data: any;
}

// 客户端会话（场景 1/2 产出，场景 4~8 复用）
const clientSession = reactive({
  tokenName: '', tokenValue: '', phone: '', deviceId: '', status: '', packageName: '', remainingCalls: 0,
});
const lastLease = reactive({ leaseTraceId: '', encryptedPayload: '' });

function applySession(d: any) {
  clientSession.tokenName = d.tokenName || 'satoken';
  clientSession.tokenValue = d.tokenValue || '';
  clientSession.phone = d.phone || '';
  clientSession.deviceId = d.deviceId || '';
  clientSession.status = d.status || '';
  clientSession.packageName = d.packageName || '';
  clientSession.remainingCalls = d.remainingCalls ?? 0;
}

async function rawPost(url: string, body: any, headers: Record<string, string> = {}): Promise<ApiResp> {
  try {
    const r = await clientHttp.post(url, body, { headers: { 'Content-Type': 'application/json', ...headers } });
    return { ok: true, code: r.data?.code ?? 200, message: r.data?.message ?? 'ok', data: r.data?.data };
  } catch (e: any) {
    const d = e?.response?.data;
    return { ok: false, code: d?.code ?? e?.response?.status ?? -1, message: d?.message ?? e?.message ?? '请求失败', data: d?.data };
  }
}

// 携带客户端会话头的请求（用于受客户端鉴权保护的接口）
async function clientPost(url: string, body: any, deviceOverride?: string): Promise<ApiResp> {
  const headers: Record<string, string> = {};
  if (clientSession.tokenValue) headers['satoken'] = clientSession.tokenValue;
  if (clientSession.phone) headers['X-PDK-Phone'] = clientSession.phone;
  headers['X-PDK-Device-ID'] = deviceOverride || clientSession.deviceId || '';
  return rawPost(url, body, headers);
}

// ---- 场景 1：注册 ----
const reg = reactive({ phone: '13800138000', deviceId: 'MAC-00-1B-44-11-3A-B7', password: 'test123456', invitationCode: '', loading: false, result: null as ApiResp | null });
async function runRegister() {
  reg.loading = true; reg.result = null;
  try {
    const sms = await rawPost('/api/v1/client/auth/sms/send', { phone: reg.phone, purpose: 'REGISTER' });
    const code = sms.data?.debugCode ?? '';
    const res = await rawPost('/api/v1/client/auth/register', {
      phone: reg.phone, smsCode: code, password: reg.password, deviceId: reg.deviceId,
      invitationCode: reg.invitationCode || undefined,
    });
    if (res.ok) applySession(res.data);
    reg.result = res;
  } finally { reg.loading = false; }
}

// ---- 场景 2：登录 ----
const login = reactive({ phone: '13800138000', deviceId: 'MAC-00-1B-44-11-3A-B7', password: 'test123456', loading: false, result: null as ApiResp | null });
async function runLogin() {
  login.loading = true; login.result = null;
  try {
    const res = await rawPost('/api/v1/client/auth/login', { phone: login.phone, deviceId: login.deviceId, password: login.password });
    if (res.ok) applySession(res.data);
    login.result = res;
  } finally { login.loading = false; }
}

// ---- 场景 3：核销（开放接口） ----
const act = reactive({ cardKey: 'PDK-8891-2041-9982', userPhone: '13800138000', deviceId: 'MAC-00-1B-44-11-3A-B7', actualAmount: 200, loading: false, result: null as ApiResp | null });
async function runActivate() {
  act.loading = true; act.result = null;
  try {
    act.result = await rawPost('/api/v1/card/activate', {
      cardKey: act.cardKey, userPhone: act.userPhone, deviceId: act.deviceId,
      actualAmount: act.actualAmount, orderType: 'NORMAL_SALE', paymentChannel: 'ALIPAY',
    });
  } finally { act.loading = false; }
}

// ---- 场景 4：加密下发 ----
const acq = reactive({ actionType: 'GOODS_COLLECT', goodsId: '1001', loading: false, result: null as ApiResp | null });
async function runAcquire() {
  acq.loading = true; acq.result = null;
  try {
    const res = await clientPost('/api/v1/dispatch/acquire-token', { actionType: acq.actionType, goodsId: acq.goodsId || undefined, timestamp: Date.now() });
    if (res.ok && res.data?.leaseTraceId) {
      lastLease.leaseTraceId = res.data.leaseTraceId;
      lastLease.encryptedPayload = res.data.encryptedPayload;
    }
    acq.result = res;
  } finally { acq.loading = false; }
}

// ---- 场景 5：设备互踢 ----
const kick = reactive({ intruderDevice: 'MAC-00-99-99-99-99-99', loading: false, result: null as ApiResp | null });
async function runKick() {
  kick.loading = true; kick.result = null;
  try {
    kick.result = await clientPost('/api/v1/dispatch/acquire-token', { actionType: 'GOODS_COLLECT', timestamp: Date.now() }, kick.intruderDevice);
  } finally { kick.loading = false; }
}

// ---- 场景 6：成功上报 ----
const rep = reactive({ leaseTraceId: '', loading: false, result: null as ApiResp | null });
async function runReportSuccess() {
  rep.loading = true; rep.result = null;
  try {
    const lease = rep.leaseTraceId || lastLease.leaseTraceId;
    rep.result = await clientPost('/api/v1/dispatch/report-result', { leaseTraceId: lease, status: 'SUCCESS', responseDurationMs: 88 });
  } finally { rep.loading = false; }
}

// ---- 场景 7：故障拉黑（内部先领租约再上报） ----
const fault = reactive({ loading: false, result: null as { acquire: ApiResp; report: ApiResp } | null });
async function runFault() {
  fault.loading = true; fault.result = null;
  try {
    const a = await clientPost('/api/v1/dispatch/acquire-token', { actionType: 'ORDER_PULL', timestamp: Date.now() });
    if (!a.ok || !a.data?.leaseTraceId) { fault.result = { acquire: a, report: { ok: false, code: -1, message: '领取租约失败，无法继续', data: null } }; return; }
    const r = await clientPost('/api/v1/dispatch/report-result', { leaseTraceId: a.data.leaseTraceId, status: 'FAIL_ACCOUNT_BANNED', errorMessage: 'pdd account banned' });
    fault.result = { acquire: a, report: r };
  } finally { fault.loading = false; }
}

// ---- 场景 8：解绑设备 ----
const unbind = reactive({ loading: false, result: null as ApiResp | null });
async function runUnbind() {
  unbind.loading = true; unbind.result = null;
  try {
    const res = await clientPost('/api/v1/client/auth/unbind-device', {});
    if (res.ok) { clientSession.tokenValue = ''; } // 会话已注销
    unbind.result = res;
  } finally { unbind.loading = false; }
}
</script>
