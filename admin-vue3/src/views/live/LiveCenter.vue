<template>
  <div>
    <div class="heading">
      <div><h2>ZHIBO_LIVE 直播中心</h2><p>媒体节点、推流状态、拉流连接与实时带宽统一监控。一次推流始终对应一条数据库记录。</p></div>
      <el-button type="primary" :loading="loading" @click="loadAll">刷新</el-button>
    </div>

    <el-row :gutter="14" class="metrics">
      <el-col v-for="item in metricCards" :key="item.label" :span="4">
        <el-card shadow="hover"><div class="metric-label">{{ item.label }}</div><div class="metric-value">{{ item.value }}</div></el-card>
      </el-col>
    </el-row>

    <el-alert class="notice" type="info" :closable="false"
      title="带宽为节点累计字节差分计算的瞬时值；“--”表示节点未上报或首次采样，不等于 0。推流地址和票据不会在管理后台展示。" />

    <el-tabs v-model="activeTab" class="tabs" @tab-change="tabChanged">
      <el-tab-pane label="推流会话" name="streams">
        <div class="toolbar">
          <el-select v-model="streamStatus" clearable placeholder="全部状态" style="width:170px" @change="loadStreams">
            <el-option v-for="s in streamStatuses" :key="s" :label="s" :value="s" />
          </el-select>
          <el-button @click="loadStreams">查询</el-button>
        </div>
        <el-table :data="streams" border stripe v-loading="streamLoading">
          <el-table-column prop="streamSessionNo" label="推流会话" min-width="235" show-overflow-tooltip />
          <el-table-column prop="userId" label="用户ID" width="90" />
          <el-table-column prop="deviceLicenseId" label="许可证ID" width="105" />
          <el-table-column prop="mediaNodeCode" label="媒体节点" width="150" />
          <el-table-column prop="protocol" label="协议" width="80" />
          <el-table-column label="状态" width="125"><template #default="s"><el-tag :type="streamTag(s.row.status)">{{ s.row.status }}</el-tag></template></el-table-column>
          <el-table-column prop="startedAt" label="开始时间" width="175" />
          <el-table-column label="时长" width="100"><template #default="s">{{ duration(s.row) }}</template></el-table-column>
          <el-table-column prop="billedUnits" label="扣次" width="70" />
          <el-table-column prop="endReason" label="结束原因" min-width="165" show-overflow-tooltip />
          <el-table-column label="操作" width="110" fixed="right"><template #default="s">
            <el-button type="danger" size="small" :disabled="!isActiveStream(s.row.status)" @click="kick(s.row)">停止</el-button>
          </template></el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="拉流会话" name="plays">
        <div class="toolbar">
          <el-select v-model="playStatus" clearable placeholder="全部状态" style="width:170px" @change="loadPlays">
            <el-option label="PLAYING" value="PLAYING" /><el-option label="ENDED" value="ENDED" />
          </el-select>
          <el-button @click="loadPlays">查询</el-button>
        </div>
        <el-table :data="plays" border stripe v-loading="playLoading">
          <el-table-column prop="id" label="拉流ID" width="90" />
          <el-table-column prop="streamSessionId" label="推流记录ID" width="115" />
          <el-table-column prop="mediaNodeCode" label="媒体节点" width="155" />
          <el-table-column prop="providerClientId" label="媒体连接ID" min-width="180" show-overflow-tooltip />
          <el-table-column prop="protocol" label="协议" width="100" />
          <el-table-column label="状态" width="110"><template #default="s"><el-tag :type="s.row.status==='PLAYING'?'success':'info'">{{ s.row.status }}</el-tag></template></el-table-column>
          <el-table-column prop="startedAt" label="开始时间" width="175" />
          <el-table-column prop="endedAt" label="结束时间" width="175" />
          <el-table-column label="时长" width="100"><template #default="s">{{ seconds(s.row.durationSeconds) }}</template></el-table-column>
          <el-table-column label="下行流量" width="120"><template #default="s">{{ bytes(s.row.outboundBytes) }}</template></el-table-column>
        </el-table>
        <Pagination v-model:page="playPage" v-model:page-size="playSize" :total="playTotal" class="pager" @update:page="loadPlays" @update:page-size="loadPlays" />
      </el-tab-pane>

      <el-tab-pane v-if="canViewNodes" label="媒体节点" name="nodes">
        <div class="toolbar">
          <el-button v-if="canEditNodes" type="primary" @click="openCreate">新增节点</el-button>
          <el-button @click="loadNodes">刷新节点</el-button>
        </div>
        <el-table :data="nodes" border stripe v-loading="nodeLoading">
          <el-table-column prop="nodeName" label="节点名称" min-width="145" />
          <el-table-column prop="nodeCode" label="nodeCode" width="155" />
          <el-table-column prop="providerType" label="类型" width="110" />
          <el-table-column prop="regionCode" label="区域" width="95" />
          <el-table-column label="人工状态" width="110"><template #default="s"><el-tag :type="nodeStatusTag(s.row.status)">{{ s.row.status }}</el-tag></template></el-table-column>
          <el-table-column label="健康" width="105"><template #default="s"><el-tooltip :content="s.row.lastHealthError||'正常'"><el-tag :type="healthTag(s.row.healthStatus)">{{ s.row.healthStatus }}</el-tag></el-tooltip></template></el-table-column>
          <el-table-column label="推流/容量" width="115"><template #default="s">{{ value(s.row.activePublishers) }} / {{ s.row.maxPublishers }}</template></el-table-column>
          <el-table-column label="拉流/容量" width="115"><template #default="s">{{ value(s.row.activeReaders) }} / {{ s.row.maxReaders }}</template></el-table-column>
          <el-table-column label="上行" width="110"><template #default="s">{{ bitrate(s.row.inboundBps) }}</template></el-table-column>
          <el-table-column label="下行" width="110"><template #default="s">{{ bitrate(s.row.outboundBps) }}</template></el-table-column>
          <el-table-column prop="metricsCollectedAt" label="采集时间" width="175" />
          <el-table-column label="操作" width="330" fixed="right"><template #default="s">
            <el-button size="small" @click="testNode(s.row)">连接测试</el-button>
            <el-button v-if="canEditNodes" size="small" :disabled="s.row.status!=='DISABLED'" @click="openEdit(s.row)">编辑</el-button>
            <el-button v-if="canEditNodes&&s.row.status!=='ACTIVE'" size="small" type="success" @click="setNodeStatus(s.row,'ACTIVE')">启用</el-button>
            <el-button v-if="canEditNodes&&s.row.status==='ACTIVE'" size="small" type="warning" @click="setNodeStatus(s.row,'DRAINING')">排空</el-button>
            <el-button v-if="canEditNodes&&s.row.status!=='DISABLED'" size="small" type="danger" @click="setNodeStatus(s.row,'DISABLED')">停用</el-button>
          </template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="nodeDialog" :title="editingNodeId?'编辑媒体节点':'新增媒体节点'" width="720px">
      <el-form :model="nodeForm" label-width="145px">
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="nodeCode" required><el-input v-model="nodeForm.nodeCode" :disabled="Boolean(editingNodeId)" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="节点名称" required><el-input v-model="nodeForm.nodeName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="服务器类型" required><el-select v-model="nodeForm.providerType" style="width:100%"><el-option label="MediaMTX" value="MEDIAMTX" /><el-option label="SRS" value="SRS" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="区域"><el-input v-model="nodeForm.regionCode" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="公开推流地址" required><el-input v-model="nodeForm.publicPublishBaseUrl" placeholder="rtmp://live.example.com:1935" /></el-form-item>
        <el-form-item label="公开 HLS 地址"><el-input v-model="nodeForm.publicHlsBaseUrl" placeholder="https://live.example.com" /></el-form-item>
        <el-form-item label="公开 WebRTC 地址"><el-input v-model="nodeForm.publicWebrtcBaseUrl" /></el-form-item>
        <el-form-item label="内部控制 API" required><el-input v-model="nodeForm.internalApiBaseUrl" placeholder="http://127.0.0.1:9997" /></el-form-item>
        <el-form-item label="内部 Metrics"><el-input v-model="nodeForm.internalMetricsUrl" placeholder="http://127.0.0.1:9998/metrics" /></el-form-item>
        <el-form-item label="Secret 引用"><el-input v-model="nodeForm.secretRef" placeholder="application（不填写明文密钥）" /></el-form-item>
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="推流协议"><el-input v-model="nodeForm.supportedPublishProtocols" placeholder="RTMP" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="拉流协议"><el-input v-model="nodeForm.supportedPlayProtocols" placeholder="RTMP,HLS" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="权重"><el-input-number v-model="nodeForm.weight" :min="1" :max="10000" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="推流容量"><el-input-number v-model="nodeForm.maxPublishers" :min="1" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="拉流容量"><el-input-number v-model="nodeForm.maxReaders" :min="1" /></el-form-item></el-col>
        </el-row>
      </el-form>
      <template #footer><el-button @click="nodeDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveNode">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, type ApiResult, type PageResult } from '../../api';
