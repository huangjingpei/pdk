<template>
  <div>
    <div class="header">
      <div>
        <h2>业务管理与部署状态</h2>
        <p>数据库开关与部署白名单双重控制。ZHIBO_AI / ZHIBO_LIVE 共用 zhibo 聚合 Handler，但 appId、用户和资源完全隔离。</p>
      </div>
      <div><el-button type="primary" @click="openCreate">新增业务</el-button><el-button @click="load()">刷新</el-button></div>
    </div>

    <el-alert type="info" :closable="false" class="mb"
      title="启用条件：数据库状态 ACTIVE + PDK_ENABLED_BIZ_CODES 包含 bizCode + Handler 已注册且健康。数据库开关不能替代部署开关。" />

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="appId" label="appId" width="75" />
      <el-table-column prop="bizCode" label="bizCode" width="135" />
      <el-table-column prop="businessName" label="业务名称" width="130" />
      <el-table-column prop="businessDescription" label="业务描述" min-width="220" show-overflow-tooltip />
      <el-table-column label="注册策略" width="125">
        <template #default="s"><el-tag :type="s.row.registrationMode === 'SELF_SERVICE' ? 'success' : 'warning'">
          {{ s.row.registrationMode === 'SELF_SERVICE' ? '手机短信自助' : '管理员建号' }}
        </el-tag></template>
      </el-table-column>
      <el-table-column label="试用" width="110">
        <template #default="s">{{ s.row.trialEnabled ? `${s.row.trialDurationHours}小时` : '关闭' }}</template>
      </el-table-column>
      <el-table-column label="部署" width="95">
        <template #default="s"><el-tag :type="s.row.deploymentEnabled ? 'success' : 'info'">{{ s.row.deploymentEnabled ? '已包含' : '未包含' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="Handler" width="105">
        <template #default="s"><el-tag :type="s.row.handlerRegistered && s.row.handlerHealth === 'UP' ? 'success' : 'danger'">
          {{ s.row.handlerRegistered ? s.row.handlerHealth : '缺失' }}
        </el-tag></template>
      </el-table-column>
      <el-table-column label="有效状态" width="150">
        <template #default="s"><el-tooltip :content="s.row.unavailableReason || '可用'">
          <el-tag :type="s.row.effectiveStatus === 'AVAILABLE' ? 'success' : 'danger'">{{ s.row.effectiveStatus }}</el-tag>
        </el-tooltip></template>
      </el-table-column>
      <el-table-column label="客户端动作" min-width="190">
        <template #default="s">{{ (s.row.supportedActions || []).join('、') || '未声明' }}</template>
      </el-table-column>
      <el-table-column label="用户/套餐/资源" width="150">
        <template #default="s">{{ s.row.userCount ?? 0 }} / {{ s.row.packageCount ?? 0 }} / {{ s.row.availableResourceCount ?? 0 }}可用</template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="s">
          <el-button size="small" @click="openEdit(s.row)">配置</el-button>
          <el-button size="small" :type="s.row.configuredStatus === 'ACTIVE' ? 'danger' : 'success'"
            :disabled="s.row.configuredStatus !== 'ACTIVE' && (!s.row.deploymentEnabled || !s.row.handlerRegistered || s.row.handlerHealth !== 'UP')"
            @click="toggle(s.row)">{{ s.row.configuredStatus === 'ACTIVE' ? '关闭' : '启用' }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="editingId ? '编辑业务配置' : '新增业务（默认关闭）'" width="600px">
      <el-form :model="form" label-width="145px">
        <template v-if="!editingId">
          <el-form-item label="appId" required><el-input-number v-model="form.appId" :min="1" /></el-form-item>
          <el-form-item label="bizCode" required><el-input v-model="form.bizCode" placeholder="例如 NEW_BIZ" /></el-form-item>
        </template>
        <el-form-item label="业务名称" required><el-input v-model="form.bizName" /></el-form-item>
        <el-form-item label="业务描述"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="注册策略" required>
          <el-radio-group v-model="form.registrationMode"><el-radio value="SELF_SERVICE">手机短信自助</el-radio><el-radio value="ADMIN_ONLY">仅管理员建号</el-radio></el-radio-group>
        </el-form-item>
        <el-form-item label="开放试用"><el-switch v-model="form.trialEnabled" /></el-form-item>
        <template v-if="form.trialEnabled">
          <el-form-item label="试用时长(小时)"><el-input-number v-model="form.trialDurationHours" :min="1" /></el-form-item>
          <el-form-item label="试用账号数"><el-input-number v-model="form.trialAccountCount" :min="1" /></el-form-item>
          <el-form-item label="单账号次数"><el-input-number v-model="form.trialCallsPerAccount" :min="1" /></el-form-item>
        </template>
        <el-form-item label="首次登录必须改密"><el-switch v-model="form.forceInitialPasswordChange" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, type ApiResult } from '../../api';
