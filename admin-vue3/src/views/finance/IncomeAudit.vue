<template>
  <div class="space-y-4">
    <div class="flex justify-between items-center">
      <div>
        <h2 class="text-xl font-bold text-slate-800">实收收入对账流水 (独立财务表)</h2>
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
import { ref, reactive } from 'vue';
import { Search, Refresh } from '@element-plus/icons-vue';
import { FinancialIncome } from '../../types';

const queryForm = reactive({
  orderType: '',
  searchKey: '',
});

const tableData = ref<FinancialIncome[]>([
  {
    id: 1,
    incomeOrderNo: 'INC-177112001928-8812',
    cardKeyId: 101,
    cardKey: 'PDK-8891-2041-9982',
    userPhone: '13800138000',
    packageId: 2,
    packageName: '200元月卡（多账号防控版）',
    faceValue: 200.00,
    amount: 200.00,
    discountAmount: 0.00,
    orderType: 'NORMAL_SALE',
    paymentChannel: 'ALIPAY',
    auditAdmin: 'super_admin',
    activatedAt: '2026-08-15 14:10:22',
  },
  {
    id: 2,
    incomeOrderNo: 'INC-177112003810-5519',
    cardKeyId: 102,
    cardKey: 'PDK-9921-7712-4410',
    userPhone: '13911223344',
    packageId: 3,
    packageName: '500元季卡（高并发工作室版）',
    faceValue: 500.00,
    amount: 450.00,
    discountAmount: 50.00,
    orderType: 'DISCOUNT_SALE',
    paymentChannel: 'WECHAT_PAY',
    auditAdmin: 'agent_beijing',
    activatedAt: '2026-08-15 13:45:10',
  },
  {
    id: 3,
    incomeOrderNo: 'INC-177112009981-1209',
    cardKeyId: 103,
    cardKey: 'PDK-1102-3399-5588',
    userPhone: '13700008888',
    packageId: 1,
    packageName: '20元天卡（高频体验版）',
    faceValue: 20.00,
    amount: 0.00,
    discountAmount: 20.00,
    orderType: 'GIFT_FREE',
    paymentChannel: 'OFFLINE',
    auditAdmin: 'super_admin',
    activatedAt: '2026-08-15 11:20:00',
  },
]);

const handleSearch = () => {
  // 执行后台查询
};

const resetSearch = () => {
  queryForm.orderType = '';
  queryForm.searchKey = '';
};
</script>
