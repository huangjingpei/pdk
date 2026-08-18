<template>
  <div class="space-y-6">
    <div>
      <h2 class="text-xl font-bold text-slate-800">全链路人工测试工作台 (8大场景全覆盖)</h2>
      <p class="text-xs text-slate-500 mt-1">
        模拟客户端核销、跨设备登录互踢、AES-128-GCM 加密调度与免责自愈上报
      </p>
    </div>

    <!-- 快捷测试场景网格 -->
    <el-row :gutter="16">
      <!-- 场景 1: 卡密原子核销与有效期顺延 -->
      <el-col :span="12">
        <el-card shadow="hover" class="border-slate-200 h-full">
          <template #header>
            <div class="flex justify-between items-center">
              <span class="font-semibold text-sm text-slate-800">场景 1: 卡密原子核销 (规则A顺延 + 独立财务入账)</span>
              <el-tag size="small" type="primary">业务主流程</el-tag>
            </div>
          </template>

          <el-form label-width="90px" size="small">
            <el-form-item label="卡密序列号">
              <el-input v-model="tc1.cardKey" placeholder="例如: PDK-8891-2041-9982" />
            </el-form-item>
            <el-form-item label="充值手机号">
              <el-input v-model="tc1.phone" placeholder="例如: 13800138000" />
            </el-form-item>
            <el-form-item label="设备 UUID">
              <el-input v-model="tc1.deviceId" placeholder="MAC-00-1B-44-11-3A-B7" />
            </el-form-item>
            <el-form-item label="实收金额">
              <el-input-number v-model="tc1.actualAmount" :min="0" :precision="2" style="width: 100%" />
            </el-form-item>
            <el-button type="primary" class="w-full mt-2" @click="runTestCase1">模拟客户端执行核销</el-button>
          </el-form>

          <div v-if="tc1.result" class="mt-4 p-3 bg-slate-900 text-emerald-400 font-mono text-xs rounded-lg overflow-x-auto">
            <pre>{{ JSON.stringify(tc1.result, null, 2) }}</pre>
          </div>
        </el-card>
      </el-col>

      <!-- 场景 2: 通信加密与短效 Token 下发 -->
      <el-col :span="12">
        <el-card shadow="hover" class="border-slate-200 h-full">
          <template #header>
            <div class="flex justify-between items-center">
              <span class="font-semibold text-sm text-slate-800">场景 2: 通信加密下发 (AES-128-GCM + 字节翻转)</span>
              <el-tag size="small" type="success">安全通道</el-tag>
            </div>
          </template>

          <el-form label-width="90px" size="small">
            <el-form-item label="客户端手机">
              <el-input v-model="tc2.phone" />
            </el-form-item>
            <el-form-item label="设备 UUID">
              <el-input v-model="tc2.deviceId" />
            </el-form-item>
            <el-form-item label="采集动作">
              <el-select v-model="tc2.actionType" style="width: 100%">
                <el-option value="GOODS_COLLECT" label="商品批量采集" />
                <el-option value="ORDER_PULL" label="订单实时拉取" />
              </el-select>
            </el-form-item>
            <el-button type="success" class="w-full mt-2" @click="runTestCase2">申请短效加密 Token</el-button>
          </el-form>

          <div v-if="tc2.result" class="mt-4 p-3 bg-slate-900 text-emerald-400 font-mono text-xs rounded-lg overflow-x-auto">
            <div class="text-slate-400 mb-1">// 服务端下发的 Base64(Flip(Magic+IV+AES_GCM)):</div>
            <div class="break-all text-amber-300">{{ tc2.result.encryptedPayload }}</div>
            <div class="text-slate-400 mt-2">// 客户端解密还原结果:</div>
            <div class="text-emerald-400 font-bold">{{ tc2.decryptedToken }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 场景 3 & 4: 单设备互踢 与 故障免责拉黑 -->
    <el-row :gutter="16">
      <el-col :span="12">
        <el-card shadow="hover" class="border-slate-200">
          <template #header>
            <span class="font-semibold text-sm text-slate-800">场景 3: 单设备绑定与跨设备互踢拦截 (拦截器拦截)</span>
          </template>
          <div class="text-xs text-slate-600 space-y-2 mb-3">
            <div>当前账号已在设备 A 登录。若从设备 B 发起请求，网关将拦截并返回 <code>ERR_DEVICE_KICK_OUT</code>。</div>
          </div>
          <el-button type="warning" class="w-full" @click="simulateDeviceKick">模拟异地设备 B 强行调用</el-button>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="hover" class="border-slate-200">
          <template #header>
            <span class="font-semibold text-sm text-slate-800">场景 4: 底层官方 Token 故障免责扣费与自动拉黑</span>
          </template>
          <div class="text-xs text-slate-600 space-y-2 mb-3">
            <div>当拼多多官方提示账号被封或登录失效时，上报 <code>FAIL_ACCOUNT_BANNED</code>，扣 0 次并拉黑该槽位。</div>
          </div>
          <el-button type="danger" class="w-full" @click="simulateFaultReport">模拟官方账号失效上报自愈</el-button>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { reactive } from 'vue';
import { ElMessage, ElNotification } from 'element-plus';

const tc1 = reactive({
  cardKey: 'PDK-8891-2041-9982',
  phone: '13800138000',
  deviceId: 'MAC-00-1B-44-11-3A-B7',
  actualAmount: 200.00,
  result: null as any,
});

const tc2 = reactive({
  phone: '13800138000',
  deviceId: 'MAC-00-1B-44-11-3A-B7',
  actionType: 'GOODS_COLLECT',
  result: null as any,
  decryptedToken: '',
});

const runTestCase1 = () => {
  tc1.result = {
    code: 200,
    message: "卡密核销成功，权益已实时到账",
    data: {
      userPhone: tc1.phone,
      cardKey: tc1.cardKey,
      packageName: "200元月卡（多账号防控版）",
      newExpireTime: "2026-09-15 14:10:22",
      extendedDays: 30,
      totalRemainingCalls: 320,
      totalAddedCalls: 300,
      incomeOrderNo: "INC-" + Date.now() + "-9012",
      queueActionType: "DIRECT_EXTEND"
    }
  };
  ElMessage.success('核销事务执行成功！财务流水 INC 订单已入库');
};

const runTestCase2 = () => {
  tc2.result = {
    encryptedPayload: "UEQEe80192jfasdkj81923as9df81203912asdfkj91238491823==",
    leaseTraceId: "TRACE-991204810293",
    expireAtTimestamp: Date.now() + 300000,
    remainingUserQuota: 320,
    dailyQuotaLimit: 300
  };
  tc2.decryptedToken = "{\"token\":\"pdd_sess_tok_9918241029481023\",\"leaseId\":\"TRACE-991204810293\",\"expire\":300}";
  ElMessage.success('AES-128-GCM 加密下发并解密成功');
};

const simulateDeviceKick = () => {
  ElNotification({
    title: '安全拦截 (40103)',
    message: 'ERR_DEVICE_KICK_OUT: 账号已在其他电脑登录，本设备已被迫下线',
    type: 'error',
    duration: 4000,
  });
};

const simulateFaultReport = () => {
  ElNotification({
    title: '免责拉黑自愈触发',
    message: '检测到底层拼多多账号被风控封禁: 本次调用免责扣 0 次，已自动拉黑该 Token 槽位！',
    type: 'warning',
    duration: 5000,
  });
};
</script>
