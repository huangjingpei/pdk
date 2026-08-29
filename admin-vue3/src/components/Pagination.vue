<template>
  <div class="unified-pagination">
    <span class="up-total">共 {{ total }} 条 · {{ totalPages }} 页</span>
    <el-button class="up-first" size="default" :disabled="page <= 1" @click="goFirst">首页</el-button>
    <el-pagination
      background
      layout="sizes, prev, pager, next, jumper"
      :total="total"
      :page-size="pageSize"
      :current-page="page"
      :page-sizes="pageSizes"
      @current-change="onCurrentChange"
      @size-change="onSizeChange"
    />
    <el-button class="up-last" size="default" :disabled="page >= totalPages" @click="goLast">末页</el-button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = withDefaults(
  defineProps<{
    total: number;
    page: number;
    pageSize: number;
    pageSizes?: number[];
  }>(),
  {
    pageSizes: () => [10, 20, 50, 100],
  },
);

const emit = defineEmits<{
  'update:page': [value: number];
  'update:pageSize': [value: number];
  change: [payload: { page: number; pageSize: number }];
}>();

const totalPages = computed(() => Math.max(1, Math.ceil((props.total || 0) / (props.pageSize || 1))));

function emitChange(nextPage: number, nextSize: number): void {
  emit('update:page', nextPage);
  emit('update:pageSize', nextSize);
  emit('change', { page: nextPage, pageSize: nextSize });
}

function onCurrentChange(p: number): void {
  emitChange(p, props.pageSize);
}

function onSizeChange(s: number): void {
  emitChange(1, s);
}

function goFirst(): void {
  if (props.page !== 1) emitChange(1, props.pageSize);
}

function goLast(): void {
  if (props.page !== totalPages.value) emitChange(totalPages.value, props.pageSize);
}
</script>

<style scoped>
.unified-pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 12px;
  flex-wrap: wrap;
}
.up-total {
  font-size: 13px;
  color: #64748b;
  margin-right: auto;
}
</style>
