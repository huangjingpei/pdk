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
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="scope">
            <el-button v-if="scope.row.status === 'ACTIVATED'" type="primary" size="small" @click="openRenew(scope.row)">续费</el-button>
            <el-button v-if="scope.row.status !== 'VOID'" type="danger" size="small" @click="voidCard(scope.row)">作废</el-button>
          </template>
        </el-table-column>
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

    <el-dialog v-model="renewVisible" title="原卡密续费" width="480px">
      <el-alert type="info" :closable="false" title="续费后卡密保持不变，并生成新的续费销售流水" />
      <el-form label-width="100px" style="margin-top:16px">
        <el-form-item label="卡密"><el-input :model-value="renewCardKey" disabled /></el-form-item>
        <el-form-item label="套餐版本"><el-select v-model="renewPackageId" style="width:100%"><el-option v-for="p in plans" :key="p.id" :value="p.id" :label="`${p.name} V${p.versionNo} / ¥${p.salePrice}`" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="renewVisible=false">取消</el-button><el-button type="primary" @click="renew">确认线下收款并续费</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { Key } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import type { CardKeyItem } from '../../types';
import { api, type ApiResult, type PageResult } from '../../api';
import { ElMessageBox } from 'element-plus';

const dialogVisible = ref(false);
const renewVisible = ref(false);
const renewCardKey = ref('');
const renewPackageId = ref<number>();
interface Plan { id:number; name:string; versionNo:number; salePrice:number }
const plans = ref<Plan[]>([]);

const form = reactive({
  packageId: 2,
  count: 5,
});

const tableData = ref<CardKeyItem[]>([]);

async function load(): Promise<void> {
  try {
    const response = await api.get<ApiResult<PageResult<CardKeyItem>>>('/api/v1/admin/card/list', { params: { size: 100 } });
    tableData.value = response.data.data.records;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '卡密列表加载失败');
  }
}

async function loadPlans(): Promise<void> {
  const response = await api.get<ApiResult<Plan[]>>('/api/v1/admin/package/list', { params: { status: 'ACTIVE' } });
  plans.value = response.data.data;
}

async function openRenew(row: CardKeyItem): Promise<void> {
  renewCardKey.value = row.cardKey;
  await loadPlans();
  renewPackageId.value = plans.value[0]?.id;
  renewVisible.value = true;
}

async function renew(): Promise<void> {
  if (!renewPackageId.value) return;
  try { await api.post(`/api/v1/admin/card/${renewCardKey.value}/renew`, { packageId: renewPackageId.value }); renewVisible.value=false; ElMessage.success('原卡密续费成功'); await load(); }
  catch(error){ ElMessage.error(error instanceof Error ? error.message : '续费失败'); }
}

async function voidCard(row: CardKeyItem): Promise<void> {
  await ElMessageBox.confirm('作废已激活卡密会立即终止授权并释放小号，确认继续？','作废卡密',{type:'warning'});
  try { await api.put(`/api/v1/admin/card/${row.cardKey}/void`); ElMessage.success('卡密已作废'); await load(); }
  catch(error){ ElMessage.error(error instanceof Error ? error.message : '作废失败'); }
}

async function handleBatchGenerate(): Promise<void> {
  try {
    const response = await api.post<ApiResult<string[]>>('/api/v1/admin/card/batch-generate', form);
    dialogVisible.value = false;
    ElMessage.success(`成功批量生成 ${response.data.data.length} 张新卡密`);
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '制卡失败');
  }
}

onMounted(load);
</script>
