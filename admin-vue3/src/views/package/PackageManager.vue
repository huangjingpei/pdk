<template>
  <div>
    <div class="header"><div><h2>套餐版本中心</h2><p>任一价格、折扣、时间、账号数或次数变化，都创建新版本；历史版本只能停用。</p></div><div><el-select v-model="bizId" clearable placeholder="全部业务" style="width:180px;margin-right:8px" @change="load"><el-option v-for="b in businesses" :key="b.bizId" :label="`${b.businessName} (${b.appId})`" :value="b.bizId" /></el-select><el-button type="primary" @click="openCreate">创建套餐版本</el-button></div></div>
    <el-table :data="rows" border stripe>
      <el-table-column label="业务" width="140"><template #default="s">{{ businessName(s.row.bizId) }}</template></el-table-column>
      <el-table-column prop="name" label="套餐" />
      <el-table-column prop="versionNo" label="版本" width="80" />
      <el-table-column prop="listPrice" label="原价" width="90" />
      <el-table-column prop="discountRate" label="折扣%" width="90" />
      <el-table-column prop="salePrice" label="售价" width="90" />
      <el-table-column prop="durationHours" label="时长(小时)" width="110" />
      <el-table-column prop="accountCount" label="账号数" width="90" />
      <el-table-column prop="callsPerAccount" label="单号次数" width="100" />
      <el-table-column prop="status" label="状态" width="90" />
      <el-table-column label="操作" width="100"><template #default="s"><el-button v-if="s.row.status==='ACTIVE'" type="warning" size="small" @click="disable(s.row.id)">停用</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="dialog" title="创建新的不可变套餐版本" width="520px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="所属业务"><el-select v-model="form.appId" style="width:100%"><el-option v-for="b in availableBusinesses" :key="b.appId" :label="`${b.businessName} (appId=${b.appId})`" :value="b.appId" /></el-select></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="原价"><el-input-number v-model="form.listPrice" :min="0.01" :precision="2" /></el-form-item>
        <el-form-item label="折扣百分比"><el-input-number v-model="form.discountRate" :min="0.01" :max="100" :precision="2" /></el-form-item>
        <el-form-item label="时长（小时）"><el-input-number v-model="form.durationHours" :min="1" /></el-form-item>
        <el-form-item label="独占账号数"><el-input-number v-model="form.accountCount" :min="1" /></el-form-item>
        <el-form-item label="单账号次数"><el-input-number v-model="form.callsPerAccount" :min="1" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="create">创建</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api, type ApiResult } from '../../api';
import type { BusinessRuntime } from '../../types';
interface Plan { id:number; bizId:number; name:string; versionNo:number; listPrice:number; discountRate:number; salePrice:number; durationHours:number; accountCount:number; callsPerAccount:number; status:string }
const rows=ref<Plan[]>([]); const dialog=ref(false);
const businesses=ref<BusinessRuntime[]>([]); const bizId=ref<number|''>(''); const availableBusinesses=computed(()=>businesses.value.filter(b=>b.effectiveStatus==='AVAILABLE'));
const form=reactive({appId:1,name:'月度套餐',listPrice:200,discountRate:100,durationHours:720,accountCount:1,callsPerAccount:100,description:''});
async function load(){try{const r=await api.get<ApiResult<Plan[]>>('/api/v1/admin/package/list',{params:{bizId:bizId.value||undefined}});rows.value=r.data.data}catch(e){ElMessage.error(e instanceof Error?e.message:'加载失败')}}
function businessName(id:number){return businesses.value.find(b=>b.bizId===id)?.businessName||`业务#${id}`}
function openCreate(){form.appId=availableBusinesses.value[0]?.appId||1;dialog.value=true}
async function create(){try{await api.post('/api/v1/admin/package',form);dialog.value=false;ElMessage.success('套餐版本已创建');await load()}catch(e){ElMessage.error(e instanceof Error?e.message:'创建失败')}}
async function disable(id:number){try{await api.put(`/api/v1/admin/package/${id}/disable`);await load()}catch(e){ElMessage.error(e instanceof Error?e.message:'停用失败')}}
onMounted(async()=>{const b=await api.get<ApiResult<BusinessRuntime[]>>('/api/v1/admin/business/list');businesses.value=b.data.data;await load()});
</script>
<style scoped>.header{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px}h2{margin:0}p{color:#64748b;font-size:13px}</style>
