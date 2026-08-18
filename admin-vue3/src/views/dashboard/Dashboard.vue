<template>
  <div>
    <div class="heading">
      <div><h2>运营任务总览</h2><p>按当前角色展示可访问的业务范围，数据来自 Spring Boot 实时接口。</p></div>
      <el-button type="primary" :icon="Refresh" :loading="loading" @click="load">刷新数据</el-button>
    </div>
    <el-row :gutter="16">
      <el-col v-for="item in metrics" :key="item.label" :span="6">
        <el-card shadow="hover"><div class="label">{{ item.label }}</div><div class="value">{{ item.value }}</div></el-card>
      </el-col>
    </el-row>
    <el-row v-if="summary.finance" :gutter="16" class="finance-row">
      <el-col :span="8"><el-card><div class="label">累计实收</div><div class="money income">¥{{ money(summary.finance.totalIncome) }}</div></el-card></el-col>
      <el-col :span="8"><el-card><div class="label">累计支出</div><div class="money expense">¥{{ money(summary.finance.totalExpense) }}</div></el-card></el-col>
      <el-col :span="8"><el-card><div class="label">净利润 / 毛利率</div><div class="money profit">¥{{ money(summary.finance.netProfit) }} · {{ summary.finance.profitMarginRate }}%</div></el-card></el-col>
    </el-row>
    <el-alert class="notice" type="info" :closable="false" title="权限由后端强制执行；前端动态菜单只用于改善操作体验，不能替代服务端鉴权。" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { api, type ApiResult } from '../../api';
import type { FinanceSummary } from '../../types';

interface DashboardSummary { userCount: number; activeUserCount: number; unusedCardCount: number; healthyResourceCount: number; finance?: FinanceSummary }
const loading = ref(false);
const summary = reactive<DashboardSummary>({ userCount: 0, activeUserCount: 0, unusedCardCount: 0, healthyResourceCount: 0 });
const metrics = computed(() => [
  { label: '客户端用户', value: summary.userCount },
  { label: '有效用户', value: summary.activeUserCount },
  { label: '待售卡密', value: summary.unusedCardCount },
  { label: '健康小号资源', value: summary.healthyResourceCount },
]);
const money = (value: number) => Number(value || 0).toFixed(2);

async function load(): Promise<void> {
  loading.value = true;
  try {
    const response = await api.get<ApiResult<DashboardSummary>>('/api/v1/admin/dashboard/summary');
    Object.assign(summary, response.data.data);
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '加载失败'); }
  finally { loading.value = false; }
}
onMounted(load);
</script>

<style scoped>
.heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
h2 { margin: 0; color: #1e293b; } p { margin: 6px 0 0; color: #64748b; font-size: 13px; }
.label { color: #64748b; font-size: 13px; }.value { margin-top: 12px; font-size: 30px; font-weight: 700; color: #2563eb; }
.finance-row { margin-top: 18px; }.money { margin-top: 12px; font-size: 24px; font-weight: 700; }.income,.profit { color: #059669; }.expense { color: #e11d48; }.notice { margin-top: 18px; }
</style>
