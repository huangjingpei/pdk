<template>
  <div>
    <div class="page-title">
      <div>
        <h2>登录日志</h2>
        <p>记录客户端用户与后台管理员的登录成功/失败，以及改密、解绑设备等敏感动作。
          排查盗用时按 IP 检索，可以看到同一个 IP 登录过哪些账号。</p>
      </div>
      <el-button :icon="Refresh" @click="load()">刷新</el-button>
    </div>

    <el-card shadow="never" class="border-slate-200">
      <div class="filter-bar">
        <el-select v-model="filters.actorType" placeholder="身份" clearable style="width: 130px">
          <el-option label="客户端用户" value="CLIENT" />
          <el-option label="后台管理员" value="ADMIN" />
        </el-select>
        <el-select v-model="filters.eventType" placeholder="事件" clearable style="width: 150px">
          <el-option v-for="e in eventOptions" :key="e.value" :label="e.label" :value="e.value" />
        </el-select>
        <el-select v-model="filters.result" placeholder="结果" clearable style="width: 110px">
          <el-option label="成功" value="SUCCESS" />
          <el-option label="失败" value="FAIL" />
        </el-select>
        <el-input v-model="filters.account" placeholder="账号 / 手机号" clearable style="width: 170px" @keyup.enter="onSearch" />
        <el-input v-model="filters.ip" placeholder="IP（支持模糊）" clearable style="width: 170px" @keyup.enter="onSearch" />
        <el-date-picker v-model="range" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss"
          start-placeholder="开始时间" end-placeholder="结束时间" style="width: 360px" />
        <el-button type="primary" :icon="Search" @click="onSearch">查询</el-button>
        <el-button :icon="RefreshLeft" @click="resetFilter">重置</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe style="width: 100%">
        <el-table-column prop="createdAt" label="时间" width="180" />
        <el-table-column label="身份" width="110">
          <template #default="s">
            <el-tag :type="s.row.actorType === 'ADMIN' ? 'danger' : 'primary'" size="small">
              {{ s.row.actorType === 'ADMIN' ? '管理员' : '客户端' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="actorAccount" label="账号" min-width="140" />
        <el-table-column label="事件" width="130">
          <template #default="s">{{ eventText(s.row.eventType) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="s">
            <el-tag :type="s.row.result === 'SUCCESS' ? 'success' : 'danger'" size="small">
              {{ s.row.result === 'SUCCESS' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ipAddress" label="IP" min-width="140" />
        <el-table-column label="设备" min-width="180">
          <template #default="s">
            <span class="font-mono text-xs text-slate-600 break-all">{{ s.row.deviceId || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="220">
          <template #default="s">
            <span :class="s.row.result === 'FAIL' ? 'text-rose-600' : 'text-slate-500'">
              {{ s.row.failReason || '-' }}
            </span>
          </template>
        </el-table-column>
      </el-table>

      <div class="mt-3 flex items-center justify-between">
        <span class="text-xs text-slate-400">共 {{ total }} 条</span>
        <Pagination v-model:page="page" v-model:page-size="pageSize" :total="total" :page-sizes="[20, 50, 100]" @change="(q) => load(q.page)" />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh, RefreshLeft, Search } from '@element-plus/icons-vue';
import { api, type ApiResult, type PageResult } from '../../api';
import Pagination from '../../components/Pagination.vue';
import type { LoginLog } from '../../types';

const rows = ref<LoginLog[]>([]);
const loading = ref(false);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
const range = ref<[string, string] | null>(null);
const filters = ref({ actorType: '', eventType: '', result: '', account: '', ip: '' });

const eventOptions = [
  { value: 'LOGIN', label: '登录' },
  { value: 'LOGOUT', label: '退出' },
  { value: 'PASSWORD_RESET', label: '密码重置' },
  { value: 'FORCE_CHANGE', label: '强制改密' },
  { value: 'DEVICE_UNBIND', label: '解绑设备' },
];

function eventText(type: string): string {
  return eventOptions.find(e => e.value === type)?.label ?? type;
}

// 防御：模板里若误写 @click="load"（不带括号），原生 PointerEvent 会被当成页码传进来。
async function load(p: unknown = 1): Promise<void> {
  const target = (typeof p === 'number' && Number.isFinite(p) && p > 0) ? p : 1;
  page.value = target;
  loading.value = true;
  try {
    const params: Record<string, unknown> = { page: target, size: pageSize.value };
    if (filters.value.actorType) params.actorType = filters.value.actorType;
    if (filters.value.eventType) params.eventType = filters.value.eventType;
    if (filters.value.result) params.result = filters.value.result;
    if (filters.value.account.trim()) params.account = filters.value.account.trim();
    if (filters.value.ip.trim()) params.ip = filters.value.ip.trim();
    if (range.value && range.value.length === 2) {
      params.start = range.value[0];
      params.end = range.value[1];
    }
    const response = await api.get<ApiResult<PageResult<LoginLog>>>('/api/v1/admin/logs/login', { params });
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
  filters.value = { actorType: '', eventType: '', result: '', account: '', ip: '' };
  range.value = null;
  load(1);
}
onMounted(() => { load(1); });
</script>

<style scoped>
.page-title { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-wrap: wrap; gap: 12px; }
h2 { margin: 0; color: #1e293b; }
p { margin: 6px 0 0; color: #64748b; font-size: 13px; max-width: 760px; }
.filter-bar { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 16px; }
.font-mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
.text-xs { font-size: 12px; }
.text-slate-500 { color: #64748b; }
.text-slate-600 { color: #475569; }
.text-rose-600 { color: #e11d48; }
.break-all { word-break: break-all; }
</style>
