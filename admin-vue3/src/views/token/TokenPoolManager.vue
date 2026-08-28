<template>
  <div class="space-y-4">
    <div class="flex justify-between items-center">
      <div>
        <h2 class="text-xl font-bold text-slate-800">多业务账号 / Token 资源池</h2>
        <p class="text-xs text-slate-500 mt-1">
          按业务隔离公司账号资产，监控账号健康度、每日调用负载与故障自动拉黑免责
        </p>
      </div>
      <div class="flex gap-2">
        <el-button type="primary" :icon="Plus" @click="dialogVisible = true">录入新底层 Token</el-button>
        <el-button type="success" :icon="Upload" @click="importDialogVisible = true">导入 TXT</el-button>
        <el-button type="danger" :icon="Delete" :disabled="selectedRows.length === 0" @click="batchDiscard">
          批量删除(废弃)
        </el-button>
      </div>
    </div>

    <el-card shadow="never" class="border-slate-200 mb-4">
      <div class="flex flex-wrap items-center gap-3">
        <el-input v-model="keyword" placeholder="模糊搜索：账号别名 / UUID" clearable style="width: 280px"
          @keyup.enter="onSearch" @clear="onSearch">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="statusFilter" placeholder="健康状态" clearable style="width: 160px" @change="onSearch">
          <el-option label="健康 (HEALTHY)" value="HEALTHY" />
          <el-option label="租借中 (BUSY)" value="BUSY" />
          <el-option label="故障拉黑 (FAULT_BLACK)" value="FAULT_BLACK" />
        </el-select>
        <el-select v-model="discardFilter" placeholder="是否废弃" clearable style="width: 140px" @change="onSearch">
          <el-option label="在用" value="0" />
          <el-option label="已废弃" value="1" />
        </el-select>
        <el-select v-model="businessFilter" placeholder="全部业务" clearable style="width:180px" @change="onSearch"><el-option v-for="b in businesses" :key="b.bizId" :label="`${b.businessName} (${b.appId})`" :value="b.bizId" /></el-select>
        <el-button type="primary" :icon="Search" @click="onSearch">搜索</el-button>
        <el-button :icon="RefreshLeft" @click="resetFilter">重置</el-button>
        <span class="text-xs text-slate-400 ml-auto">共 {{ total }} 条</span>
      </div>
    </el-card>

    <el-card shadow="never" class="border-slate-200">
      <el-table :data="tableData" stripe border style="width: 100%" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="48" />
        <el-table-column label="业务" width="140"><template #default="s">{{ businessName(s.row.bizId) }}</template></el-table-column>
        <el-table-column prop="id" label="槽位ID" width="80" />
        <el-table-column prop="uuid" label="UUID" min-width="320">
          <template #default="scope">
            <div class="flex items-center gap-1">
              <span class="font-mono text-xs text-slate-700 break-all leading-5">{{ scope.row.uuid }}</span>
              <el-button link type="primary" size="small" :icon="CopyDocument" title="复制完整 UUID"
                @click="copyText(scope.row.uuid)">复制</el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="accountAlias" label="账号别名" width="160" />
        <el-table-column prop="tokenVal" label="业务凭证（脱敏）" min-width="200">
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
        <el-table-column label="是否废弃" width="90">
          <template #default="scope">
            <el-tag v-if="scope.row.isDiscarded === 1" type="info">已废弃</el-tag>
            <el-tag v-else type="success">在用</el-tag>
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
        <el-table-column label="操作" width="210">
          <template #default="scope">
            <el-button v-if="scope.row.healthStatus === 'FAULT_BLACK'" type="success" size="small" @click="recoverToken(scope.row)">自愈恢复</el-button>
            <el-button v-else type="danger" size="small" @click="blackToken(scope.row)">手动拉黑</el-button>
            <el-button v-if="scope.row.isDiscarded === 1" type="primary" size="small" @click="restoreToken(scope.row)">恢复</el-button>
            <el-button v-else type="warning" size="small" @click="discardToken(scope.row)">废弃</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="mt-3 flex items-center justify-between">
        <span class="text-xs text-slate-400">已选 {{ selectedRows.length }} 条</span>
        <el-pagination
          layout="total, sizes, prev, pager, next, jumper"
          :total="total"
          :page-size="pageSize"
          :page-sizes="[20, 50, 100]"
          :current-page="page"
          @current-change="onPageChange"
          @size-change="onSizeChange" />
      </div>
    </el-card>

    <!-- 录入对话框 -->
    <el-dialog v-model="dialogVisible" title="录入业务账号凭证" width="500px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="所属业务"><el-select v-model="form.appId" style="width:100%"><el-option v-for="b in availableBusinesses" :key="b.appId" :label="`${b.businessName} (appId=${b.appId})`" :value="b.appId" /></el-select></el-form-item>
        <el-form-item label="账号别名">
          <el-input v-model="form.accountAlias" placeholder="例如: 拼多多采集槽位-06" />
        </el-form-item>
        <el-form-item label="凭证类型">
          <el-select v-model="form.credentialType" style="width:100%"><el-option label="Token" value="TOKEN" /><el-option label="Cookie" value="COOKIE" /><el-option label="账号密码" value="ACCOUNT_PASSWORD" /><el-option label="JSON" value="JSON" /></el-select>
        </el-form-item>
        <el-form-item label="凭证内容">
          <el-input v-model="form.tokenVal" type="textarea" :rows="3" placeholder="粘贴该业务 Handler 所需的凭证载荷" />
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

    <!-- 导入对话框 -->
    <el-dialog v-model="importDialogVisible" title="从 TXT 批量导入小号" width="540px">
      <el-alert type="info" :closable="false" class="mb-3"
        title="文件格式"
        description="每行一条：账号别名------账号token（中间用 6 个连字符 ------ 或中文破折号 —— 分隔）。支持 UTF-8 / GBK 编码。" />
      <el-upload
        :auto-upload="false"
        :limit="1"
        accept=".txt"
        :on-change="handleFileChange"
        :on-remove="handleFileRemove"
        drag>
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">将 .txt 文件拖到此处，或<em>点击选择</em></div>
      </el-upload>
      <el-form label-width="110px" class="mt-4">
        <el-form-item label="所属业务"><el-select v-model="importForm.appId" style="width:100%"><el-option v-for="b in availableBusinesses" :key="b.appId" :label="`${b.businessName} (appId=${b.appId})`" :value="b.appId" /></el-select></el-form-item>
        <el-form-item label="日调用上限">
          <el-input-number v-model="importForm.dailyMaxCapacity" :min="100" :max="2000" :step="100" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" :disabled="!importFile" @click="submitImport">开始导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, reactive, onMounted } from 'vue';
