<template>
  <div>
    <div class="heading">
      <div><h2>运营任务总览</h2><p>按当前角色展示可访问的业务范围，数据来自 Spring Boot 实时接口。</p></div>
      <el-button type="primary" :icon="Refresh" :loading="loading" @click="load()">刷新数据</el-button>
    </div>
    <el-row :gutter="16">
      <el-col v-for="item in metrics" :key="item.label" :span="summary.live ? 4 : 6" class="metric-col">
        <el-card shadow="hover"><div class="label">{{ item.label }}</div><div class="value">{{ item.value }}</div></el-card>
      </el-col>
    </el-row>
    <el-alert v-if="summary.live" class="notice live-notice" :type="summary.live.abnormalNodes?'warning':'success'" :closable="false"
      :title="`ZHIBO_LIVE 媒体节点 ${summary.live.availableNodes}/${summary.live.totalNodes} 可用；带宽 -- 表示尚未采集，不代表 0。`" />
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
import type { FinanceSummary, LiveOverview } from '../../types';

interface DashboardSummary { userCount: number; activeUserCount: number; unusedCardCount: number; healthyResourceCount: number; finance?: FinanceSummary; live?: LiveOverview }
const loading = ref(false);
const summary = reactive<DashboardSummary>({ userCount: 0, activeUserCount: 0, unusedCardCount: 0, healthyResourceCount: 0 });
const metrics = computed(() => {
 const base: Array<{label:string;value:string|number}>=[
  { label: '客户端用户', value: summary.userCount },
  { label: '有效用户', value: summary.activeUserCount },
  { label: '待售卡密', value: summary.unusedCardCount },
  { label: '健康小号资源', value: summary.healthyResourceCount },
 ];
 if(summary.live)base.push(
   {label:'当前推流',value:summary.live.activePublishers},
   {label:'当前拉流',value:summary.live.activeReaders},
   {label:'直播上行',value:bitrate(summary.live.inboundBps)},
   {label:'直播下行',value:bitrate(summary.live.outboundBps)},
 );
 return base;
});
const money = (value: number) => Number(value || 0).toFixed(2);
const bitrate = (value?: number) => value===undefined||value===null?'--':`${(value/1_000_000).toFixed(2)} Mbps`;

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
.metric-col{margin-bottom:16px}.finance-row { margin-top: 2px; }.money { margin-top: 12px; font-size: 24px; font-weight: 700; }.income,.profit { color: #059669; }.expense { color: #e11d48; }.notice { margin-top: 18px; }.live-notice{margin-top:2px}
</style>
