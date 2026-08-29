<template>
  <div>
    <div class="header">
      <div><h2>设备许可证</h2><p>一张卡密对应一台电脑。给同一手机号分配 1 张或多张卡，使用同一套许可证规则。</p></div>
      <el-button @click="loadBusinesses">刷新</el-button>
    </div>
    <el-alert type="info" :closable="false" class="mb"
      title="PDD 的用户级套餐不在此页管理；这里只显示授权模型为 DEVICE_LICENSE 的业务。解绑不会暂停有效期，续费保留原卡密。" />
    <el-card shadow="never" class="mb">
      <div class="filters">
        <el-select v-model="bizId" placeholder="选择许可证业务" style="width:220px" @change="loadUsers">
          <el-option v-for="b in licenseBusinesses" :key="b.bizId" :label="`${b.businessName} (appId=${b.appId})`" :value="b.bizId" />
        </el-select>
        <el-select v-model="userId" filterable placeholder="选择手机号" style="width:260px" @change="selectUser">
          <el-option v-for="u in users" :key="u.id" :label="`${u.phone}（用户ID ${u.id}）`" :value="u.id" />
        </el-select>
        <el-input v-model="phoneLookup" placeholder="代理可直接输入客户手机号" style="width:210px" @keyup.enter="lookupCustomer" />
        <el-button @click="lookupCustomer">按手机号查找</el-button>
        <el-button type="primary" :disabled="!userId" @click="loadLicenses">查询许可证</el-button>
        <el-button type="success" :disabled="!userId" @click="openAssign">分配卡密/席位</el-button>
        <el-button type="primary" plain :disabled="selectedRows.length === 0" @click="openBatchRenew">批量续费（{{ selectedRows.length }}）</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" row-key="licenseId" border stripe @selection-change="selectedRows=$event">
        <el-table-column type="selection" width="48" />
        <el-table-column prop="licenseId" label="许可证ID" width="95" />
        <el-table-column prop="cardKeyMasked" label="卡密（脱敏）" width="165" />
        <el-table-column prop="packageName" label="套餐" min-width="140" />
        <el-table-column label="状态" width="105"><template #default="s"><el-tag :type="statusType(s.row.status)">{{ s.row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="deviceName" label="设备名称" min-width="130" />
        <el-table-column prop="deviceId" label="设备UUID" min-width="200" show-overflow-tooltip />
        <el-table-column prop="activatedAt" label="激活时间" width="175" />
        <el-table-column prop="expireAt" label="独立到期时间" width="175" />
        <el-table-column prop="remainingCalls" label="剩余次数" width="95" />
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="s">
            <el-button size="small" type="primary" @click="openRenew(s.row)">续费</el-button>
            <el-button size="small" type="warning" :disabled="!s.row.userDeviceId" @click="unbind(s.row)">解绑</el-button>
            <el-button v-if="s.row.status === 'ACTIVE'" size="small" type="warning" @click="changeStatus(s.row,'SUSPENDED')">暂停</el-button>
            <el-button v-if="s.row.status === 'SUSPENDED'" size="small" type="success" @click="changeStatus(s.row,'ACTIVE')">恢复</el-button>
            <el-button size="small" type="danger" :disabled="s.row.status === 'REVOKED'" @click="changeStatus(s.row,'REVOKED')">作废</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="assignVisible" title="给手机号分配设备许可证" width="500px">
      <el-form label-width="110px">
        <el-form-item label="客户手机号">{{ selectedUser?.phone }}</el-form-item>
        <el-form-item label="套餐" required><el-select v-model="assignForm.packageId" style="width:100%"><el-option v-for="p in plans" :key="p.id" :value="p.id" :label="`${p.name} / ${p.callsPerAccount}次 / ${p.durationHours}小时`" /></el-select></el-form-item>
        <el-form-item label="许可证数量" required><el-input-number v-model="assignForm.count" :min="1" :max="500" /></el-form-item>
        <el-form-item label="销售备注"><el-input v-model="assignForm.remark" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="assignVisible=false">取消</el-button><el-button type="primary" @click="assign">生成并分配</el-button></template>
    </el-dialog>

    <el-dialog v-model="generatedVisible" title="新生成的卡密（请立即安全保存）" width="600px" :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" class="mb"
        title="许可证列表只显示脱敏卡密。关闭窗口前请复制并安全发送给客户。" />
      <el-input :model-value="generatedKeys.join('\n')" type="textarea" :rows="Math.min(14, Math.max(4, generatedKeys.length))" readonly />
      <template #footer>
        <el-button @click="copyGeneratedKeys">复制全部</el-button>
        <el-button type="primary" @click="generatedVisible=false">我已保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="renewVisible" :title="batchRenewMode ? `批量续费 ${selectedRows.length} 个许可证` : '原卡密续费'" width="500px">
      <el-form label-width="110px">
        <template v-if="!batchRenewMode">
          <el-form-item label="卡密">{{ renewTarget?.cardKeyMasked }}</el-form-item>
          <el-form-item label="当前到期">{{ renewTarget?.expireAt || '尚未激活' }}</el-form-item>
        </template>
        <el-form-item v-else label="续费对象">已选中 {{ selectedRows.length }} 个独立设备许可证</el-form-item>
        <el-form-item label="续费套餐" required><el-select v-model="renewForm.packageId" style="width:100%"><el-option v-for="p in plans" :key="p.id" :value="p.id" :label="`${p.name} / ${p.callsPerAccount}次 / ${p.durationHours}小时`" /></el-select></el-form-item>
        <el-form-item label="线下流水号"><el-input v-model="renewForm.paymentTxnNo" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="renewForm.remark" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="renewVisible=false">取消</el-button><el-button type="primary" @click="renew">确认续费</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, type ApiResult, type PageResult } from '../../api';