import { Plus, Upload, UploadFilled, Delete, Search, RefreshLeft, CopyDocument } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { TokenPoolItem, BusinessRuntime } from '../../types';
import { api, type ApiResult, type PageResult } from '../../api';

const dialogVisible = ref(false);
const importDialogVisible = ref(false);
const importing = ref(false);
const importFile = ref<File | null>(null);
const selectedRows = ref<TokenPoolItem[]>([]);
const tableData = ref<TokenPoolItem[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(50);
const keyword = ref('');
const statusFilter = ref('');
const discardFilter = ref('');
const businessFilter = ref<number|''>('');
const businesses = ref<BusinessRuntime[]>([]);
const availableBusinesses = computed(()=>businesses.value.filter(b=>b.effectiveStatus==='AVAILABLE'));
const businessName=(id:number)=>businesses.value.find(b=>b.bizId===id)?.businessName||`业务#${id}`;

const form = reactive({
  appId: 1,
  accountAlias: 'PDD-BUYER-SLOT-05',
  tokenVal: 'pdd_sess_tok_99182310294810239120391203',
  credentialType: 'TOKEN',
  dailyMaxCapacity: 500,
});
const importForm = reactive({ appId: 1, dailyMaxCapacity: 500 });

async function load(p = 1): Promise<void> {
  page.value = p;
  try {
    const params: Record<string, unknown> = { page: p, size: pageSize.value };
    if (keyword.value.trim()) params.keyword = keyword.value.trim();
    if (statusFilter.value) params.status = statusFilter.value;
    if (discardFilter.value !== '') params.discarded = discardFilter.value;
    if (businessFilter.value) params.bizId = businessFilter.value;
    const response = await api.get<ApiResult<PageResult<TokenPoolItem>>>('/api/v1/admin/token/list', { params });
    tableData.value = response.data.data.records;
    total.value = response.data.data.total;
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '资源池加载失败'); }
}

