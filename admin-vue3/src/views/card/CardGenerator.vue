<template>
  <div class="space-y-4">
    <div class="flex justify-between items-center">
      <div>
        <h2 class="text-xl font-bold text-slate-800">卡密批量制卡与凭证池 (纯状态表)</h2>
        <p class="text-xs text-slate-500 mt-1">
          管理卡密凭证的生成、售卖与核销状态，本表与财务实收表完全解耦
        </p>
      </div>
      <el-button type="primary" :icon="Key" @click="dialogVisible = true">批量生成新卡密</el-button>
    </div>

    <!-- 卡密列表 -->
    <el-card shadow="never" class="border-slate-200">
      <el-table :data="tableData" stripe border style="width: 100%">
        <el-table-column prop="cardKey" label="卡密序列号" width="220">
          <template #default="scope">
            <span class="font-mono font-medium text-indigo-600">{{ scope.row.cardKey }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="packageId" label="套餐类型" width="180">
          <template #default="scope">
            <span v-if="scope.row.packageId === 1">20元天卡（50次/天）</span>
            <span v-else-if="scope.row.packageId === 2">200元月卡（10号×30次/天）</span>
            <span v-else-if="scope.row.packageId === 3">500元季卡（30号×50次/天）</span>
            <span v-else>1500元年卡（100号×100次/天）</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="使用状态" width="110">
          <template #default="scope">
            <el-tag v-if="scope.row.status === 'UNUSED'" type="success">待售未使用</el-tag>
            <el-tag v-else-if="scope.row.status === 'ACTIVATED'" type="info">已核销激活</el-tag>
            <el-tag v-else type="danger">已作废</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="activatedByPhone" label="核销手机号" width="130">
          <template #default="scope">
            <span>{{ scope.row.activatedByPhone || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="activatedAt" label="核销时间" width="170">
          <template #default="scope">
            <span>{{ scope.row.activatedAt || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="generatedByAdmin" label="制卡账号" width="110" />
        <el-table-column prop="createdAt" label="制卡时间" width="170" />
      </el-table>
    </el-card>

    <!-- 批量制卡对话框 -->
    <el-dialog v-model="dialogVisible" title="批量生成卡密" width="500px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="套餐模版">
          <el-select v-model="form.packageId" style="width: 100%">
            <el-option :value="1" label="20元天卡（单日50次）" />
            <el-option :value="2" label="200元月卡（10账号×30次/天）" />
            <el-option :value="3" label="500元季卡（30账号×50次/天）" />
            <el-option :value="4" label="1500元年卡（100账号×100次/天）" />
          </el-select>
        </el-form-item>
        <el-form-item label="制卡数量">
          <el-input-number v-model="form.count" :min="1" :max="100" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleBatchGenerate">立即批量生成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue';
import { Key } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { CardKeyItem } from '../../types';

const dialogVisible = ref(false);

const form = reactive({
  packageId: 2,
  count: 5,
});

const tableData = ref<CardKeyItem[]>([
  {
    id: 1,
    cardKey: 'PDK-8891-2041-9982',
    packageId: 2,
    status: 'ACTIVATED',
    generatedByAdmin: 'super_admin',
    activatedByPhone: '13800138000',
    activatedAt: '2026-08-15 14:10:22',
    createdAt: '2026-08-15 10:00:00',
  },
  {
    id: 2,
    cardKey: 'PDK-7712-9901-3310',
    packageId: 2,
    status: 'UNUSED',
    generatedByAdmin: 'super_admin',
    createdAt: '2026-08-15 10:00:00',
  },
  {
    id: 3,
    cardKey: 'PDK-1120-4499-8821',
    packageId: 3,
    status: 'UNUSED',
    generatedByAdmin: 'agent_beijing',
    createdAt: '2026-08-15 11:30:00',
  },
]);

const handleBatchGenerate = () => {
  for (let i = 0; i < form.count; i++) {
    const r1 = Math.random().toString(36).substring(2, 6).toUpperCase();
    const r2 = Math.random().toString(36).substring(2, 6).toUpperCase();
    const r3 = Math.random().toString(36).substring(2, 6).toUpperCase();
    tableData.value.unshift({
      id: Date.now() + i,
      cardKey: `PDK-${r1}-${r2}-${r3}`,
      packageId: form.packageId,
      status: 'UNUSED',
      generatedByAdmin: 'super_admin',
      createdAt: new Date().toLocaleString(),
    });
  }
  dialogVisible.value = false;
  ElMessage.success(`成功批量生成 ${form.count} 张新卡密`);
};
</script>