import type { BusinessRuntime, ClientUser, DeviceLicenseItem, PackagePlanLite } from '../../types';

const businesses=ref<BusinessRuntime[]>([]), users=ref<ClientUser[]>([]), plans=ref<PackagePlanLite[]>([]), rows=ref<DeviceLicenseItem[]>([]);
const bizId=ref<number>(), userId=ref<number>(), loading=ref(false), assignVisible=ref(false), renewVisible=ref(false), generatedVisible=ref(false), renewTarget=ref<DeviceLicenseItem>();
const selectedRows=ref<DeviceLicenseItem[]>([]), generatedKeys=ref<string[]>([]), batchRenewMode=ref(false);
const phoneLookup=ref('');
const assignForm=reactive({packageId:undefined as number|undefined,count:1,remark:''});
const renewForm=reactive({packageId:undefined as number|undefined,paymentTxnNo:'',remark:''});
const licenseBusinesses=computed(()=>businesses.value.filter(b=>b.authorizationMode==='DEVICE_LICENSE'));
const selectedUser=computed(()=>users.value.find(u=>u.id===userId.value));
async function loadBusinesses(){const r=await api.get<ApiResult<BusinessRuntime[]>>('/api/v1/admin/business/list');businesses.value=r.data.data;if(!bizId.value&&licenseBusinesses.value.length){bizId.value=licenseBusinesses.value[0].bizId;await loadUsers();}}
async function loadUsers(){userId.value=undefined;rows.value=[];selectedRows.value=[];if(!bizId.value)return;try{const r=await api.get<ApiResult<PageResult<ClientUser>>>('/api/v1/admin/user/list',{params:{bizId:bizId.value,size:100}});users.value=r.data.data.records;}catch{users.value=[];}}
async function lookupCustomer(){const b=licenseBusinesses.value.find(v=>v.bizId===bizId.value);if(!b||!phoneLookup.value)return;const r=await api.get<ApiResult<ClientUser>>('/api/v1/admin/device-licenses/customer',{params:{appId:b.appId,phone:phoneLookup.value.trim()}});if(!users.value.some(u=>u.id===r.data.data.id))users.value.push(r.data.data);userId.value=r.data.data.id;await selectUser();}
async function selectUser(){await Promise.all([loadLicenses(),loadPlans()]);}
async function loadPlans(){if(!bizId.value)return;const r=await api.get<ApiResult<PackagePlanLite[]>>('/api/v1/admin/package/list',{params:{bizId:bizId.value,status:'ACTIVE'}});plans.value=r.data.data;}
async function loadLicenses(){if(!userId.value)return;loading.value=true;try{const r=await api.get<ApiResult<DeviceLicenseItem[]>>(`/api/v1/admin/users/${userId.value}/device-licenses`);rows.value=r.data.data;selectedRows.value=[];}finally{loading.value=false;}}
async function openAssign(){await loadPlans();assignForm.packageId=plans.value[0]?.id;assignForm.count=1;assignForm.remark='';assignVisible.value=true;}
async function assign(){if(!userId.value||!assignForm.packageId)return;const r=await api.post<ApiResult<string[]>>(`/api/v1/admin/users/${userId.value}/device-licenses/batch-assign`,assignForm);generatedKeys.value=r.data.data;generatedVisible.value=true;ElMessage.success(`已分配 ${generatedKeys.value.length} 张卡密`);assignVisible.value=false;await loadLicenses();}
async function copyGeneratedKeys(){try{await navigator.clipboard.writeText(generatedKeys.value.join('\n'));ElMessage.success('全部卡密已复制');}catch{ElMessage.error('复制失败，请手动选中文本复制');}}
async function openRenew(row:DeviceLicenseItem){await loadPlans();batchRenewMode.value=false;renewTarget.value=row;renewForm.packageId=row.packageId;renewForm.paymentTxnNo='';renewForm.remark='';renewVisible.value=true;}
async function openBatchRenew(){if(!selectedRows.value.length)return;await loadPlans();batchRenewMode.value=true;renewTarget.value=undefined;renewForm.packageId=selectedRows.value[0]?.packageId||plans.value[0]?.id;renewForm.paymentTxnNo='';renewForm.remark='';renewVisible.value=true;}
async function renew(){if(!renewForm.packageId)return;if(batchRenewMode.value){if(!selectedRows.value.length)return;await api.post('/api/v1/admin/device-licenses/batch-renew',{licenseIds:selectedRows.value.map(v=>v.licenseId),renewal:renewForm});ElMessage.success(`已续费 ${selectedRows.value.length} 个许可证，原卡密未改变`);}else{if(!renewTarget.value)return;await api.post(`/api/v1/admin/device-licenses/${renewTarget.value.licenseId}/renew`,renewForm);ElMessage.success('续费成功，原卡密未改变');}renewVisible.value=false;await loadLicenses();}
async function unbind(row:DeviceLicenseItem){await ElMessageBox.confirm('解绑后有效期继续流逝，正在推流会被停止。确认继续？','强制解绑',{type:'warning'});await api.post(`/api/v1/admin/device-licenses/${row.licenseId}/unbind`,null,{params:{reason:'管理后台强制解绑'}});await loadLicenses();}
async function changeStatus(row:DeviceLicenseItem,status:'ACTIVE'|'SUSPENDED'|'REVOKED'){const action={ACTIVE:'恢复',SUSPENDED:'暂停',REVOKED:'作废'}[status];const r=await ElMessageBox.prompt(`请输入${action}原因`,`${action}许可证`,{inputPattern:/.{2,}/,inputErrorMessage:'至少2个字符'});await api.put(`/api/v1/admin/device-licenses/${row.licenseId}/status`,{status,reason:r.value});ElMessage.success(`${action}成功`);await loadLicenses();}
function statusType(status:string){return status==='ACTIVE'?'success':status==='UNBOUND'?'info':status==='SUSPENDED'?'warning':'danger';}
onMounted(()=>loadBusinesses().catch(e=>ElMessage.error(e instanceof Error?e.message:'加载失败')));
</script>
<style scoped>.header{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px}.header h2{margin:0;color:#1e293b}.header p{margin:6px 0 0;color:#64748b;font-size:13px}.mb{margin-bottom:16px}.filters{display:flex;gap:12px;align-items:center;flex-wrap:wrap}</style>
