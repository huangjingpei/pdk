<template>
  <div>
    <div class="page-title">
      <div>
        <h2>管理员与合伙人</h2>
        <p>管理后台登录账号。系统只有两种身份：超级管理员（全部权限）与代理商/合伙人（受限权限）。
          账号由超级管理员在此手工创建，合伙人不再以客户端身份登录后台，所有人进入的都是同一套管理后台，仅可见内容不同。</p>
      </div>
      <div class="flex gap-2">
        <el-button type="primary" :icon="Plus" @click="openCreate">新增账号</el-button>
        <el-button :icon="Refresh" @click="load()">刷新</el-button>
      </div>
    </div>

    <el-card shadow="never" class="border-slate-200">
      <el-table v-loading="loading" :data="rows" border stripe style="width: 100%">
        <el-table-column prop="username" label="登录账号" min-width="160" />
        <el-table-column prop="displayName" label="显示名称" min-width="140" />
        <el-table-column label="身份" width="140">
          <template #default="scope">
            <el-tag :type="roleTag(scope.row.roleCode).type">{{ roleTag(scope.row.roleCode).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="所属业务" width="160"><template #default="s">{{ s.row.roleCode === 'SUPER_ADMIN' ? '全部业务' : businessName(s.row.bizId) }}</template></el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="scope">
            <el-tag :type="statusTag(scope.row.status).type">{{ statusTag(scope.row.status).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginAt" label="最后登录" min-width="180" />
        <el-table-column prop="createdAt" label="创建时间" min-width="180" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="scope">
            <div class="action-cell">
              <el-button type="primary" size="small" @click="openRole(scope.row)">改角色</el-button>
              <el-button :type="scope.row.status === 'ACTIVE' ? 'danger' : 'success'" size="small"
                @click="toggleStatus(scope.row)">{{ scope.row.status === 'ACTIVE' ? '禁用' : '启用' }}</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增账号对话框 -->
    <el-dialog v-model="createVisible" title="新增后台账号" width="460px">
      <el-form :model="createForm" label-width="92px">
        <el-form-item label="登录账号" required>
          <el-input v-model="createForm.username" placeholder="3-32 位字母/数字/下划线" maxlength="32" />
        </el-form-item>
        <el-form-item label="初始密码" required>
          <el-input v-model="createForm.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="显示名称" required>
          <el-input v-model="createForm.displayName" placeholder="如：张三 / 渠道A" maxlength="64" />
        </el-form-item>
        <el-form-item label="身份">
          <el-select v-model="createForm.roleCode" style="width: 100%">
            <el-option v-for="r in roleOptions" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="createForm.roleCode === 'PARTNER'" label="所属业务" required><el-select v-model="createForm.bizId" style="width:100%"><el-option v-for="b in businesses" :key="b.bizId" :label="`${b.businessName} (${b.bizCode})`" :value="b.bizId" /></el-select></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">确认创建</el-button>
      </template>
    </el-dialog>

    <!-- 改角色对话框 -->
    <el-dialog v-model="roleVisible" title="调整后台账号身份" width="420px">
      <el-alert type="info" :closable="false" class="mb-3"
        :title="`当前账号：${roleTarget?.username}（${roleTarget ? roleTag(roleTarget.roleCode).text : ''}）`" />
      <el-form :model="roleForm" label-width="80px">
        <el-form-item label="身份">
          <el-select v-model="roleForm.roleCode" style="width: 100%">
            <el-option v-for="r in roleOptions" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="roleForm.roleCode === 'PARTNER'" label="所属业务" required><el-select v-model="roleForm.bizId" style="width:100%"><el-option v-for="b in businesses" :key="b.bizId" :label="`${b.businessName} (${b.bizCode})`" :value="b.bizId" /></el-select></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleVisible = false">取消</el-button>
        <el-button type="primary" :loading="adjusting" @click="submitRole">确认调整</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh } from '@element-plus/icons-vue';
import { api, type ApiResult } from '../../api';
import type { AdminAccount, BusinessRuntime } from '../../types';

const rows = ref<AdminAccount[]>([]);
const loading = ref(false);
const businesses=ref<BusinessRuntime[]>([]); const businessName=(id?:number)=>businesses.value.find(b=>b.bizId===id)?.businessName||'未绑定';

const roleOptions = [
  { value: 'SUPER_ADMIN', label: '超级管理员（全部权限）' },
  { value: 'PARTNER', label: '代理商 / 合伙人（受限权限）' },
];

function roleTag(role: string): { text: string; type: 'danger' | 'warning' } {
  return role === 'SUPER_ADMIN'
    ? { text: '超级管理员', type: 'danger' }
    : { text: '代理商', type: 'warning' };
}

function statusTag(status: string): { text: string; type: 'success' | 'info' } {
  return status === 'ACTIVE'
    ? { text: '启用', type: 'success' }
    : { text: '禁用', type: 'info' };
}

async function load(): Promise<void> {
  loading.value = true;
  try {
    const response = await api.get<ApiResult<AdminAccount[]>>('/api/v1/admin/admins/list');
    rows.value = response.data.data;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

// ---- 新增账号 ----
const createVisible = ref(false);
const creating = ref(false);
const createForm = ref({ username: '', password: '', displayName: '', roleCode: 'PARTNER', bizId: undefined as number|undefined });

function openCreate(): void {
  createForm.value = { username: '', password: '', displayName: '', roleCode: 'PARTNER', bizId: businesses.value[0]?.bizId };
  createVisible.value = true;
}

async function submitCreate(): Promise<void> {
  if (!/^[A-Za-z0-9_]{3,32}$/.test(createForm.value.username)) {
    ElMessage.error('登录账号为 3-32 位字母/数字/下划线');
    return;
  }
  if (createForm.value.password.length < 6) {
    ElMessage.error('初始密码至少 6 位');
    return;
  }
  creating.value = true;
  try {
    await api.post('/api/v1/admin/admins', createForm.value);
    ElMessage.success('后台账号已创建');
    createVisible.value = false;
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '创建失败');
  } finally {
    creating.value = false;
  }
}

// ---- 改角色 ----
const roleVisible = ref(false);
const adjusting = ref(false);
const roleTarget = ref<AdminAccount | null>(null);
const roleForm = ref({ id: 0, roleCode: 'PARTNER', bizId: undefined as number|undefined });

function openRole(row: AdminAccount): void {
  roleTarget.value = row;
  roleForm.value = { id: row.id, roleCode: row.roleCode, bizId: row.bizId || businesses.value[0]?.bizId };
  roleVisible.value = true;
}

async function submitRole(): Promise<void> {
  adjusting.value = true;
  try {
    await api.put(`/api/v1/admin/admins/${roleForm.value.id}/role`, null,
      { params: { role: roleForm.value.roleCode, bizId: roleForm.value.roleCode === 'PARTNER' ? roleForm.value.bizId : undefined } });
    ElMessage.success('身份已更新');
    roleVisible.value = false;
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '调整失败');
  } finally {
    adjusting.value = false;
  }
}

// ---- 启用 / 禁用 ----
async function toggleStatus(row: AdminAccount): Promise<void> {
  const target = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
  const label = target === 'ACTIVE' ? '启用' : '禁用';
  try {
    await ElMessageBox.confirm(`确认${label}账号 ${row.username}？`, label, { type: 'warning' });
  } catch {
    return;
  }
  try {
    await api.put(`/api/v1/admin/admins/${row.id}/status`, null, { params: { status: target } });
    ElMessage.success(`已${label}`);
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : `${label}失败`);
  }
}

onMounted(async()=>{const b=await api.get<ApiResult<BusinessRuntime[]>>('/api/v1/admin/business/list');businesses.value=b.data.data;await load()});
</script>

<style scoped>
.page-title { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-wrap: wrap; gap: 12px; }
h2 { margin: 0; color: #1e293b; }
p { margin: 6px 0 0; color: #64748b; font-size: 13px; max-width: 760px; }
.action-cell { display: flex; flex-wrap: nowrap; gap: 6px; }
.action-cell .el-button { flex: 1 1 0; min-width: 0; padding: 0 6px; margin: 0; }
</style>
