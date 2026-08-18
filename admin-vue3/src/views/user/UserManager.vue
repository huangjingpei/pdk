<template>
  <div>
    <div class="page-title">
      <div><h2>客户端用户与电脑绑定</h2><p>客服可查询用户状态，具备权限的角色可强制解绑电脑。</p></div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>
    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" border stripe>
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column prop="status" label="状态" width="90" />
        <el-table-column prop="roleCode" label="身份" width="100" />
        <el-table-column prop="invitationCode" label="代理邀请码" width="130" />
        <el-table-column prop="invitedByPhone" label="邀请代理" width="130" />
        <el-table-column prop="currentPackageName" label="套餐" min-width="180" />
        <el-table-column prop="remainingCalls" label="剩余次数" width="100" />
        <el-table-column prop="deviceId" label="绑定电脑" min-width="190" />
        <el-table-column prop="expireTime" label="到期时间" width="180" />
        <el-table-column label="操作" width="250">
          <template #default="scope">
            <el-button v-if="canUnbind && scope.row.deviceId" type="warning" size="small" @click="unbind(scope.row.id)">解绑</el-button>
            <el-button v-if="canManagePartner && scope.row.roleCode === 'CUSTOMER'" type="primary" size="small" @click="changeRole(scope.row.id,'PARTNER')">升级代理</el-button>
            <el-button v-if="canManagePartner && scope.row.roleCode === 'PARTNER'" type="danger" size="small" @click="changeRole(scope.row.id,'CUSTOMER')">取消代理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { api, type ApiResult, type PageResult } from '../../api';
import { hasPermission } from '../../auth';

interface ClientUser { id: number; phone: string; status: string; roleCode:string; invitationCode?:string; invitedByPhone?:string; currentPackageName?: string; remainingCalls: number; deviceId?: string; expireTime?: string }
const rows = ref<ClientUser[]>([]);
const loading = ref(false);
const canUnbind = computed(() => hasPermission('user:unbind'));
const canManagePartner = computed(() => hasPermission('partner:manage'));

async function load(): Promise<void> {
  loading.value = true;
  try {
    const response = await api.get<ApiResult<PageResult<ClientUser>>>('/api/v1/admin/user/list');
    rows.value = response.data.data.records;
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '加载失败'); }
  finally { loading.value = false; }
}

async function unbind(id: number): Promise<void> {
  await ElMessageBox.confirm('确认解除该用户的电脑绑定？原客户端会话将失效。', '解绑电脑', { type: 'warning' });
  await api.post(`/api/v1/admin/user/${id}/unbind-device`);
  ElMessage.success('解绑成功');
  await load();
}

async function changeRole(id:number, role:string):Promise<void>{
  await ElMessageBox.confirm(`确认将用户身份调整为 ${role}？`,'身份审核',{type:'warning'});
  try{await api.put(`/api/v1/admin/user/${id}/role`,null,{params:{role}});ElMessage.success('身份已更新');await load()}
  catch(error){ElMessage.error(error instanceof Error?error.message:'身份更新失败')}
}

onMounted(load);
</script>

<style scoped>
.page-title { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
h2 { margin: 0; color: #1e293b; } p { margin: 6px 0 0; color: #64748b; font-size: 13px; }
</style>
