<template>
  <div class="mt-3">
    <div class="flex items-center gap-2 mb-1">
      <span class="text-xs font-medium text-slate-500">期待结果：</span>
      <span class="text-xs text-slate-600">{{ expected }}</span>
    </div>
    <div v-if="res" class="flex items-center gap-2 mb-2">
      <el-tag size="small" :type="matched ? 'success' : 'danger'">
        {{ matched ? '✅ 符合期待' : '❌ 不符期待' }}
      </el-tag>
      <span class="text-xs text-slate-500">实际 code = {{ res.code }}（{{ res.ok ? '成功' : '被拦截/异常' }}）</span>
    </div>
    <div class="p-3 bg-slate-900 text-emerald-400 font-mono text-xs rounded-lg overflow-x-auto">
      <div class="text-slate-400 mb-1">// 真实后端响应：</div>
      <pre>{{ pretty }}</pre>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

interface ApiResp { ok: boolean; code: number; message: string; data: any }

const props = defineProps<{
  res: ApiResp | null;
  expected: string;
  expectCode?: number;
}>();

const pretty = computed(() => {
  if (!props.res) return '';
  return JSON.stringify({ code: props.res.code, message: props.res.message, data: props.res.data }, null, 2);
});

// 有期待码时按码比对；无期待码时不强制判定（仅展示）
const matched = computed(() => {
  if (!props.res) return false;
  if (typeof props.expectCode === 'number') return props.res.code === props.expectCode;
  return props.res.ok;
});
</script>
