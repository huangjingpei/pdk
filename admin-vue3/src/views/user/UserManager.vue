<template>
  <div>
    <div class="page-title">
      <div>
        <h2>客户端用户与电脑</h2>
        <p>管理客户端注册用户及其绑定电脑；可手工建号、调整套餐/次数/期限、冻结账号。套餐字段由卡密激活自动写入，此处仅作例外人工调整。</p>
      </div>
      <div class="flex gap-2">
        <el-button v-if="isSuperAdmin" type="primary" :icon="Plus" @click="openCreate">新增用户</el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </div>

    <el-card shadow="never" class="border-slate-200 mb-4">
      <div class="flex flex-wrap items-center gap-3">
        <el-input v-model="keyword" placeholder="模糊搜索：手机号 / 设备ID / 套餐名" clearable style="width: 300px"
          @keyup.enter="onSearch" @clear="onSearch">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="statusFilter" placeholder="账号状态" clearable style="width: 150px" @change="onSearch">
          <el-option label="正常 (ACTIVE)" value="ACTIVE" />
          <el-option label="试用 (TRIAL)" value="TRIAL" />
          <el-option label="冻结 (FROZEN)" value="FROZEN" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="onSearch">搜索</el-button>
        <el-button :icon="RefreshLeft" @click="resetFilter">重置</el-button>
        <span class="text-xs text-slate-400 ml-auto">共 {{ total }} 条</span>
      </div>
    </el-card>

    <el-card shadow="never" class="border-slate-200">
      <el-table v-loading="loading" :data="rows" border stripe style="width: 100%">
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="scope">
            <el-tag v-if="scope.row.status === 'ACTIVE'" type="success">正常</el-tag>
            <el-tag v-else-if="scope.row.status === 'TRIAL'" type="warning">试用</el-tag>
            <el-tag v-else type="danger">冻结</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="roleCode" label="身份" width="100" />
        <el-table-column prop="invitationCode" label="代理邀请码" width="130" />
        <el-table-column prop="invitedByPhone" label="邀请代理" width="130" />
        <el-table-column prop="currentPackageName" label="套餐" min-width="170" />
        <el-table-column prop="remainingCalls" label="剩余次数" width="100" />
        <el-table-column prop="maxAccounts" label="并发账号" width="90" />
        <el-table-column prop="deviceId" label="绑定电脑" min-width="200">
          <template #default="scope">
            <span class="font-mono text-xs text-slate-600 break-all">{{ scope.row.deviceId || '未绑定' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="expireTime" label="到期时间" width="180" />
        <el-table-column prop="createdAt" label="注册时间" width="180" />
        <el-table-column label="操作" width="470" fixed="right">
          <template #default="scope">
            <div class="action-cell">
              <el-button type="info" size="small" @click="openDetail(scope.row)">套餐详情</el-button>
              <el-button v-if="canUnbind && scope.row.deviceId" type="warning" size="small" @click="unbind(scope.row.id)">解绑</el-button>
              <el-button v-if="canManagePartner && scope.row.roleCode === 'CUSTOMER'" type="primary" size="small" @click="changeRole(scope.row.id, 'PARTNER')">升级代理</el-button>
              <el-button v-if="canManagePartner && scope.row.roleCode === 'PARTNER'" type="danger" size="small" @click="changeRole(scope.row.id, 'CUSTOMER')">取消代理</el-button>
              <el-button v-if="canEdit" type="success" size="small" @click="openAdjust(scope.row)">调整套餐</el-button>
              <el-button v-if="isSuperAdmin" :type="scope.row.status === 'FROZEN' ? 'primary' : 'danger'" size="small"
                @click="toggleStatus(scope.row)">{{ scope.row.status === 'FROZEN' ? '解冻' : '冻结' }}</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div class="mt-3 flex items-center justify-between">
        <span class="text-xs text-slate-400">已加载 {{ rows.length }} 条</span>
        <el-pagination layout="total, sizes, prev, pager, next, jumper" :total="total" :page-size="pageSize"
          :page-sizes="[20, 50, 100]" :current-page="page" @current-change="onPageChange" @size-change="onSizeChange" />
      </div>
    </el-card>

    <!-- 新增用户对话框 -->
    <el-dialog v-model="createVisible" title="新增客户端用户" width="460px">
      <el-form :model="createForm" label-width="96px">
        <el-form-item label="手机号" required>
          <el-input v-model="createForm.phone" placeholder="11 位手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="初始密码" required>
          <el-input v-model="createForm.password" type="password" show-password placeholder="6-32 位" />
        </el-form-item>
        <el-form-item label="预绑设备ID">
          <el-input v-model="createForm.deviceId" placeholder="可选，留空则登录时绑定" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">确认创建</el-button>
      </template>
    </el-dialog>

    <!-- 调整套餐对话框 -->
    <el-dialog v-model="adjustVisible" title="调整套餐 / 次数 / 期限" width="520px">
      <el-alert type="info" :closable="false" class="mb-3"
        :title="`当前：套餐=${adjustTarget?.currentPackageName || '未开通'} ，剩余=${adjustTarget?.remainingCalls ?? 0} 次，到期=${adjustTarget?.expireTime || '—'}`" />
      <el-form :model="adjustForm" label-width="110px">
        <el-form-item label="绑定/更换套餐">
          <el-select v-model="adjustForm.packagePlanId" placeholder="不改动套餐请留空" clearable filterable style="width: 100%">
            <el-option v-for="p in plans" :key="p.id" :label="`${p.name}（${p.accountCount}号×${p.callsPerAccount}次，${Math.round(p.durationHours / 24)}天）`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="额外补次数">
          <el-input-number v-model="adjustForm.extraCalls" :min="-100000" :max="100000" :step="100" style="width: 100%" />
          <span class="text-xs text-slate-400 ml-2">正数补次，负数扣减（下限 0）</span>
        </el-form-item>
        <el-form-item label="延长天数">
          <el-input-number v-model="adjustForm.extendDays" :min="0" :max="3650" :step="10" style="width: 100%" />
          <span class="text-xs text-slate-400 ml-2">在现有到期时间上顺延</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="adjusting" @click="submitAdjust">确认调整</el-button>
      </template>
    </el-dialog>

    <!-- 套餐使用详情抽屉 -->
    <el-drawer v-model="detailVisible" title="客户当前套餐使用详情" size="640px" direction="rtl">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border size="small" class="mb-4">
            <el-descriptions-item label="手机号">{{ detail.phone || '-' }}</el-descriptions-item>
            <el-descriptions-item label="套餐">{{ detail.currentPackageName || '未开通' }}</el-descriptions-item>
            <el-descriptions-item label="剩余总次数">
              <span class="font-semibold text-emerald-600">{{ detail.remainingCalls }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="已用 / 总分配">
              {{ detail.totalUsed }} / {{ detail.totalAllocated }}
            </el-descriptions-item>
            <el-descriptions-item label="到期时间" :span="2">{{ detail.expireTime || '-' }}</el-descriptions-item>
          </el-descriptions>

          <div class="flex items-center justify-between mb-2">
            <h3 class="text-sm font-semibold text-slate-700">底层小号明细（{{ detail.accounts.length }} 个）</h3>
            <span class="text-xs text-slate-400">每个槽位：已分配 / 已用 / 剩余</span>
          </div>

          <el-table :data="detail.accounts" border size="small" max-height="420">
            <el-table-column prop="slotIndex" label="槽位" width="60" />
            <el-table-column label="小号UUID" min-width="180">
              <template #default="scope">
                <span class="font-mono text-xs text-slate-600 break-all">{{ scope.row.uuid || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="accountAlias" label="别名" min-width="120" />
            <el-table-column label="健康" width="100">
              <template #default="scope">
                <el-tag v-if="scope.row.healthStatus === 'HEALTHY'" type="success" size="small">正常</el-tag>
                <el-tag v-else-if="scope.row.healthStatus === 'BUSY'" type="warning" size="small">占用</el-tag>
                <el-tag v-else-if="scope.row.healthStatus === 'FAULT_BLACK'" type="danger" size="small">拉黑</el-tag>
                <el-tag v-else-if="scope.row.healthStatus === 'EXPIRED'" type="info" size="small">过期</el-tag>
                <span v-else>{{ scope.row.healthStatus || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="已分配" width="80" prop="allocatedCalls" />
            <el-table-column label="已用" width="70" prop="usedCalls" />
            <el-table-column label="剩余" width="70">
              <template #default="scope">
                <span class="font-semibold" :class="scope.row.remaining <= 0 ? 'text-rose-600' : 'text-emerald-600'">
                  {{ scope.row.remaining }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="100" />
          </el-table>

          <el-alert v-if="detail.accounts.length === 0" type="info" :closable="false" class="mt-3"
            title="该客户当前没有 ACTIVE 的小号分配（未开通套餐或已全部回收/释放）。" />
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Search, Refresh, RefreshLeft } from '@element-plus/icons-vue';
import { api, type ApiResult, type PageResult } from '../../api';
import { hasPermission, authState } from '../../auth';
import type { ClientUser, PackagePlanLite, UserAssignmentDetail } from '../../types';

const rows = ref<ClientUser[]>([]);
const loading = ref(false);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
const keyword = ref('');
const statusFilter = ref('');

const canEdit = computed(() => hasPermission('user:edit'));
const canUnbind = computed(() => hasPermission('user:unbind'));
const canManagePartner = computed(() => hasPermission('partner:manage'));
// 仅超级管理员可执行「新增用户」与「删除（冻结/解冻）」——按角色严格控制，不依赖权限位分配
const isSuperAdmin = computed(() => authState.session?.role === 'SUPER_ADMIN');

async function load(p = 1): Promise<void> {
  page.value = p;
  loading.value = true;
  try {
    const params: Record<string, unknown> = { page: p, size: pageSize.value };
    if (keyword.value.trim()) params.keyword = keyword.value.trim();
    if (statusFilter.value) params.status = statusFilter.value;
    const response = await api.get<ApiResult<PageResult<ClientUser>>>('/api/v1/admin/user/list', { params });
    rows.value = response.data.data.records;
    total.value = response.data.data.total;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function onSearch(): void { load(1); }
function resetFilter(): void { keyword.value = ''; statusFilter.value = ''; load(1); }
const onPageChange = (p: number) => load(p);
const onSizeChange = (s: number) => { pageSize.value = s; load(1); };

// ---- 新增用户 ----
const createVisible = ref(false);
const creating = ref(false);
const createForm = ref({ phone: '', password: '', deviceId: '' });

function openCreate(): void {
  createForm.value = { phone: '', password: '', deviceId: '' };
  createVisible.value = true;
}

async function submitCreate(): Promise<void> {
  if (!/^1[3-9]\d{9}$/.test(createForm.value.phone)) {
    ElMessage.error('请输入正确的 11 位手机号');
    return;
  }
  if (createForm.value.password.length < 6) {
    ElMessage.error('初始密码至少 6 位');
    return;
  }
  creating.value = true;
  try {
    await api.post('/api/v1/admin/user', createForm.value);
    ElMessage.success('客户账号已创建');
    createVisible.value = false;
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '创建失败');
  } finally {
    creating.value = false;
  }
}

// ---- 套餐使用详情 ----
const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<UserAssignmentDetail | null>(null);

async function openDetail(row: ClientUser): Promise<void> {
  detailVisible.value = true;
  detailLoading.value = true;
  detail.value = null;
  try {
    const response = await api.get<ApiResult<UserAssignmentDetail>>(`/api/v1/admin/user/${row.id}/assignments`);
    detail.value = response.data.data;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载套餐详情失败');
  } finally {
    detailLoading.value = false;
  }
}

// ---- 调整套餐 ----
const adjustVisible = ref(false);
const adjusting = ref(false);
const plans = ref<PackagePlanLite[]>([]);
const adjustTarget = ref<ClientUser | null>(null);
const adjustForm = ref({ id: 0, packagePlanId: null as number | null, extraCalls: 0, extendDays: 0 });

async function openAdjust(row: ClientUser): Promise<void> {
  adjustTarget.value = row;
  adjustForm.value = { id: row.id, packagePlanId: null, extraCalls: 0, extendDays: 0 };
  try {
    const res = await api.get<ApiResult<PackagePlanLite[]>>('/api/v1/admin/package/list?status=ACTIVE');
    plans.value = res.data.data;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载套餐列表失败');
    return;
  }
  adjustVisible.value = true;
}

async function submitAdjust(): Promise<void> {
  adjusting.value = true;
  try {
    await api.put(`/api/v1/admin/user/${adjustForm.value.id}/adjust`, {
      packagePlanId: adjustForm.value.packagePlanId,
      extraCalls: adjustForm.value.extraCalls,
      extendDays: adjustForm.value.extendDays,
    });
    ElMessage.success('用户权益已调整');
    adjustVisible.value = false;
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '调整失败');
  } finally {
    adjusting.value = false;
  }
}

// ---- 冻结 / 解冻 ----
async function toggleStatus(row: ClientUser): Promise<void> {
  const target = row.status === 'FROZEN' ? 'ACTIVE' : 'FROZEN';
  const label = target === 'FROZEN' ? '冻结' : '解冻';
  try {
    await ElMessageBox.confirm(`确认将用户 ${row.phone} ${label}？${target === 'FROZEN' ? '冻结后该账号将无法登录与调用。' : ''}`, label, { type: 'warning' });
  } catch {
    return;
  }
  try {
    await api.put(`/api/v1/admin/user/${row.id}/status`, null, { params: { status: target } });
    ElMessage.success(`已${label}`);
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : `${label}失败`);
  }
}

// ---- 解绑电脑 ----
async function unbind(id: number): Promise<void> {
  try {
    await ElMessageBox.confirm('确认解除该用户的电脑绑定？原客户端会话将失效。', '解绑电脑', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await api.post(`/api/v1/admin/user/${id}/unbind-device`);
    ElMessage.success('解绑成功');
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '解绑失败');
  }
}

// ---- 升级 / 取消代理 ----
async function changeRole(id: number, role: string): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认将用户身份调整为 ${role}？`, '身份审核', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await api.put(`/api/v1/admin/user/${id}/role`, null, { params: { role } });
    ElMessage.success('身份已更新');
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '身份更新失败');
  }
}

onMounted(load);
</script>

<style scoped>
.page-title { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-wrap: wrap; gap: 12px; }
h2 { margin: 0; color: #1e293b; }
p { margin: 6px 0 0; color: #64748b; font-size: 13px; max-width: 720px; }
.action-cell { display: flex; flex-wrap: nowrap; gap: 5px; }
.action-cell .el-button { flex: 1 1 0; min-width: 0; padding: 0 4px; margin: 0; }
</style>
