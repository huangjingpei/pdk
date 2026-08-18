<template>
  <div class="space-y-4">
    <div class="flex justify-between items-center">
      <div>
        <h2 class="text-xl font-bold text-slate-800">{{ partnerView ? '我的销售与续费记录' : '实收收入对账流水 (独立财务表)' }}</h2>
        <p class="text-xs text-slate-500 mt-1">
          记录每一笔已激活卡密的真实财务实收金额，支持正价、折价及免费赠送归因对账
        </p>
      </div>
    </div>

    <!-- 筛选搜索 -->
    <el-card shadow="never" class="border-slate-200">
      <el-form :inline="true" :model="queryForm" class="demo-form-inline">
        <el-form-item label="销售类型">
          <el-select v-model="queryForm.orderType" placeholder="全部类型" clearable style="width: 160px">
            <el-option label="正价售卖 (NORMAL_SALE)" value="NORMAL_SALE" />
            <el-option label="折价优惠 (DISCOUNT_SALE)" value="DISCOUNT_SALE" />
            <el-option label="商务赠送 (GIFT_FREE)" value="GIFT_FREE" />
            <el-option label="套餐续费 (RENEWAL)" value="RENEWAL" />
          </el-select>
        </el-form-item>

        <el-form-item label="流水/手机号/卡密">
          <el-input v-model="queryForm.searchKey" placeholder="搜索流水号/卡密/手机号" clearable style="width: 220px" />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="resetSearch">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 数据表格 -->
    <el-card shadow="never" class="border-slate-200">
      <el-table :data="tableData" stripe border style="width: 100%">
        <el-table-column prop="incomeOrderNo" label="财务流水号" width="180" />
        <el-table-column prop="cardKey" label="卡密序列号" width="170" />
        <el-table-column prop="userPhone" label="充值手机号" width="120" />
        <el-table-column prop="packageName" label="套餐名称" min-width="180" />
        <el-table-column prop="faceValue" label="官方面值" width="90">
          <template #default="scope">
            <span>¥{{ scope.row.faceValue }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="amount" label="实收金额" width="100">
          <template #default="scope">
            <span class="font-bold text-emerald-600">¥{{ scope.row.amount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="orderType" label="销售类型" width="120">
          <template #default="scope">
            <el-tag v-if="scope.row.orderType === 'NORMAL_SALE'" type="success" size="small">正价全款</el-tag>
            <el-tag v-else-if="scope.row.orderType === 'DISCOUNT_SALE'" type="warning" size="small">折价让利</el-tag>
            <el-tag v-else-if="scope.row.orderType === 'RENEWAL'" type="primary" size="small">套餐续费</el-tag>
            <el-tag v-else type="info" size="small">商务赠送 (0元)</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="paymentChannel" label="支付通道" width="100">
          <template #default="scope">
            <el-tag size="small">{{ scope.row.paymentChannel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="activatedAt" label="核销入账时间" width="170" />
        <el-table-column prop="auditAdmin" label="制卡归属" width="100" />
      </el-table>

      <div class="mt-4 flex justify-end">
        <el-pagination background layout="prev, pager, next" :total="tableData.length" :page-size="10" />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { Search, Refresh } from '@element-plus/icons-vue';
import type { FinancialIncome } from '../../types';
import { api, type ApiResult, type PageResult } from '../../api';
import { ElMessage } from 'element-plus';
import { useRoute } from 'vue-router';

const route = useRoute();
const partnerView = route.path === '/sales';

const queryForm = reactive({
  orderType: '',
  searchKey: '',
});

const tableData = ref<FinancialIncome[]>([]);

async function handleSearch(): Promise<void> {
  try {
    const params = { size: 100, orderType: queryForm.orderType || undefined, searchKey: queryForm.searchKey || undefined };
    const endpoint = partnerView ? '/api/v1/admin/sales/list' : '/api/v1/admin/finance/incomes';
    const response = await api.get<ApiResult<PageResult<FinancialIncome>>>(endpoint, { params });
    tableData.value = response.data.data.records;
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '收入流水加载失败'); }
}

const resetSearch = async () => {
  queryForm.orderType = '';
  queryForm.searchKey = '';
  await handleSearch();
};
onMounted(handleSearch);
</script>