import { hasPermission } from '../../auth';
import Pagination from '../../components/Pagination.vue';
import type { BusinessRuntime, LiveOverview, LivePlaySession, LiveStreamSession, MediaServerNode } from '../../types';

const activeTab=ref('streams'),loading=ref(false),streamLoading=ref(false),playLoading=ref(false),nodeLoading=ref(false),saving=ref(false);
const overview=reactive<LiveOverview>({totalNodes:0,availableNodes:0,abnormalNodes:0,activePublishers:0,activeReaders:0,todayStreamStarts:0});
const streams=ref<LiveStreamSession[]>([]),plays=ref<LivePlaySession[]>([]),nodes=ref<MediaServerNode[]>([]);
const streamStatus=ref(''),playStatus=ref(''),playPage=ref(1),playSize=ref(20),playTotal=ref(0),liveBizId=ref(3);
const nodeDialog=ref(false),editingNodeId=ref<number>();
const canViewNodes=computed(()=>hasPermission('live:node:view')),canEditNodes=computed(()=>hasPermission('live:node:edit'));
const streamStatuses=['ISSUED','AUTHORIZED','LIVE','KICK_REQUESTED','ENDED','EXPIRED'];
const nodeForm=reactive({bizId:3,nodeCode:'',nodeName:'',providerType:'MEDIAMTX' as 'MEDIAMTX'|'SRS',regionCode:'',publicPublishBaseUrl:'rtmp://127.0.0.1:1935',publicHlsBaseUrl:'http://127.0.0.1:8888',publicWebrtcBaseUrl:'',internalApiBaseUrl:'http://127.0.0.1:9997',internalMetricsUrl:'http://127.0.0.1:9998/metrics',secretRef:'application',supportedPublishProtocols:'RTMP',supportedPlayProtocols:'RTMP,HLS',weight:100,maxPublishers:100,maxReaders:1000});
const metricCards=computed(()=>[
  {label:'可用媒体节点',value:`${overview.availableNodes}/${overview.totalNodes}`},
  {label:'异常节点',value:overview.abnormalNodes},
  {label:'当前推流',value:overview.activePublishers},
  {label:'当前拉流',value:overview.activeReaders},
  {label:'总上行',value:bitrate(overview.inboundBps)},
  {label:'总下行',value:bitrate(overview.outboundBps)},
]);

