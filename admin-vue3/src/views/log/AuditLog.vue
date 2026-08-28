<template>
  <div>
    <div class="page-title">
      <div>
        <h2>操作审计</h2>
        <p>管理后台的高危操作留痕：改套餐、冻结账号、重置密码、发卡、作废卡密等。
          变更前后的状态以 JSON 快照保存，出问题时可以还原当时到底改了什么。</p>
      </div>
      <el-button :icon="Refresh" @click="load()">刷新</el-button>
    </div>

    <el-card shadow="never" class="border-slate-200">
      <div class="filter-bar">
        <el-select v-model="filters.actionType" placeholder="操作类型" clearable filterable style="width: 240px">
          <el-option v-for="a in actionOptions" :key="a" :label="a" :value="a" />
        </el-select>
        <el-input v-model="filters.adminName" placeholder="操作人" clearable style="width: 160px" @keyup.enter="onSearch" />
        <el-input v-model="filters.targetId" placeholder="目标（手机号/卡密）" clearable style="width: 200px" @keyup.enter="onSearch" />
        <el-date-picker v-model="range" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss"
          start-placeholder="开始时间" end-placeholder="结束时间" style="width: 360px" />
        <el-button type="primary" :icon="Search" @click="onSearch">查询</el-button>
        <el-button :icon="RefreshLeft" @click="resetFilter">重置</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe style="width: 100%">
        <el-table-column prop="createdAt" label="时间" width="180" />
        <el-table-column label="操作人" min-width="150">
          <template #default="s">
            <div>{{ s.row.adminName }}</div>
            <div class="text-xs text-slate-400">{{ roleText(s.row.adminRole) }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="actionType" label="操作类型" min-width="200" />
        <el-table-column label="目标" min-width="150">
          <template #default="s">
            <span class="text-xs text-slate-500">{{ s.row.targetType || '-' }}</span>
            <div>{{ s.row.targetId || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="变更前" min-width="200">
          <template #default="s">
            <span class="font-mono text-xs text-slate-500 break-all">{{ s.row.beforeState || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="变更后" min-width="200">
          <template #default="s">
            <span class="font-mono text-xs text-slate-500 break-all">{{ s.row.afterState || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="180" />
        <el-table-column prop="ipAddress" label="IP" min-width="130" />
      </el-table>

      <div class="mt-3 flex items-center justify-between">
        <span class="text-xs text-slate-400">共 {{ total }} 条</span>
        <el-pagination layout="total, sizes, prev, pager, next, jumper" :total="total" :page-size="pageSize"
          :page-sizes="[20, 50, 100]" :current-page="page" @current-change="onPageChange" @size-change="onSizeChange" />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh, RefreshLeft, Search } from '@element-plus/icons-vue';
import { api, type ApiResult, type PageResult } from '../../api';
import type { AdminAuditLog } from '../../types';

const rows = ref<AdminAuditLog[]>([]);
const loading = ref(false);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
const range = ref<[string, string] | null>(null);
const filters = ref({ actionType: '', adminName: '', targetId: '' });

// 与后端 adminAuditService.record 传入的 actionType 保持一致，用于下拉筛选
const actionOptions = [
  'MANUAL_ADJUST_USER', 'CHANGE_USER_STATUS', 'CHANGE_USER_ROLE', 'CREATE_USER',
  'RESET_USER_PASSWORD', 'FORCE_USER_CHANGE_PASSWORD', 'CANCEL_FORCE_USER_CHANGE_PASSWORD',
  'UNBIND_DEVICE', 'GENERATE_CARD', 'VOID_CARD', 'VOID_ALL_OWNED_CARDS',
  'EXTEND_EXPIRE', 'BLOCK_USER', 'MANUAL_ADJUST_QUOTA',
  'CREATE_ADMIN', 'CHANGE_ADMIN_ROLE', 'CHANGE_ADMIN_STATUS',
  'CREATE_BUSINESS', 'UPDATE_BUSINESS', 'DISABLE_BUSINESS', 'ENABLE_BUSINESS',
  'CREATE_PACKAGE', 'DISABLE_PACKAGE', 'CREATE_TOKEN_RESOURCE', 'CHANGE_TOKEN_STATUS',
];

function roleText(role?: string): string {
  if (role === 'SUPER_ADMIN') return '超级管理员';
  if (role === 'PARTNER') return '代理商';
  return role || '';
}

// 防御：模板里若误写 @click="load"（不带括号），原生 PointerEvent 会被当成页码传进来。
async function load(p: unknown = 1): Promise<void> {
  const target = (typeof p === 'number' && Number.isFinite(p) && p > 0) ? p : 1;
  page.value = target;
  loading.value = true;
  try {
    const params: Record<string, unknown> = { page: target, size: pageSize.value };
    if (filters.value.actionType) params.actionType = filters.value.actionType;
    if (filters.value.adminName.trim()) params.adminName = filters.value.adminName.trim();
    if (filters.value.targetId.trim()) params.targetId = filters.value.targetId.trim();
    if (range.value && range.value.length === 2) {
      params.start = range.value[0];
      params.end = range.value[1];
    }
    const response = await api.get<ApiResult<PageResult<AdminAuditLog>>>('/api/v1/admin/logs/audit', { params });
    rows.value = response.data.data.records;
    total.value = response.data.data.total;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function onSearch(): void { load(1); }
function resetFilter(): void {
  filters.value = { actionType: '', adminName: '', targetId: '' };
  range.value = null;
  load(1);
}
const onPageChange = (p: number) => load(p);
const onSizeChange = (s: number) => { pageSize.value = s; load(1); };

onMounted(() => { load(1); });
</script>

<style scoped>
.page-title { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-wrap: wrap; gap: 12px; }
h2 { margin: 0; color: #1e293b; }
p { margin: 6px 0 0; color: #64748b; font-size: 13px; max-width: 760px; }
.filter-bar { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 16px; }
.font-mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
.text-xs { font-size: 12px; }
.text-slate-400 { color: #94a3b8; }
.text-slate-500 { color: #64748b; }
.break-all { word-break: break-all; }
</style>
