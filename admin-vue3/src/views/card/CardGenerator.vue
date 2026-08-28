<template>
  <div class="space-y-4">
    <div class="flex justify-between items-center">
      <div>
        <h2 class="text-xl font-bold text-slate-800">多业务套餐激活码与凭证池</h2>
        <p class="text-xs text-slate-500 mt-1">
          管理激活码的生成、售卖与激活状态，本表与财务实收表完全解耦
        </p>
      </div>
      <div><el-select v-model="businessFilter" clearable placeholder="全部业务" style="width:180px;margin-right:8px" @change="load"><el-option v-for="b in businesses" :key="b.bizId" :label="`${b.businessName} (${b.appId})`" :value="b.bizId" /></el-select><el-button type="primary" :icon="Key" @click="openGenerate">批量生成新激活码</el-button></div>
    </div>

    <!-- 卡密列表 -->
    <el-card shadow="never" class="border-slate-200">
      <el-table :data="tableData" stripe border style="width: 100%">
        <el-table-column label="业务" width="140"><template #default="s">{{ businessName(s.row.bizId) }}</template></el-table-column>
        <el-table-column prop="cardKey" label="卡密序列号" width="220">
          <template #default="scope">
            <span class="font-mono font-medium text-indigo-600">{{ scope.row.cardKey }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="packageId" label="套餐模板" width="220">
          <template #default="scope">
            <span>{{ packageName(scope.row.packageId) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="使用状态" width="110">
          <template #default="scope">
            <el-tag v-if="scope.row.status === 'UNUSED'" type="success">待售未使用</el-tag>
            <el-tag v-else-if="scope.row.status === 'ACTIVATED'" type="info">已激活</el-tag>
            <el-tag v-else type="danger">已作废</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="activatedByPhone" label="激活手机号" width="130">
          <template #default="scope">
            <span>{{ scope.row.activatedByPhone || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="activatedAt" label="激活时间" width="170">
          <template #default="scope">
            <span>{{ scope.row.activatedAt || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="generatedByAdmin" label="生成账号" width="110" />
        <el-table-column prop="createdAt" label="生成时间" width="170" />
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="scope">
            <el-button v-if="scope.row.status === 'ACTIVATED'" type="primary" size="small" @click="openRenew(scope.row)">续费</el-button>
            <el-button v-if="scope.row.status !== 'VOID'" type="danger" size="small" @click="voidCard(scope.row)">作废</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 批量生成对话框 -->
    <el-dialog v-model="dialogVisible" title="批量生成激活码" width="500px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="套餐模板">
          <el-select v-model="form.packageId" style="width: 100%" placeholder="请选择你在套餐模板中心建立的模板">
            <el-option v-for="p in plans" :key="p.id" :value="p.id" :label="`${p.name} ¥${p.salePrice}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="生成数量">
          <el-input-number v-model="form.count" :min="1" :max="100" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleBatchGenerate">立即批量生成</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="renewVisible" title="原激活码续费" width="480px">
      <el-alert type="info" :closable="false" title="续费后激活码保持不变，并生成新的续费销售流水" />
      <el-form label-width="100px" style="margin-top:16px">
        <el-form-item label="激活码"><el-input :model-value="renewCardKey" disabled /></el-form-item>
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
import type { BusinessRuntime } from '../../types';
interface Plan { id:number; bizId:number; name:string; versionNo:number; salePrice:number }
const plans = ref<Plan[]>([]);
const businesses=ref<BusinessRuntime[]>([]); const businessFilter=ref<number|''>('');
const businessName=(id:number)=>businesses.value.find(b=>b.bizId===id)?.businessName||`业务#${id}`;

const form = reactive({
  packageId: undefined as number | undefined,
  count: 5,
});

const tableData = ref<CardKeyItem[]>([]);

async function load(): Promise<void> {
  try {
    const response = await api.get<ApiResult<PageResult<CardKeyItem>>>('/api/v1/admin/card/list', { params: { size: 100, bizId: businessFilter.value || undefined } });
    tableData.value = response.data.data.records;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '激活码列表加载失败');
  }
}

async function loadPlans(bizId?: number): Promise<void> {
  const response = await api.get<ApiResult<Plan[]>>('/api/v1/admin/package/list', { params: { status: 'ACTIVE', bizId } });
  plans.value = response.data.data;
}

function packageName(id: number): string {
  const p = plans.value.find((x) => x.id === id);
  return p ? `${p.name} ¥${p.salePrice}` : `套餐#${id}`;
}

function openGenerate(): void {
  loadPlans(businessFilter.value || undefined);
  dialogVisible.value = true;
}

async function openRenew(row: CardKeyItem): Promise<void> {
  renewCardKey.value = row.cardKey;
  await loadPlans(row.bizId);
  renewPackageId.value = plans.value[0]?.id;
  renewVisible.value = true;
}

async function renew(): Promise<void> {
  if (!renewPackageId.value) return;
  try { await api.post(`/api/v1/admin/card/${renewCardKey.value}/renew`, { packageId: renewPackageId.value }); renewVisible.value=false; ElMessage.success('原激活码续费成功'); await load(); }
  catch(error){ ElMessage.error(error instanceof Error ? error.message : '续费失败'); }
}

async function voidCard(row: CardKeyItem): Promise<void> {
  await ElMessageBox.confirm('作废已激活的激活码会立即终止授权并释放小号，确认继续？','作废激活码',{type:'warning'});
  const appId=businesses.value.find(b=>b.bizId===row.bizId)?.appId;
  try { await api.put(`/api/v1/admin/card/${row.cardKey}/void`,null,{params:{appId}}); ElMessage.success('激活码已作废'); await load(); }
  catch(error){ ElMessage.error(error instanceof Error ? error.message : '作废失败'); }
}

async function handleBatchGenerate(): Promise<void> {
  try {
    const response = await api.post<ApiResult<string[]>>('/api/v1/admin/card/batch-generate', form);
    dialogVisible.value = false;
    ElMessage.success(`成功批量生成 ${response.data.data.length} 个新激活码`);
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '生成失败');
  }
}

onMounted(async () => {
  const b=await api.get<ApiResult<BusinessRuntime[]>>('/api/v1/admin/business/list'); businesses.value=b.data.data;
  await load();
  await loadPlans();
});
</script>
