<template>
  <div>
    <div class="header">
      <div>
        <h2>系统设置</h2>
        <p>平台级全局参数，由超级管理员统一维护；令牌分配、短信、协议加密等策略在此集中开关。</p>
      </div>
      <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
    </div>

    <div v-for="g in groups" :key="g.key" class="card">
      <div class="card-title">{{ g.label }}</div>
      <div v-for="item in g.items" :key="item.configKey" class="row">
        <div class="row-meta">
          <div class="row-label">{{ item.configLabel }}</div>
          <div class="row-desc">{{ item.description }}</div>
        </div>
        <div class="row-control">
          <!-- 开关 -->
          <el-switch
            v-if="item.configType === 'SWITCH'"
            :model-value="item.configValue === 'true'"
            @change="(v: any) => (item.configValue = v ? 'true' : 'false')"
          />
          <!-- 下拉 -->
          <el-select
            v-else-if="item.configType === 'SELECT'"
            :model-value="item.configValue"
            @change="(v: any) => (item.configValue = v)"
            style="width: 220px"
          >
            <el-option v-for="o in parseOptions(item.configOptions)" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <!-- 数字 -->
          <el-input-number
            v-else-if="item.configType === 'NUMBER'"
            :model-value="toNumber(item.configValue)"
            @change="(v: any) => (item.configValue = String(v ?? 0))"
            :min="0"
          />
          <!-- 文本 -->
          <el-input
            v-else
            :model-value="item.configValue"
            @input="(v: any) => (item.configValue = v)"
            style="width: 320px"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api, type ApiResult } from '../../api';

interface SystemConfigItem {
  id?: number;
  configKey: string;
  configValue: string;
  configType: string;
  configGroup: string;
  configLabel: string;
  configOptions: string;
  defaultValue: string;
  description: string;
  editableBy?: string;
}

const configs = ref<SystemConfigItem[]>([]);
const saving = ref(false);

const GROUP_ORDER: { key: string; label: string }[] = [
  { key: 'ACCOUNT', label: '账号与 Token' },
  { key: 'SMS', label: '短信' },
  { key: 'SECURITY', label: '安全' },
  { key: 'GENERAL', label: '其他' },
];

const groups = ref<{ key: string; label: string; items: SystemConfigItem[] }[]>([]);

function parseOptions(raw: string): { value: string; label: string }[] {
  if (!raw) return [];
  return raw
    .split(',')
    .map((s) => s.split(':'))
    .filter((p) => p.length >= 2)
    .map((p) => ({ value: p[0], label: p.slice(1).join(':') }));
}

function toNumber(v: string): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

function buildGroups() {
  const map = new Map<string, SystemConfigItem[]>();
  for (const c of configs.value) {
    const key = GROUP_ORDER.some((g) => g.key === c.configGroup) ? c.configGroup : 'GENERAL';
    if (!map.has(key)) map.set(key, []);
    map.get(key)!.push(c);
  }
  groups.value = GROUP_ORDER.filter((g) => map.has(g.key)).map((g) => ({
    key: g.key,
    label: g.label,
    items: map.get(g.key)!,
  }));
}

async function load() {
  try {
    const r = await api.get<ApiResult<SystemConfigItem[]>>('/api/v1/admin/system-config/list');
    configs.value = r.data.data || [];
    buildGroups();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载配置失败');
  }
}

async function save() {
  saving.value = true;
  try {
    await api.post('/api/v1/admin/system-config/update', configs.value);
    ElMessage.success('配置已保存');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
h2 { margin: 0; }
p { color: #64748b; font-size: 13px; margin: 4px 0 0; }
.card { background: #fff; border: 1px solid #e2e8f0; border-radius: 10px; padding: 8px 20px 12px; margin-bottom: 16px; }
.card-title { font-weight: 600; color: #0f172a; padding: 12px 0 4px; border-bottom: 1px solid #f1f5f9; margin-bottom: 8px; }
.row { display: flex; justify-content: space-between; align-items: center; padding: 12px 0; border-bottom: 1px dashed #f1f5f9; }
.row:last-child { border-bottom: none; }
.row-label { font-weight: 500; color: #1e293b; }
.row-desc { color: #94a3b8; font-size: 12px; margin-top: 2px; }
</style>
