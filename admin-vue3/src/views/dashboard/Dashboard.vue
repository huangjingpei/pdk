<template>
  <div>
    <div class="heading">
      <div><h2>运营任务总览</h2><p>按当前角色展示可访问的业务范围，数据来自 Spring Boot 实时接口。</p></div>
      <el-button type="primary" :icon="Refresh" :loading="loading" @click="load()">刷新数据</el-button>
    </div>
    <!-- 顶部核心指标卡片 -->
    <el-row :gutter="16">
      <el-col v-for="item in metrics" :key="item.label" :xs="12" :sm="8" :md="summary.live ? 4 : 4" :lg="summary.live ? 3 : 4" class="metric-col">
        <el-card shadow="hover">
          <div class="label">{{ item.label }}</div>
          <div class="value">{{ item.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 活跃度看板 (最近10天 / 30天 / 90天) -->
    <el-card shadow="hover" class="activity-card">
      <template #header>
        <div class="card-header">
          <div class="card-title">
            <el-icon class="title-icon"><TrendCharts /></el-icon>
            <span>用户与设备活跃度看板</span>
          </div>
          <el-tag type="success" effect="plain" round size="small">
            基于最后登录时间 · 实时聚合
          </el-tag>
        </div>
      </template>

      <el-row :gutter="24">
        <!-- 用户活跃分布 -->
        <el-col :xs="24" :md="12" class="activity-section">
          <div class="section-title">
            <span class="dot dot-user"></span>
            <span class="title-text">用户活跃分布</span>
            <span class="title-tip">（总注册用户: {{ summary.userCount }} 人）</span>
          </div>
          <el-row :gutter="12" class="stat-boxes">
            <el-col :span="8">
              <div class="stat-box box-blue">
                <div class="stat-tag">近 10 天</div>
                <div class="stat-val text-blue">{{ summary.activeUsers10d ?? 0 }}</div>
                <div class="stat-name">活跃用户数</div>
                <div class="stat-rate">占比 {{ calcRate(summary.activeUsers10d, summary.userCount) }}%</div>
              </div>
            </el-col>
            <el-col :span="8">
              <div class="stat-box box-indigo">
                <div class="stat-tag">近 30 天</div>
                <div class="stat-val text-indigo">{{ summary.activeUsers30d ?? 0 }}</div>
                <div class="stat-name">活跃用户数</div>
                <div class="stat-rate">占比 {{ calcRate(summary.activeUsers30d, summary.userCount) }}%</div>
              </div>
            </el-col>
            <el-col :span="8">
              <div class="stat-box box-purple">
                <div class="stat-tag">近 90 天</div>
                <div class="stat-val text-purple">{{ summary.activeUsers90d ?? 0 }}</div>
                <div class="stat-name">活跃用户数</div>
                <div class="stat-rate">占比 {{ calcRate(summary.activeUsers90d, summary.userCount) }}%</div>
              </div>
            </el-col>
          </el-row>
        </el-col>

        <!-- 设备活跃分布 -->
        <el-col :xs="24" :md="12" class="activity-section">
          <div class="section-title">
            <span class="dot dot-device"></span>
            <span class="title-text">设备终端活跃分布</span>
            <span class="title-tip">（活跃设备: {{ summary.activeDeviceCount ?? 0 }} / 登记设备: {{ summary.deviceCount ?? 0 }}）</span>
          </div>
          <el-row :gutter="12" class="stat-boxes">
            <el-col :span="8">
              <div class="stat-box box-emerald">
                <div class="stat-tag">近 10 天</div>
                <div class="stat-val text-emerald">{{ summary.activeDevices10d ?? 0 }}</div>
                <div class="stat-name">活跃设备数</div>
                <div class="stat-rate">占比 {{ calcRate(summary.activeDevices10d, summary.activeDeviceCount || summary.deviceCount) }}%</div>
              </div>
            </el-col>
            <el-col :span="8">
              <div class="stat-box box-teal">
                <div class="stat-tag">近 30 天</div>
                <div class="stat-val text-teal">{{ summary.activeDevices30d ?? 0 }}</div>
                <div class="stat-name">活跃设备数</div>
                <div class="stat-rate">占比 {{ calcRate(summary.activeDevices30d, summary.activeDeviceCount || summary.deviceCount) }}%</div>
              </div>
            </el-col>
            <el-col :span="8">
              <div class="stat-box box-cyan">
                <div class="stat-tag">近 90 天</div>
                <div class="stat-val text-cyan">{{ summary.activeDevices90d ?? 0 }}</div>
                <div class="stat-name">活跃设备数</div>
                <div class="stat-rate">占比 {{ calcRate(summary.activeDevices90d, summary.activeDeviceCount || summary.deviceCount) }}%</div>
              </div>
            </el-col>
          </el-row>
        </el-col>
      </el-row>
    </el-card>

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
import { Refresh, TrendCharts } from '@element-plus/icons-vue';
import { api, type ApiResult } from '../../api';
import type { FinanceSummary, LiveOverview } from '../../types';

interface ActivityStats {
  activeUsers10d: number;
  activeUsers30d: number;
  activeUsers90d: number;
  activeDevices10d: number;
  activeDevices30d: number;
  activeDevices90d: number;
}

interface DashboardSummary {
  userCount: number;
  activeUserCount: number;
  deviceCount?: number;
  activeDeviceCount?: number;
  activeUsers10d?: number;
  activeUsers30d?: number;
  activeUsers90d?: number;
  activeDevices10d?: number;
  activeDevices30d?: number;
  activeDevices90d?: number;
  activity?: ActivityStats;
  unusedCardCount: number;
  healthyResourceCount: number;
  finance?: FinanceSummary;
  live?: LiveOverview;
}

const loading = ref(false);
const summary = reactive<DashboardSummary>({
  userCount: 0,
  activeUserCount: 0,
  deviceCount: 0,
  activeDeviceCount: 0,
  activeUsers10d: 0,
  activeUsers30d: 0,
  activeUsers90d: 0,
  activeDevices10d: 0,
  activeDevices30d: 0,
  activeDevices90d: 0,
  unusedCardCount: 0,
  healthyResourceCount: 0,
});

const metrics = computed(() => {
  const base: Array<{ label: string; value: string | number }> = [
    { label: '客户端用户', value: summary.userCount },
    { label: '有效用户', value: summary.activeUserCount },
    { label: '活跃设备数', value: summary.activeDeviceCount ?? 0 },
    { label: '待售卡密', value: summary.unusedCardCount },
    { label: '健康小号资源', value: summary.healthyResourceCount },
  ];
  if (summary.live) {
    base.push(
      { label: '当前推流', value: summary.live.activePublishers },
      { label: '当前拉流', value: summary.live.activeReaders },
      { label: '直播上行', value: bitrate(summary.live.inboundBps) },
      { label: '直播下行', value: bitrate(summary.live.outboundBps) },
    );
  }
  return base;
});

const money = (value: number) => Number(value || 0).toFixed(2);
const bitrate = (value?: number) => (value === undefined || value === null ? '--' : `${(value / 1_000_000).toFixed(2)} Mbps`);
const calcRate = (active?: number, total?: number) => {
  if (!active || !total || total <= 0) return '0.0';
  return ((active / total) * 100).toFixed(1);
};

async function load(): Promise<void> {
  loading.value = true;
  try {
    const response = await api.get<ApiResult<DashboardSummary>>('/api/v1/admin/dashboard/summary');
    Object.assign(summary, response.data.data);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载失败');
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>

<style scoped>
.heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
h2 {
  margin: 0;
  color: #1e293b;
}
p {
  margin: 6px 0 0;
  color: #64748b;
  font-size: 13px;
}
.label {
  color: #64748b;
  font-size: 13px;
}
.value {
  margin-top: 12px;
  font-size: 28px;
  font-weight: 700;
  color: #2563eb;
}
.metric-col {
  margin-bottom: 16px;
}

/* 活跃度看板样式 */
.activity-card {
  margin-bottom: 16px;
  border-radius: 8px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-title {
  display: flex;
  align-items: center;
  font-size: 16px;
  font-weight: 600;
  color: #1e293b;
}
.title-icon {
  margin-right: 8px;
  font-size: 18px;
  color: #3b82f6;
}
.activity-section {
  margin-bottom: 8px;
}
.section-title {
  display: flex;
  align-items: center;
  margin-bottom: 14px;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 8px;
}
.dot-user {
  background-color: #3b82f6;
}
.dot-device {
  background-color: #10b981;
}
.title-text {
  font-weight: 600;
  color: #334155;
  font-size: 14px;
}
.title-tip {
  margin-left: 6px;
  color: #94a3b8;
  font-size: 12px;
}
.stat-boxes {
  margin-top: 4px;
}
.stat-box {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 14px 12px;
  text-align: center;
  transition: all 0.2s ease;
}
.stat-box:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05);
}
.box-blue {
  background: linear-gradient(180deg, #eff6ff 0%, #f8fafc 100%);
  border-color: #bfdbfe;
}
.box-indigo {
  background: linear-gradient(180deg, #eef2ff 0%, #f8fafc 100%);
  border-color: #c7d2fe;
}
.box-purple {
  background: linear-gradient(180deg, #faf5ff 0%, #f8fafc 100%);
  border-color: #e9d5ff;
}
.box-emerald {
  background: linear-gradient(180deg, #ecfdf5 0%, #f8fafc 100%);
  border-color: #a7f3d0;
}
.box-teal {
  background: linear-gradient(180deg, #f0fdfa 0%, #f8fafc 100%);
  border-color: #99f6e4;
}
.box-cyan {
  background: linear-gradient(180deg, #ecfeff 0%, #f8fafc 100%);
  border-color: #a5f3fc;
}

.stat-tag {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
  margin-bottom: 6px;
}
.stat-val {
  font-size: 26px;
  font-weight: 800;
  line-height: 1.2;
}
.text-blue {
  color: #2563eb;
}
.text-indigo {
  color: #4f46e5;
}
.text-purple {
  color: #7c3aed;
}
.text-emerald {
  color: #059669;
}
.text-teal {
  color: #0d9488;
}
.text-cyan {
  color: #0891b2;
}

.stat-name {
  font-size: 12px;
  color: #64748b;
  margin-top: 4px;
}
.stat-rate {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 4px;
}

.finance-row {
  margin-top: 2px;
}
.money {
  margin-top: 12px;
  font-size: 24px;
  font-weight: 700;
}
.income,
.profit {
  color: #059669;
}
.expense {
  color: #e11d48;
}
.notice {
  margin-top: 16px;
}
.live-notice {
  margin-top: 2px;
}
</style>
