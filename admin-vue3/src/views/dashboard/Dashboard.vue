<template>
  <div class="space-y-6">
    <!-- 顶部标题 -->
    <div class="flex justify-between items-center">
      <div>
        <h2 class="text-xl font-bold text-slate-800">商业化运营与财务大盘</h2>
        <p class="text-xs text-slate-500 mt-1">
          实时展示卡密核销收益、Token采购支出、毛利率及多账号并发承载
        </p>
      </div>
      <el-button type="primary" :icon="Refresh" @click="fetchData">刷新数据</el-button>
    </div>

    <!-- 4个核心指标卡片 -->
    <el-row :gutter="16">
      <el-col :span="6">
        <el-card shadow="hover" class="border-slate-200">
          <div class="text-xs text-slate-500 font-medium">累计总实收流水 (已核销)</div>
          <div class="text-2xl font-bold text-indigo-600 mt-2">¥{{ summary.totalIncome.toFixed(2) }}</div>
          <div class="text-xs text-slate-400 mt-2 flex justify-between">
            <span>正价售卖: ¥{{ summary.normalSaleIncome }}</span>
            <span>折价让利: ¥{{ summary.discountSaleIncome }}</span>
          </div>
        </el-card>
      </el-col>

      <el-col :span="6">
        <el-card shadow="hover" class="border-slate-200">
          <div class="text-xs text-slate-500 font-medium">Token 采购对公支出</div>
          <div class="text-2xl font-bold text-rose-600 mt-2">¥{{ summary.totalExpense.toFixed(2) }}</div>
          <div class="text-xs text-slate-400 mt-2">
            采购进货单价: 0.15 ~ 0.20 元/个
          </div>
        </el-card>
      </el-col>

      <el-col :span="6">
        <el-card shadow="hover" class="border-slate-200">
          <div class="text-xs text-slate-500 font-medium">平台净利润</div>
          <div class="text-2xl font-bold text-emerald-600 mt-2">¥{{ summary.netProfit.toFixed(2) }}</div>
          <div class="text-xs text-slate-400 mt-2">
            综合毛利率: <span class="text-emerald-600 font-semibold">{{ summary.profitMarginRate }}%</span>
          </div>
        </el-card>
      </el-col>

      <el-col :span="6">
        <el-card shadow="hover" class="border-slate-200">
          <div class="text-xs text-slate-500 font-medium">拼多多 Token 健康可用池</div>
          <div class="text-2xl font-bold text-blue-600 mt-2">{{ summary.activeTokenCount }} / 30</div>
          <div class="text-xs text-slate-400 mt-2">
            已激活核销卡密总数: {{ summary.totalCardsActivated }} 张
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 架构要点提示 -->
    <el-card shadow="never" class="bg-blue-50/50 border-blue-200">
      <div class="flex items-start gap-3">
        <el-icon class="text-blue-600 text-lg mt-0.5"><InfoFilled /></el-icon>
        <div class="text-xs text-slate-600 space-y-1">
          <div class="font-semibold text-slate-800">企业级生产架构与财务规范已落实：</div>
          <div>1. <strong>财务物理解耦</strong>：卡密核销时，严格向独立财务实收流水表写入订单，彻底与卡密凭证表分离。</div>
          <div>2. <strong>防重复与高并发保障</strong>：后端采用行级悲观锁与 CAS 乐观核销，杜绝同一卡密多端重复充值。</div>
          <div>3. <strong>通信加密防盗刷</strong>：服务端下发拼多多 Token 使用 AES-128-GCM 配合 10 分钟动态时间窗口与字节反转。</div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { Refresh, InfoFilled } from '@element-plus/icons-vue';
import { FinanceSummary } from '../../types';

const summary = ref<FinanceSummary>({
  totalIncome: 12560.00,
  normalSaleIncome: 10200.00,
  discountSaleIncome: 2360.00,
  giftValue: 1200.00,
  totalExpense: 2450.00,
  netProfit: 10110.00,
  profitMarginRate: 80.49,
  totalCardsActivated: 68,
  activeTokenCount: 26,
});

const fetchData = () => {
  // 模拟从后端接口获取最新聚合统计
};

onMounted(() => {
  fetchData();
});
</script>
