<template>
  <div class="update-page">
    <div class="page-head">
      <div><h2>客户端升级中心</h2><p>版本、构件、强制策略和遥测均按业务隔离</p></div>
      <el-button v-if="hasPermission('client-update:create')" type="primary" @click="createVisible=true">新建版本</el-button>
    </div>

    <el-card shadow="never" class="scope-card">
      <el-form inline>
        <el-form-item label="业务">
          <el-select v-model="bizId" style="width:260px" @change="reloadAll">
            <el-option v-for="b in businesses" :key="b.bizId" :value="b.bizId" :label="`${b.businessName} · appId=${b.appId} · ${b.bizCode}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="版本线"><el-select v-model="channel" style="width:120px" @change="reloadAll"><el-option label="稳定版" value="STABLE"/><el-option label="测试版" value="BETA"/></el-select></el-form-item>
        <el-form-item label="状态"><el-select v-model="status" clearable style="width:140px" @change="loadReleases"><el-option v-for="s in states" :key="s" :label="s" :value="s"/></el-select></el-form-item>
      </el-form>
      <el-alert v-if="selectedBusiness" :closable="false" type="info" show-icon :title="`当前业务：${selectedBusiness.businessName}（appId=${selectedBusiness.appId} / ${selectedBusiness.bizCode}）`" />
    </el-card>

    <el-tabs v-model="activeTab" class="content-card">
      <el-tab-pane label="版本发布" name="releases">
        <el-table :data="releases" v-loading="loading" stripe>
          <el-table-column prop="version" label="版本" width="110"/><el-table-column prop="channel" label="版本线" width="100"/>
          <el-table-column label="状态" width="120"><template #default="{row}"><el-tag :type="statusType(row.status)">{{row.status}}</el-tag></template></el-table-column>
          <el-table-column label="灰度" width="100"><template #default="{row}"><el-button v-if="row.status==='PUBLISHED'" link @click="changeRollout(row)">{{row.rolloutPercentage}}%</el-button><span v-else>{{row.rolloutPercentage}}%</span></template></el-table-column>
          <el-table-column prop="minimumUpdaterVersion" label="最低 Updater" width="130"/><el-table-column prop="releaseNotes" label="说明" min-width="220" show-overflow-tooltip/>
          <el-table-column prop="publishedAt" label="发布时间" width="180"/>
          <el-table-column label="操作" width="360" fixed="right"><template #default="{row}">
            <el-button link @click="showArtifacts(row)">构件</el-button>
            <el-button v-if="row.status==='DRAFT' && hasPermission('client-update:create')" link type="primary" @click="openUpload(row)">上传</el-button>
            <el-button v-if="row.status==='DRAFT' && hasPermission('client-update:create')" link type="danger" @click="deleteDraft(row)">删除草稿</el-button>
            <el-button v-if="row.status==='DRAFT'" link type="primary" @click="act(row,'ready')">就绪</el-button>
            <el-button v-if="row.status==='READY'" link type="success" @click="act(row,'publish')">发布</el-button>
            <el-button v-if="row.status==='READY'" link @click="act(row,'draft')">退回草稿</el-button>
            <el-button v-if="row.status==='PUBLISHED'" link type="warning" @click="act(row,'suspend')">暂停</el-button>
            <el-button v-if="row.status==='SUSPENDED'" link type="success" @click="act(row,'resume')">恢复</el-button>
            <el-button v-if="['PUBLISHED','SUSPENDED'].includes(row.status)" link type="danger" @click="act(row,'archive')">归档</el-button>
          </template></el-table-column>
        </el-table>
        <Pagination :total="total" v-model:page="page" v-model:page-size="size" @change="loadReleases" />
      </el-tab-pane>

      <el-tab-pane label="运行策略" name="policy">
        <el-form v-if="bizId" :model="policy" label-width="170px" class="policy-form">
          <el-form-item label="允许检查与下载"><el-switch v-model="policy.updateEnabled"/></el-form-item>
          <el-form-item label="最低可运行版本"><el-input v-model="policy.minimumSupportedVersion" placeholder="例如 1.8.0；留空表示不强制"/></el-form-item>
          <el-form-item label="强制目标 Release ID"><el-input-number v-model="policy.mandatoryReleaseId" :min="1" controls-position="right"/></el-form-item>
          <el-form-item label="服务端 426 拦截"><el-switch v-model="policy.serverEnforcementEnabled"/></el-form-item>
          <el-form-item label="离线宽限（小时）"><el-input-number v-model="policy.offlineGraceHours" :min="0" :max="720"/></el-form-item>
          <el-form-item label="检查间隔（秒）"><el-input-number v-model="policy.checkIntervalSeconds" :min="60" :max="86400"/></el-form-item>
          <el-form-item><el-button type="primary" :disabled="!hasPermission('client-update:publish')" @click="savePolicy">保存策略</el-button><span class="revision">revision {{policy.policyRevision || 0}}</span></el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="升级遥测" name="events">
        <div class="metric-grid"><el-card v-for="s in statistics" :key="s.eventType" shadow="never"><span>{{s.eventType}}</span><strong>{{s.count}}</strong></el-card></div>
        <el-table :data="events" stripe><el-table-column prop="createdAt" label="服务端时间" width="180"/><el-table-column prop="eventType" label="事件" width="180"/><el-table-column prop="fromVersion" label="原版本"/><el-table-column prop="targetVersion" label="目标版本"/><el-table-column prop="errorCategory" label="错误分类"/><el-table-column prop="checkRequestId" label="检查请求" min-width="220"/></el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="createVisible" title="创建升级版本草稿" width="560px"><el-form :model="createForm" label-width="130px">
      <el-form-item label="版本"><el-input v-model="createForm.version" placeholder="1.8.0"/></el-form-item><el-form-item label="版本线"><el-select v-model="createForm.channel"><el-option label="STABLE" value="STABLE"/><el-option label="BETA" value="BETA"/></el-select></el-form-item>
      <el-form-item label="最低协议"><el-input-number v-model="createForm.minimumProtocolVersion" :min="1"/></el-form-item><el-form-item label="最低 Updater"><el-input v-model="createForm.minimumUpdaterVersion"/></el-form-item>
      <el-form-item label="灰度比例"><el-slider v-model="createForm.rolloutPercentage" show-input/></el-form-item><el-form-item label="发布说明"><el-input v-model="createForm.releaseNotes" type="textarea" :rows="4"/></el-form-item>
    </el-form><template #footer><el-button @click="createVisible=false">取消</el-button><el-button type="primary" @click="createRelease">创建草稿</el-button></template></el-dialog>

    <el-dialog v-model="uploadVisible" title="上传 Windows x64 完整包" width="620px">
      <el-alert type="warning" :closable="false" title="ZIP 根目录必须包含 update-manifest.json；以下参数必须与 Manifest 完全一致"/>
      <el-descriptions v-if="uploadRelease" :column="2" border class="upload-contract">
        <el-descriptions-item label="appId">{{ selectedBusiness?.appId }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ uploadRelease.version }}</el-descriptions-item>
        <el-descriptions-item label="平台/架构">WINDOWS / X64</el-descriptions-item>
        <el-descriptions-item label="包类型">ZIP</el-descriptions-item>
        <el-descriptions-item label="协议版本">{{ uploadRelease.minimumProtocolVersion }}</el-descriptions-item>
        <el-descriptions-item label="最低 Updater">{{ uploadRelease.minimumUpdaterVersion }}</el-descriptions-item>
      </el-descriptions>
      <el-upload drag :auto-upload="false" :limit="1" :on-change="onFile"><el-icon class="el-icon--upload"><UploadFilled/></el-icon><div>拖入或选择 ZIP 完整包</div></el-upload>
      <template #footer><el-button @click="uploadVisible=false">取消</el-button><el-button type="primary" :loading="uploading" @click="uploadArtifact">上传、校验并签名</el-button></template>
    </el-dialog>

    <el-drawer v-model="artifactVisible" title="版本构件" size="65%">
      <el-alert class="drawer-tip" type="info" :closable="false" title="下载地址为带签名的临时链接，仅已发布且签名完成的构件可以生成。"/>
      <el-table :data="artifacts">
        <el-table-column prop="fileName" label="文件" min-width="220"/><el-table-column prop="platform" label="平台" width="100"/><el-table-column prop="arch" label="架构" width="90"/><el-table-column prop="fileSize" label="字节" width="120"/><el-table-column prop="status" label="状态" width="120"/><el-table-column prop="sha256" label="SHA-256" min-width="180" show-overflow-tooltip/><el-table-column prop="signingKeyId" label="签名 Key" width="130"/>
        <el-table-column label="下载地址" width="150" fixed="right"><template #default="{row}">
          <el-button v-if="row.status==='AVAILABLE' && artifactRelease?.status==='PUBLISHED'" link type="primary" :disabled="!hasPermission('client-update:publish')" @click="openDownloadLink(row)">生成下载地址</el-button>
          <span v-else class="muted">发布后可生成</span>
        </template></el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="downloadLinkVisible" title="生成用户下载地址" width="620px">
      <el-alert type="warning" :closable="false" title="链接包含访问签名，请按需设置有效期；到期后需重新生成。"/>
      <el-form class="download-form" label-width="110px">
        <el-form-item label="升级包"><el-input :model-value="downloadArtifact?.fileName" disabled/></el-form-item>
        <el-form-item label="有效期"><el-input-number v-model="downloadHours" :min="1" :max="168"/><span class="unit">小时（最多 7 天）</span></el-form-item>
        <el-form-item label="生成原因"><el-input v-model="downloadReason" maxlength="500" show-word-limit placeholder="例如：发送给客户张三进行手动升级"/></el-form-item>
        <el-form-item v-if="generatedLink" label="下载地址"><el-input v-model="generatedLink" type="textarea" :rows="4" readonly/></el-form-item>
        <el-form-item v-if="linkExpiresAt" label="失效时间"><span>{{linkExpiresAt}}</span></el-form-item>
      </el-form>
      <template #footer><el-button @click="downloadLinkVisible=false">关闭</el-button><el-button type="primary" :loading="generatingLink" @click="generateDownloadLink">生成新地址</el-button><el-button v-if="generatedLink" type="success" @click="copyDownloadLink">复制地址</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus';
