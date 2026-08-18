<template>
  <div class="space-y-4">
    <div class="flex justify-between items-center">
      <div>
        <h2 class="text-xl font-bold text-slate-800">Token 采购支出管理 (对公采购成本)</h2>
        <p class="text-xs text-slate-500 mt-1">
          记录向上游渠道或号商采购底层拼多多账号 Token 的真实支出，计算单账号摊销成本
        </p>
      </div>
      <el-button type="primary" :icon="Plus" @click="dialogVisible = true">录入采购支出</el-button>
    </div>

    <!-- 支出表格 -->
    <el-card shadow="never" class="border-slate-200">
      <el-table :data="tableData" stripe border style="width: 100%">
        <el-table-column prop="expenseOrderNo" label="采购流水号" width="190" />
        <el-table-column prop="category" label="支出类目" width="140">
          <template #default="scope">
            <el-tag size="small" type="danger">{{ scope.row.category }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="tokenBatchId" label="Token 批次号" width="140" />
        <el-table-column prop="tokenCount" label="采购数量" width="90" />
        <el-table-column prop="supplierName" label="供应商名称" min-width="160" />
        <el-table-column prop="unitCost" label="采购单价" width="100">
          <template #default="scope">
            <span>¥{{ scope.row.unitCost.toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="totalCost" label="支出总金额" width="110">
          <template #default="scope">
            <span class="font-bold text-rose-600">¥{{ scope.row.totalCost.toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="purchaser" label="经办采购人" width="110" />
        <el-table-column prop="purchasedAt" label="采购发生时间" width="170" />
      </el-table>
    </el-card>

    <!-- 录入对话框 -->
    <el-dialog v-model="dialogVisible" title="新增 Token 采购支出记账" width="500px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="采购数量">
          <el-input-number v-model="form.tokenCount" :min="10" :max="10000" :step="50" style="width: 100%" />
        </el-form-item>
        <el-form-item label="采购单价 (元)">
          <el-input-number v-model="form.unitCost" :precision="2" :step="0.01" :min="0.05" style="width: 100%" />
        </el-form-item>
        <el-form-item label="供应商名称">
          <el-input v-model="form.supplierName" placeholder="例如: 拼客云联网络 / 极速商贸" />
        </el-form-item>
        <el-form-item label="经办采购人">
          <el-input v-model="form.purchaser" placeholder="当前管理员" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitExpense">确认入账</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue';
import { Plus } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { CompanyExpense } from '../../types';

const dialogVisible = ref(false);

const form = reactive({
  tokenCount: 100,
  unitCost: 0.18,
  supplierName: '拼客云联供应链',
  purchaser: 'super_admin',
});

const tableData = ref<CompanyExpense[]>([
  {
    id: 1,
    expenseOrderNo: 'EXP-17711100223-9081',
    category: 'TOKEN_PURCHASE',
    tokenBatchId: 'BATCH-TK2026A',
    tokenCount: 500,
    supplierName: '拼客云联供应链',
    unitCost: 0.18,
    totalCost: 90.00,
    purchaser: 'super_admin',
    purchasedAt: '2026-08-14 10:00:00',
  },
  {
    id: 2,
    expenseOrderNo: 'EXP-17710988123-1102',
    category: 'TOKEN_PURCHASE',
    tokenBatchId: 'BATCH-TK2026B',
    tokenCount: 1000,
    supplierName: '极速数码电商服务',
    unitCost: 0.16,
    totalCost: 160.00,
    purchaser: 'finance_manager',
    purchasedAt: '2026-08-12 15:30:00',
  },
]);

const submitExpense = () => {
  const total = form.tokenCount * form.unitCost;
  tableData.value.unshift({
    id: Date.now(),
    expenseOrderNo: 'EXP-' + Date.now() + '-' + Math.floor(Math.random() * 9000 + 1000),
    category: 'TOKEN_PURCHASE',
    tokenBatchId: 'BATCH-' + Math.random().toString(36).substring(2, 8).toUpperCase(),
    tokenCount: form.tokenCount,
    supplierName: form.supplierName,
    unitCost: form.unitCost,
    totalCost: total,
    purchaser: form.purchaser,
    purchasedAt: new Date().toLocaleString(),
  });
  dialogVisible.value = false;
  ElMessage.success('采购支出录入成功，对公账目已平衡');
};
</script>
