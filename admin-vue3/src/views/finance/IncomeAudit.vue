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
        <el-form-item label="业务"><el-select v-model="queryForm.bizId" placeholder="全部业务" clearable style="width:180px"><el-option v-for="b in businesses" :key="b.bizId" :label="`${b.businessName} (${b.appId})`" :value="b.bizId" /></el-select></el-form-item>

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
        <el-table-column label="业务" width="140"><template #default="s">{{ businessName(s.row.bizId) }}</template></el-table-column>
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

      <Pagination class="mt-4" v-model:page="page" v-model:page-size="pageSize" :total="total" @change="(q) => handleSearch(q.page)" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { Search, Refresh } from '@element-plus/icons-vue';
import type { FinancialIncome, BusinessRuntime } from '../../types';
import { api, type ApiResult, type PageResult } from '../../api';
import Pagination from '../../components/Pagination.vue';
import { ElMessage } from 'element-plus';
import { useRoute } from 'vue-router';

const route = useRoute();
const partnerView = route.path === '/sales';

const queryForm = reactive({
  orderType: '',
  searchKey: '',
  bizId: '' as number|'',
});
const businesses=ref<BusinessRuntime[]>([]); const businessName=(id:number)=>businesses.value.find(b=>b.bizId===id)?.businessName||`业务#${id}`;

const tableData = ref<FinancialIncome[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);

async function handleSearch(p: number = 1): Promise<void> {
  try {
    const params = { page: p, size: pageSize.value, orderType: queryForm.orderType || undefined, searchKey: queryForm.searchKey || undefined, bizId: queryForm.bizId || undefined };
    const endpoint = partnerView ? '/api/v1/admin/sales/list' : '/api/v1/admin/finance/incomes';
    const response = await api.get<ApiResult<PageResult<FinancialIncome>>>(endpoint, { params });
    tableData.value = response.data.data.records;
    total.value = response.data.data.total;
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '收入流水加载失败'); }
}

const resetSearch = async () => {
  queryForm.orderType = '';
  queryForm.searchKey = '';
  queryForm.bizId = '';
  await handleSearch();
};
onMounted(async()=>{const b=await api.get<ApiResult<BusinessRuntime[]>>('/api/v1/admin/business/list');businesses.value=b.data.data;await handleSearch()});
</script>
