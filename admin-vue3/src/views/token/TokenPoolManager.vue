<template>
  <div class="space-y-4">
    <div class="flex justify-between items-center">
      <div>
        <h2 class="text-xl font-bold text-slate-800">拼多多官方底层 Token 公共调度池</h2>
        <p class="text-xs text-slate-500 mt-1">
          管理底层拼多多采集 Session Token，监控账号健康度、每日调用负载与故障自动拉黑免责
        </p>
      </div>
      <el-button type="primary" :icon="Plus" @click="dialogVisible = true">录入新底层 Token</el-button>
    </div>

    <!-- Token 列表 -->
    <el-card shadow="never" class="border-slate-200">
      <el-table :data="tableData" stripe border style="width: 100%">
        <el-table-column prop="id" label="槽位ID" width="80" />
        <el-table-column prop="accountAlias" label="账号别名" width="160" />
        <el-table-column prop="tokenVal" label="底层 Session Token" min-width="240">
          <template #default="scope">
            <span class="font-mono text-xs text-slate-600 truncate block">{{ scope.row.tokenVal }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="healthStatus" label="健康状态" width="130">
          <template #default="scope">
            <el-tag v-if="scope.row.healthStatus === 'HEALTHY'" type="success">健康 (HEALTHY)</el-tag>
            <el-tag v-else-if="scope.row.healthStatus === 'BUSY'" type="warning">租借中 (BUSY)</el-tag>
            <el-tag v-else type="danger">故障拉黑 (BLACK)</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dailyCallsCount" label="今日调度负荷" width="160">
          <template #default="scope">
            <div class="text-xs">
              <span class="font-medium">{{ scope.row.dailyCallsCount }}</span> / {{ scope.row.dailyMaxCapacity }} 次
              <el-progress :percentage="Math.min(100, Math.round((scope.row.dailyCallsCount / scope.row.dailyMaxCapacity) * 100))" :status="scope.row.dailyCallsCount > 400 ? 'exception' : ''" />
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="riskScore" label="风控分" width="90">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.riskScore > 60 ? 'danger' : 'info'">{{ scope.row.riskScore }}分</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="scope">
            <el-button v-if="scope.row.healthStatus === 'FAULT_BLACK'" type="success" size="small" @click="recoverToken(scope.row)">自愈恢复</el-button>
            <el-button v-else type="danger" size="small" @click="blackToken(scope.row)">手动拉黑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 录入对话框 -->
    <el-dialog v-model="dialogVisible" title="录入拼多多官方底层 Token" width="500px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="账号别名">
          <el-input v-model="form.accountAlias" placeholder="例如: 拼多多采集槽位-06" />
        </el-form-item>
        <el-form-item label="Session Token">
          <el-input v-model="form.tokenVal" type="textarea" :rows="3" placeholder="粘贴官方抓包 Cookie / Token" />
        </el-form-item>
        <el-form-item label="日调用上限">
          <el-input-number v-model="form.dailyMaxCapacity" :min="100" :max="2000" :step="100" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitToken">确认录入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { Plus } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import type { TokenPoolItem } from '../../types';
import { api, type ApiResult, type PageResult } from '../../api';

const dialogVisible = ref(false);

const form = reactive({
  accountAlias: 'PDD-BUYER-SLOT-05',
  tokenVal: 'pdd_sess_tok_99182310294810239120391203',
  dailyMaxCapacity: 500,
});

const tableData = ref<TokenPoolItem[]>([]);

async function load(): Promise<void> {
  try {
    const response = await api.get<ApiResult<PageResult<TokenPoolItem>>>('/api/v1/admin/token/list', { params: { size: 100 } });
    tableData.value = response.data.data.records;
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '资源池加载失败'); }
}

async function submitToken(): Promise<void> {
  try {
    await api.post('/api/v1/admin/token', form);
    dialogVisible.value = false;
    ElMessage.success('底层 Token 已加入公共调度池');
    await load();
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '录入失败'); }
}

async function setStatus(row: TokenPoolItem, status: string): Promise<void> {
  try {
    await api.put(`/api/v1/admin/token/${row.id}/status`, null, { params: { status } });
    ElMessage.success(`资源状态已更新: ${row.accountAlias}`);
    await load();
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '更新失败'); }
}
const blackToken = (row: TokenPoolItem) => setStatus(row, 'FAULT_BLACK');
const recoverToken = (row: TokenPoolItem) => setStatus(row, 'HEALTHY');
onMounted(load);
</script>