import { api, type ApiResult, type PageResult } from '../../api';
import { hasPermission } from '../../auth';
import Pagination from '../../components/Pagination.vue';
import type { BusinessRuntime, ClientArtifact, ClientRelease, ClientUpdatePolicy } from '../../types';
const businesses=ref<BusinessRuntime[]>([]), releases=ref<ClientRelease[]>([]), artifacts=ref<ClientArtifact[]>([]), events=ref<any[]>([]), statistics=ref<{eventType:string,count:number}[]>([]);
const bizId=ref<number>(), channel=ref('STABLE'), status=ref(''), page=ref(1), size=ref(20), total=ref(0), loading=ref(false), activeTab=ref('releases');
const states=['DRAFT','READY','PUBLISHED','SUSPENDED','ARCHIVED']; const selectedBusiness=computed(()=>businesses.value.find(b=>b.bizId===bizId.value));
const policy=reactive({updateEnabled:false,minimumSupportedVersion:'',mandatoryReleaseId:undefined as number|undefined,serverEnforcementEnabled:false,offlineGraceHours:24,checkIntervalSeconds:21600,policyRevision:undefined as number|undefined});
const createVisible=ref(false), uploadVisible=ref(false), artifactVisible=ref(false), uploading=ref(false), uploadRelease=ref<ClientRelease>(), artifactRelease=ref<ClientRelease>(), selectedFile=ref<File>();
const downloadLinkVisible=ref(false), generatingLink=ref(false), downloadArtifact=ref<ClientArtifact>(), downloadHours=ref(24), downloadReason=ref(''), generatedLink=ref(''), linkExpiresAt=ref('');
const createForm=reactive({version:'',channel:'STABLE',minimumProtocolVersion:1,minimumUpdaterVersion:'1.0.0',rolloutPercentage:100,releaseNotes:''});
const requestId=()=>crypto.randomUUID(); const err=(e:unknown)=>ElMessage.error(e instanceof Error?e.message:'操作失败');
async function loadBusinesses(){const r=await api.get<ApiResult<BusinessRuntime[]>>('/api/v1/admin/business/list');businesses.value=r.data.data;if(!bizId.value&&businesses.value.length)bizId.value=businesses.value[0].bizId;}
async function loadReleases(){if(!bizId.value)return;loading.value=true;try{const r=await api.get<ApiResult<PageResult<ClientRelease>>>('/api/v1/admin/client-updates/releases',{params:{bizId:bizId.value,channel:channel.value,status:status.value||undefined,page:page.value,size:size.value}});releases.value=r.data.data.records;total.value=r.data.data.total;}catch(e){err(e)}finally{loading.value=false}}
async function loadPolicy(){if(!bizId.value)return;try{const r=await api.get<ApiResult<ClientUpdatePolicy|null>>(`/api/v1/admin/client-updates/policies/${bizId.value}`,{params:{channel:channel.value,platform:'WINDOWS',arch:'X64'}});const p=r.data.data;if(p)Object.assign(policy,{...p,updateEnabled:p.updateEnabled===1,serverEnforcementEnabled:p.serverEnforcementEnabled===1});else Object.assign(policy,{updateEnabled:false,minimumSupportedVersion:'',mandatoryReleaseId:undefined,serverEnforcementEnabled:false,offlineGraceHours:24,checkIntervalSeconds:21600,policyRevision:undefined});}catch(e){err(e)}}
async function loadEvents(){if(!bizId.value)return;try{const [a,b]=await Promise.all([api.get<ApiResult<PageResult<any>>>('/api/v1/admin/client-updates/events',{params:{bizId:bizId.value,page:1,size:50}}),api.get<ApiResult<{eventType:string,count:number}[]>>('/api/v1/admin/client-updates/statistics',{params:{bizId:bizId.value}})]);events.value=a.data.data.records;statistics.value=b.data.data;}catch(e){err(e)}}
async function reloadAll(){page.value=1;await Promise.all([loadReleases(),loadPolicy(),loadEvents()]);}
async function createRelease(){if(!bizId.value||!selectedBusiness.value)return;try{await api.post('/api/v1/admin/client-updates/releases',{...createForm,appId:selectedBusiness.value.appId,requestId:requestId()});ElMessage.success('草稿已创建');createVisible.value=false;await loadReleases();}catch(e){err(e)}}
async function act(row:ClientRelease,action:string){let reason:string;try{reason=(await ElMessageBox.prompt(`请输入${action}原因`,'高风险发布操作',{inputPattern:/.{2,}/,inputErrorMessage:'至少2个字符',type:'warning'})).value}catch{return}try{await api.post(`/api/v1/admin/client-updates/releases/${row.id}/${action}`,{requestId:requestId(),reason});ElMessage.success('状态已更新');await reloadAll()}catch(e){err(e)}}
async function changeRollout(row:ClientRelease){let raw:string;try{raw=(await ElMessageBox.prompt('输入新灰度比例 0-100','调整已发布版本灰度',{inputValue:String(row.rolloutPercentage),inputPattern:/^(100|[1-9]?\d)$/,inputErrorMessage:'请输入 0-100'})).value}catch{return}try{await api.put(`/api/v1/admin/client-updates/releases/${row.id}/rollout`,{rolloutPercentage:Number(raw),requestId:requestId(),reason:`调整灰度 ${row.rolloutPercentage}% → ${raw}%`});ElMessage.success('灰度已更新');await loadReleases()}catch(e){err(e)}}
async function deleteDraft(row:ClientRelease){let reason:string;try{reason=(await ElMessageBox.prompt(`将永久删除草稿 ${row.version}、构件数据库记录和已上传文件。请输入删除原因`,'删除未发布版本',{inputPattern:/.{2,}/,inputErrorMessage:'至少2个字符',confirmButtonText:'确认永久删除',type:'warning'})).value}catch{return}try{await api.delete(`/api/v1/admin/client-updates/releases/${row.id}`,{data:{requestId:requestId(),reason}});ElMessage.success('草稿版本和构件已删除');await loadReleases()}catch(e){err(e)}}
async function showArtifacts(row:ClientRelease){artifactRelease.value=row;artifactVisible.value=true;const r=await api.get<ApiResult<ClientArtifact[]>>(`/api/v1/admin/client-updates/releases/${row.id}/artifacts`);artifacts.value=r.data.data;}
function openDownloadLink(row:ClientArtifact){downloadArtifact.value=row;downloadHours.value=24;downloadReason.value='';generatedLink.value='';linkExpiresAt.value='';downloadLinkVisible.value=true;}
async function generateDownloadLink(){if(!downloadArtifact.value)return;if(downloadReason.value.trim().length<2)return ElMessage.warning('请输入至少 2 个字符的生成原因');generatingLink.value=true;try{const r=await api.post<ApiResult<{downloadUrl:string,expiresAt:string}>>(`/api/v1/admin/client-updates/artifacts/${downloadArtifact.value.id}/download-link`,{validHours:downloadHours.value,requestId:requestId(),reason:downloadReason.value.trim()});generatedLink.value=r.data.data.downloadUrl;linkExpiresAt.value=r.data.data.expiresAt;const local=/\b(localhost|127\.0\.0\.1)\b/i.test(generatedLink.value);local?ElMessage.warning('地址已生成，但当前为本机地址，发送给其他用户前请配置 PDK_UPDATE_PUBLIC_BASE_URL'):ElMessage.success('下载地址已生成');}catch(e){err(e)}finally{generatingLink.value=false}}
async function copyDownloadLink(){try{await navigator.clipboard.writeText(generatedLink.value);ElMessage.success('下载地址已复制')}catch{ElMessage.error('自动复制失败，请手动选择并复制')}}
function openUpload(row:ClientRelease){uploadRelease.value=row;selectedFile.value=undefined;uploadVisible.value=true} function onFile(f:UploadFile){selectedFile.value=f.raw}
async function uploadArtifact(){if(!uploadRelease.value||!selectedFile.value)return ElMessage.warning('请选择 ZIP 文件');uploading.value=true;let stage='创建上传会话';try{const session=await api.post<ApiResult<ClientArtifact>>(`/api/v1/admin/client-updates/releases/${uploadRelease.value.id}/artifacts/upload-session`,{platform:'WINDOWS',arch:'X64',packageType:'ZIP',fileName:selectedFile.value.name,requestId:requestId()});stage='上传并校验 ZIP';const fd=new FormData();fd.append('file',selectedFile.value);await api.put(`/api/v1/admin/client-updates/artifacts/${session.data.data.id}/content`,fd,{timeout:0});stage='生成 Ed25519 构件签名';await api.post(`/api/v1/admin/client-updates/artifacts/${session.data.data.id}/complete`,{requestId:requestId(),reason:'管理后台上传并完成服务端校验'});ElMessage.success('构件已校验并签名');uploadVisible.value=false;}catch(e){ElMessage.error(`${stage}失败：${e instanceof Error?e.message:'未知错误'}`)}finally{uploading.value=false}}
async function savePolicy(){if(!bizId.value)return;let reason:string;try{reason=(await ElMessageBox.prompt('请输入策略变更原因','策略审计',{inputPattern:/.{2,}/,inputErrorMessage:'至少2个字符',type:'warning'})).value}catch{return}try{const body={channel:channel.value,platform:'WINDOWS',arch:'X64',updateEnabled:policy.updateEnabled,minimumSupportedVersion:policy.minimumSupportedVersion||null,mandatoryReleaseId:policy.minimumSupportedVersion?policy.mandatoryReleaseId:null,serverEnforcementEnabled:policy.serverEnforcementEnabled,offlineGraceHours:policy.offlineGraceHours,checkIntervalSeconds:policy.checkIntervalSeconds,policyRevision:policy.policyRevision,requestId:requestId(),reason};await api.put(`/api/v1/admin/client-updates/policies/${bizId.value}`,body);ElMessage.success('策略已原子保存');await loadPolicy()}catch(e){err(e)}}
function statusType(s:string){return ({PUBLISHED:'success',SUSPENDED:'warning',ARCHIVED:'info',READY:'primary'} as any)[s]||''}
onMounted(async()=>{try{await loadBusinesses();await reloadAll()}catch(e){err(e)}});
</script>
<style scoped>
.update-page{display:flex;flex-direction:column;gap:18px}.page-head{display:flex;align-items:center;justify-content:space-between}.page-head h2{margin:0;color:#172033}.page-head p{margin:6px 0 0;color:#718096;font-size:13px}.scope-card,.content-card{border:1px solid #e5eaf2;border-radius:12px}.policy-form{max-width:620px;padding:24px 12px}.revision{margin-left:16px;color:#8492a6}.metric-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin-bottom:16px}.metric-grid .el-card :deep(.el-card__body){display:flex;flex-direction:column;gap:8px}.metric-grid span{font-size:12px;color:#6b7280}.metric-grid strong{font-size:24px;color:#1d4ed8}.upload-contract{margin:14px 0}.drawer-tip{margin-bottom:14px}.download-form{margin-top:18px}.unit{margin-left:10px;color:#718096}.muted{font-size:12px;color:#a0aec0}
</style>