function onSearch(): void { load(1); }
function resetFilter(): void { keyword.value = ''; statusFilter.value = ''; discardFilter.value = ''; businessFilter.value=''; load(1); }
const onPageChange = (p: number) => load(p);
const onSizeChange = (s: number) => { pageSize.value = s; load(1); };

function handleSelectionChange(rows: TokenPoolItem[]): void {
  selectedRows.value = rows;
}

async function copyText(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success('UUID 已复制到剪贴板');
  } catch {
    ElMessage.error('复制失败，请手动选择文本');
  }
}

function handleFileChange(file: { raw?: File; name?: string }): void {
  const raw = file.raw as File | undefined;
  if (!raw) return;
  if (!raw.name.toLowerCase().endsWith('.txt')) {
    ElMessage.error('请上传 .txt 文件');
    return;
  }
  importFile.value = raw;
}
function handleFileRemove(): void {
  importFile.value = null;
}

async function submitImport(): Promise<void> {
  if (!importFile.value) {
    ElMessage.error('请先选择 txt 文件');
    return;
  }
  const fd = new FormData();
  fd.append('file', importFile.value);
  fd.append('dailyMaxCapacity', String(importForm.dailyMaxCapacity));
  fd.append('appId', String(importForm.appId));
  importing.value = true;
  try {
    const res = await api.post<ApiResult<{ imported: number; skipped: number }>>('/api/v1/admin/token/import', fd);
    const d = res.data.data;
    ElMessage.success(`导入完成：成功 ${d.imported} 条，跳过 ${d.skipped} 条格式错误`);
    importDialogVisible.value = false;
    importFile.value = null;
    await load();
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '导入失败'); }
  finally { importing.value = false; }
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

async function discardToken(row: TokenPoolItem): Promise<void> {
  try {
    await api.put(`/api/v1/admin/token/${row.id}/discard`);
    ElMessage.success(`已逻辑废弃: ${row.accountAlias}（记录保留）`);
    await load();
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '操作失败'); }
}
async function restoreToken(row: TokenPoolItem): Promise<void> {
  try {
    await api.put(`/api/v1/admin/token/${row.id}/restore`);
    ElMessage.success(`已恢复: ${row.accountAlias}`);
    await load();
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '操作失败'); }
}

async function batchDiscard(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认逻辑废弃选中的 ${selectedRows.value.length} 条小号？记录将保留在表中供查看，且不再参与调度。`,
      '批量删除(废弃)',
      { type: 'warning', confirmButtonText: '确认废弃', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  const ids = selectedRows.value.map((r) => r.id);
  try {
    await api.post('/api/v1/admin/token/batch-discard', ids);
    ElMessage.success(`已逻辑废弃 ${ids.length} 条（记录保留）`);
    await load();
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '操作失败'); }
}

onMounted(async()=>{const b=await api.get<ApiResult<BusinessRuntime[]>>('/api/v1/admin/business/list');businesses.value=b.data.data;form.appId=availableBusinesses.value[0]?.appId||1;importForm.appId=form.appId;await load()});
</script>