import type { BusinessRuntime } from '../../types';

const rows = ref<BusinessRuntime[]>([]); const loading = ref(false); const visible = ref(false); const saving = ref(false); const editingId = ref<number | null>(null);
const form = reactive({ appId: 4, bizCode: '', bizName: '', description: '', registrationMode: 'ADMIN_ONLY' as 'SELF_SERVICE'|'ADMIN_ONLY', trialEnabled: false, trialDurationHours: 0, trialAccountCount: 0, trialCallsPerAccount: 0, forceInitialPasswordChange: true });
async function load(){ loading.value=true; try { const r=await api.get<ApiResult<BusinessRuntime[]>>('/api/v1/admin/business/list'); rows.value=r.data.data; } catch(e){ ElMessage.error(e instanceof Error?e.message:'加载失败'); } finally { loading.value=false; } }
function reset(){ Object.assign(form,{appId:4,bizCode:'',bizName:'',description:'',registrationMode:'ADMIN_ONLY',trialEnabled:false,trialDurationHours:0,trialAccountCount:0,trialCallsPerAccount:0,forceInitialPasswordChange:true}); }
function openCreate(){ editingId.value=null; reset(); visible.value=true; }
function openEdit(row:BusinessRuntime){ editingId.value=row.bizId; Object.assign(form,{appId:row.appId,bizCode:row.bizCode,bizName:row.businessName,description:row.businessDescription||'',registrationMode:row.registrationMode,trialEnabled:row.trialEnabled,trialDurationHours:row.trialDurationHours,trialAccountCount:row.trialAccountCount,trialCallsPerAccount:row.trialCallsPerAccount,forceInitialPasswordChange:row.forceInitialPasswordChange}); visible.value=true; }
async function save(){ saving.value=true; try { const body={...form}; if(editingId.value) await api.put(`/api/v1/admin/business/${editingId.value}`,body); else await api.post('/api/v1/admin/business',body); ElMessage.success('业务配置已保存'); visible.value=false; await load(); } catch(e){ ElMessage.error(e instanceof Error?e.message:'保存失败'); } finally { saving.value=false; } }
async function toggle(row:BusinessRuntime){ const enabled=row.configuredStatus!=='ACTIVE'; let reason=''; try { const r=await ElMessageBox.prompt(`请输入${enabled?'启用':'关闭'} ${row.bizCode} 的原因`, '业务开关审计', {inputPattern:/.{2,}/,inputErrorMessage:'至少输入2个字符',type:'warning'}); reason=r.value; } catch { return; } try { await api.put(`/api/v1/admin/business/${row.bizId}/status`,null,{params:{enabled,reason}}); ElMessage.success(enabled?'业务已启用':'业务已关闭'); await load(); } catch(e){ ElMessage.error(e instanceof Error?e.message:'操作失败'); } }
onMounted(load);
</script>
<style scoped>.header{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;gap:16px}.header h2{margin:0;color:#1e293b}.header p{margin:6px 0 0;color:#64748b;font-size:13px}.mb{margin-bottom:16px}</style>