async function resolveBiz(){if(!hasPermission('business:view'))return;const r=await api.get<ApiResult<BusinessRuntime[]>>('/api/v1/admin/business/list');const b=r.data.data.find(v=>v.appId===3&&v.bizCode==='ZHIBO_LIVE');if(b){liveBizId.value=b.bizId;nodeForm.bizId=b.bizId;}}
async function loadOverview(){const r=await api.get<ApiResult<LiveOverview>>('/api/v1/admin/zhibo-live/overview');Object.assign(overview,r.data.data);}
async function loadStreams(){streamLoading.value=true;try{const r=await api.get<ApiResult<LiveStreamSession[]>>('/api/v1/admin/zhibo-live/streams',{params:{status:streamStatus.value||undefined}});streams.value=r.data.data;}finally{streamLoading.value=false;}}
async function loadPlays(){playLoading.value=true;try{const r=await api.get<ApiResult<PageResult<LivePlaySession>>>('/api/v1/admin/zhibo-live/play-sessions',{params:{page:playPage.value,size:playSize.value,status:playStatus.value||undefined}});plays.value=r.data.data.records;playTotal.value=r.data.data.total;}finally{playLoading.value=false;}}
async function loadNodes(){if(!canViewNodes.value)return;nodeLoading.value=true;try{const r=await api.get<ApiResult<MediaServerNode[]>>('/api/v1/admin/zhibo-live/media-nodes');nodes.value=r.data.data;}finally{nodeLoading.value=false;}}
async function loadAll(){loading.value=true;try{await resolveBiz();await Promise.all([loadOverview(),loadStreams(),loadPlays(),loadNodes()]);}catch(e){ElMessage.error(e instanceof Error?e.message:'直播数据加载失败');}finally{loading.value=false;}}
async function tabChanged(name:string|number){if(name==='nodes')await loadNodes();if(name==='plays')await loadPlays();if(name==='streams')await loadStreams();}
async function kick(row:LiveStreamSession){await ElMessageBox.confirm(`确认停止推流 ${row.streamSessionNo}？`,'停止直播',{type:'warning'});await api.post(`/api/v1/admin/zhibo-live/streams/${row.streamSessionNo}/kick`);ElMessage.success('停止请求已执行');await Promise.all([loadStreams(),loadOverview()]);}
function resetForm(){Object.assign(nodeForm,{bizId:liveBizId.value,nodeCode:'',nodeName:'',providerType:'MEDIAMTX',regionCode:'',publicPublishBaseUrl:'rtmp://127.0.0.1:1935',publicHlsBaseUrl:'http://127.0.0.1:8888',publicWebrtcBaseUrl:'',internalApiBaseUrl:'http://127.0.0.1:9997',internalMetricsUrl:'http://127.0.0.1:9998/metrics',secretRef:'application',supportedPublishProtocols:'RTMP',supportedPlayProtocols:'RTMP,HLS',weight:100,maxPublishers:100,maxReaders:1000});}
function openCreate(){editingNodeId.value=undefined;resetForm();nodeDialog.value=true;}
function openEdit(row:MediaServerNode){editingNodeId.value=row.id;Object.assign(nodeForm,{bizId:row.bizId,nodeCode:row.nodeCode,nodeName:row.nodeName,providerType:row.providerType,regionCode:row.regionCode||'',publicPublishBaseUrl:row.publicPublishBaseUrl,publicHlsBaseUrl:row.publicHlsBaseUrl||'',publicWebrtcBaseUrl:row.publicWebrtcBaseUrl||'',internalApiBaseUrl:row.internalApiBaseUrl,internalMetricsUrl:row.internalMetricsUrl||'',secretRef:row.secretRef||'application',supportedPublishProtocols:row.supportedPublishProtocols||'RTMP',supportedPlayProtocols:row.supportedPlayProtocols||'',weight:row.weight,maxPublishers:row.maxPublishers,maxReaders:row.maxReaders});nodeDialog.value=true;}
async function saveNode(){saving.value=true;try{if(editingNodeId.value)await api.put(`/api/v1/admin/zhibo-live/media-nodes/${editingNodeId.value}`,nodeForm);else await api.post('/api/v1/admin/zhibo-live/media-nodes',nodeForm);ElMessage.success('节点配置已保存');nodeDialog.value=false;await loadNodes();}catch(e){ElMessage.error(e instanceof Error?e.message:'保存失败');}finally{saving.value=false;}}
async function testNode(row:MediaServerNode){try{await api.post(`/api/v1/admin/zhibo-live/media-nodes/${row.id}/test`);ElMessage.success('连接和指标采集成功');await Promise.all([loadNodes(),loadOverview()]);}catch(e){ElMessage.error(e instanceof Error?e.message:'连接测试失败');await loadNodes();}}
async function setNodeStatus(row:MediaServerNode,status:'ACTIVE'|'DRAINING'|'DISABLED'){let reason='';try{const r=await ElMessageBox.prompt(`请输入将节点切换为 ${status} 的原因`,'节点状态变更',{inputPattern:/.{2,}/,inputErrorMessage:'至少输入2个字符',type:'warning'});reason=r.value;}catch{return;}await api.put(`/api/v1/admin/zhibo-live/media-nodes/${row.id}/status`,null,{params:{status,reason}});ElMessage.success('节点状态已更新');await Promise.all([loadNodes(),loadOverview()]);}
const value=(v?:number)=>v===undefined||v===null?'--':v;
function bitrate(v?:number){return v===undefined||v===null?'--':`${(v/1_000_000).toFixed(2)} Mbps`;}
function bytes(v?:number){if(v===undefined||v===null)return '--';if(v<1024)return `${v} B`;if(v<1024**2)return `${(v/1024).toFixed(1)} KB`;if(v<1024**3)return `${(v/1024**2).toFixed(1)} MB`;return `${(v/1024**3).toFixed(2)} GB`;}
function seconds(v?:number){if(v===undefined||v===null)return '--';const h=Math.floor(v/3600),m=Math.floor(v%3600/60),s=v%60;return h?`${h}h ${m}m`:`${m}m ${s}s`;}
function duration(row:LiveStreamSession){if(row.durationSeconds!==undefined&&row.durationSeconds!==null)return seconds(row.durationSeconds);if(!row.startedAt)return '--';return seconds(Math.max(0,Math.floor((Date.now()-new Date(row.startedAt).getTime())/1000)));}
const isActiveStream=(s:string)=>['ISSUED','AUTHORIZED','LIVE','KICK_REQUESTED'].includes(s);
const streamTag=(s:string)=>s==='LIVE'?'success':isActiveStream(s)?'warning':s==='ENDED'?'info':'danger';
const healthTag=(s:string)=>s==='UP'?'success':s==='DEGRADED'?'warning':s==='DOWN'?'danger':'info';
const nodeStatusTag=(s:string)=>s==='ACTIVE'?'success':s==='DRAINING'?'warning':'info';
onMounted(loadAll);
</script>

<style scoped>
.heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px}.heading h2{margin:0;color:#1e293b}.heading p{margin:6px 0 0;color:#64748b;font-size:13px}.metrics{margin-bottom:14px}.metric-label{color:#64748b;font-size:13px}.metric-value{margin-top:9px;font-size:22px;font-weight:700;color:#2563eb}.notice{margin-bottom:14px}.tabs{background:#fff;padding:0 16px 16px;border-radius:6px}.toolbar{display:flex;gap:10px;margin-bottom:12px}.pager{margin-top:14px}
</style>
