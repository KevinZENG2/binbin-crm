<script setup lang="ts">
import {computed,ref,watch} from 'vue';
import Icon from './Icon.vue';
type Request=<T>(path:string,method?:string,body?:unknown)=>Promise<T>;
type Row={id:string;name:string;source:string;region:string;ownerMemberId:string;ownerName:string;inPool:boolean;poolEnteredAt:string|null;updatedAt:string;version:number;status?:string};
type Page={items:Row[];total:number;page:number;pageSize:number};
type Event={id:string;action:string;reason:string;fromName:string|null;toName:string|null;actorName:string|null;createdAt:string;resourceVersion:number};
const props=defineProps<{section:string;request:Request;readonly:boolean;write:boolean;manager:boolean;admin:boolean}>();
const kind=computed(()=>props.section==='lead-pool'?'leads':'customers');
const label=computed(()=>kind.value==='leads'?'线索':'客户');
const mode=ref<'pool'|'mine'|'team'>('pool'),rows=ref<Row[]>([]),total=ref(0),page=ref(1),query=ref('');
const busy=ref(false),error=ref(''),notice=ref(''),selected=ref<string[]>([]),recipients=ref<{id:string;name:string}[]>([]),recipient=ref('');
const pending=ref<{kind:'claim'|'return'|'assign'|'batch';row?:Row}|null>(null),reason=ref('');
const historyId=ref(''),history=ref<Event[]>([]);
const endpoint=computed(()=>'/resource-pool/'+kind.value);
const selection=computed(()=>rows.value.filter(r=>selected.value.includes(r.id)));
const actions:Record<string,string>={CLAIM:'领取',RETURN:'退回',ASSIGN:'主管派发',BATCH_ASSIGN:'批量分配',AUTO_RECYCLE:'超期回收',OFFBOARD_TRANSFER:'停用转交',DIRECT_TRANSFER:'负责人变更'};
function time(value:string|null){return value?new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(value)):'—';}
async function run(fn:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';try{await fn();}catch(e){error.value=e instanceof Error?e.message:'操作未完成';}finally{busy.value=false;}}
async function load(){const result=await props.request<Page>(endpoint.value+`?mode=${mode.value}&page=${page.value}&pageSize=20&q=${encodeURIComponent(query.value)}`);rows.value=result.items;total.value=result.total;selected.value=[];}
function changeMode(value:'pool'|'mine'|'team'){mode.value=value;page.value=1;historyId.value='';notice.value='';run(load);}
function search(){page.value=1;run(load);}
function turn(delta:number){page.value+=delta;run(load);}
async function prepare(kind:'claim'|'return'|'assign'|'batch',row?:Row){
 pending.value={kind,row};reason.value='';recipient.value='';
 if((kind==='assign'||kind==='batch')&&!recipients.value.length)await run(async()=>{recipients.value=await props.request(endpoint.value+'/recipients');});
}
function cancel(){pending.value=null;reason.value='';}
function commit(){return run(async()=>{
 const action=pending.value;if(!action)return;
 if((action.kind==='assign'||action.kind==='batch')&&!recipient.value)throw new Error('请选择接收成员');
 if(action.kind==='batch'){
  if(!selection.value.length)throw new Error('请先选择记录');
  const result=await props.request<{assigned:number}>(endpoint.value+'/assign-batch','POST',{memberId:recipient.value,items:selection.value.map(r=>({id:r.id,expectedVersion:r.version})),reason:reason.value});
  notice.value=`已分配 ${result.assigned} 条${label.value}，操作已记录`;
 }else if(action.row){
  const payload=action.kind==='assign'?{memberId:recipient.value,expectedVersion:action.row.version,reason:reason.value}:{expectedVersion:action.row.version,reason:reason.value};
  await props.request(endpoint.value+'/'+action.row.id+'/'+(action.kind==='return'?'return':action.kind),'POST',payload);
  notice.value=action.kind==='claim'?'领取成功，已进入我的资源':action.kind==='return'?'已退回资源池':'派发成功';
 }
 pending.value=null;historyId.value='';await load();
});}
function showHistory(row:Row){if(historyId.value===row.id){historyId.value='';return;}run(async()=>{history.value=await props.request<Event[]>(endpoint.value+'/'+row.id+'/history');historyId.value=row.id;});}
function toggle(id:string){selected.value=selected.value.includes(id)?selected.value.filter(x=>x!==id):[...selected.value,id];}
function selectVisible(){selected.value=selected.value.length===rows.value.length?[]:rows.value.map(r=>r.id);}
function recover(){run(async()=>{const result=await props.request<{recycled:number}>('/resource-pool/recover','POST');notice.value=`本次回收 ${result.recycled} 条超期资源`;await load();});}
watch(()=>props.section,()=>{mode.value='pool';page.value=1;recipients.value=[];historyId.value='';run(load);},{immediate:true});
</script>

<template>
 <section class="pool-workspace">
  <div class="page-heading"><div><span class="eyebrow">RESOURCE OPERATIONS</span><h1>{{label}}资源池</h1><p>领取、退回与派发均检查权限和保有量；每次流转保留历史。</p></div><button v-if="admin&&!readonly" class="btn secondary" :disabled="busy" @click="recover"><Icon name="refresh" :size="16"/>检查超期资源</button></div>
  <div v-if="error" class="pool-alert error" role="alert">{{error}}</div><div v-if="notice" class="pool-alert" role="status">{{notice}}</div>
  <div class="pool-tabs" aria-label="资源范围"><button :class="{active:mode==='pool'}" @click="changeMode('pool')">{{label}}资源池</button><button :class="{active:mode==='mine'}" @click="changeMode('mine')">我负责的{{label}}</button><button v-if="manager" :class="{active:mode==='team'}" @click="changeMode('team')">团队资源</button></div>
  <section class="card pool-panel"><form class="pool-toolbar" @submit.prevent="search"><label class="pool-search"><Icon name="search" :size="16"/><input v-model="query" :placeholder="`搜索${label}名称`" maxlength="100" aria-label="搜索资源名称"/></label><button class="btn secondary" :disabled="busy">搜索</button><span>共 {{total}} 条</span><button type="button" class="icon-button" aria-label="刷新资源池" :disabled="busy" @click="run(load)"><Icon name="refresh"/></button></form>
   <div v-if="manager&&!readonly" class="pool-batch"><label><input type="checkbox" :checked="!!rows.length&&selected.length===rows.length" :disabled="!rows.length||busy" @change="selectVisible"/>选中本页</label><span>已选 {{selected.length}} 条</span><button class="btn secondary" :disabled="!selected.length||busy" @click="prepare('batch')">批量分配</button><small>同一批次最多 50 条；有版本冲突时整批回滚。</small></div>
   <div class="table-scroll"><table><thead><tr><th v-if="manager&&!readonly">选择</th><th>{{label}}名称</th><th>来源 / 地区</th><th>原负责人</th><th>{{mode==='pool'?'入池时间':'更新时间'}}</th><th>操作</th></tr></thead><tbody>
    <tr v-for="row in rows" :key="row.id"><td v-if="manager&&!readonly"><input type="checkbox" :checked="selected.includes(row.id)" :aria-label="`选择${row.name}`" @change="toggle(row.id)"/></td><td><strong>{{row.name}}</strong><small v-if="row.status">{{row.status}}</small></td><td>{{row.source||'—'}}<small>{{row.region||'—'}}</small></td><td>{{row.ownerName}}</td><td>{{time(mode==='pool'?row.poolEnteredAt:row.updatedAt)}}</td><td><div class="pool-actions"><button v-if="mode==='pool'&&write&&!readonly" class="text-button" :disabled="busy" @click="prepare('claim',row)">领取</button><button v-if="mode!=='pool'&&write&&!readonly&&(manager||mode==='mine')" class="text-button" :disabled="busy" @click="prepare('return',row)">退回</button><button v-if="manager&&!readonly" class="text-button" :disabled="busy" @click="prepare('assign',row)">派发</button><button class="text-button" :disabled="busy" @click="showHistory(row)">流转记录</button></div></td></tr>
    <tr v-if="!rows.length"><td :colspan="manager&&!readonly?6:5" class="empty-cell">{{busy?'正在加载…':mode==='pool'?'当前资源池没有可领取记录':'当前范围没有记录'}}</td></tr>
   </tbody></table></div>
   <div class="pool-pages"><span>第 {{page}} 页</span><button class="btn secondary" :disabled="busy||page<=1" @click="turn(-1)">上一页</button><button class="btn secondary" :disabled="busy||page*20>=total" @click="turn(1)">下一页</button></div>
  </section>
  <section v-if="historyId" class="card pool-history"><div class="pool-section-title"><h2>流转记录</h2><button class="text-button" @click="historyId=''">关闭</button></div><div v-if="!history.length" class="pool-empty">尚无流转记录</div><ol v-else><li v-for="event in history" :key="event.id"><strong>{{actions[event.action]||event.action}}</strong><span>{{event.fromName||'资源池'}} → {{event.toName||'资源池'}}</span><small>{{time(event.createdAt)}} · {{event.actorName||'系统规则'}} · 版本 {{event.resourceVersion}}</small><p v-if="event.reason">{{event.reason}}</p></li></ol></section>
  <section v-if="pending" class="card pool-confirm" role="dialog" aria-modal="true" aria-labelledby="pool-confirm-title"><div class="pool-section-title"><h2 id="pool-confirm-title">{{pending.kind==='claim'?'确认领取':pending.kind==='return'?'确认退回':pending.kind==='batch'?'批量分配':'主管派发'}}{{label}}</h2><button class="icon-button" aria-label="取消操作" @click="cancel"><Icon name="close"/></button></div><p>{{pending.kind==='batch'?`将 ${selected.length} 条${label}分配给指定成员`:pending.row?.name}}。提交后会重新检查资源版本、权限和接收成员保有量。</p><label v-if="pending.kind==='assign'||pending.kind==='batch'">接收成员<select v-model="recipient" required><option value="">选择成员</option><option v-for="member in recipients" :key="member.id" :value="member.id">{{member.name}}</option></select></label><label>操作说明<input v-model="reason" maxlength="300" placeholder="例如：客户区域调整"/></label><div class="pool-confirm-actions"><button class="btn secondary" :disabled="busy" @click="cancel">取消</button><button class="btn primary" :disabled="busy" @click="commit">{{busy?'正在处理…':'确认提交'}}</button></div></section>
 </section>
</template>

<style scoped>
.pool-workspace{display:grid;gap:16px}.pool-alert{padding:12px 16px;border:1px solid var(--crm-line);border-radius:10px;background:var(--crm-hover);color:var(--crm-accent)}.pool-alert.error{color:#ffb1b2;background:rgba(255,77,79,.11)}.pool-tabs{display:flex;gap:8px;flex-wrap:wrap}.pool-tabs button{padding:10px 16px;border:1px solid var(--crm-line);border-radius:10px;color:var(--crm-subtext);background:var(--crm-panel-thin);font-size:12px}.pool-tabs button.active{color:var(--crm-accent);background:var(--crm-hover);border-color:rgba(0,210,106,.35)}.pool-panel{overflow:hidden}.pool-toolbar,.pool-batch,.pool-pages{display:flex;gap:12px;align-items:center;flex-wrap:wrap;padding:14px 18px}.pool-toolbar>span,.pool-pages>span{margin-left:auto;color:var(--crm-muted);font-size:12px}.pool-search{display:flex;align-items:center;gap:8px;flex:1;max-width:420px;color:var(--crm-muted)}.pool-search input{width:100%;padding:9px 11px}.pool-batch{border-block:1px solid var(--crm-line);font-size:12px;color:var(--crm-subtext)}.pool-batch label{display:flex;align-items:center;gap:7px}.pool-batch small{color:var(--crm-muted)}.pool-workspace input[type=checkbox]{accent-color:var(--crm-accent)}.pool-panel table{width:100%;min-width:760px}.pool-panel td small{display:block;color:var(--crm-muted);margin-top:3px}.pool-actions{display:flex;gap:12px;flex-wrap:wrap}.pool-pages{border-top:1px solid var(--crm-line)}.pool-section-title{display:flex;align-items:center;justify-content:space-between;gap:10px}.pool-section-title h2{font-size:17px}.pool-history,.pool-confirm{padding:20px}.pool-history ol{list-style:none;padding:0;margin:14px 0 0}.pool-history li{padding:12px 0;border-top:1px solid var(--crm-line);display:flex;align-items:center;gap:12px;flex-wrap:wrap;font-size:12px}.pool-history li span{color:var(--crm-subtext)}.pool-history li small{margin-left:auto;color:var(--crm-muted)}.pool-history li p{width:100%;margin:0;color:var(--crm-muted)}.pool-empty{padding:15px 0;color:var(--crm-muted)}.pool-confirm{position:fixed;z-index:55;left:50%;top:50%;transform:translate(-50%,-50%);width:min(440px,calc(100vw - 32px));max-height:calc(100dvh - 32px);overflow:auto;border:1px solid var(--crm-line);background:var(--crm-panel-strong);box-shadow:var(--crm-shadow)}.pool-confirm p{color:var(--crm-subtext);font-size:12px;line-height:1.7}.pool-confirm label{display:grid;gap:8px;margin:14px 0;font-size:12px;color:var(--crm-subtext)}.pool-confirm :is(input,select){min-width:0;width:100%;padding:10px}.pool-confirm-actions{display:flex;justify-content:flex-end;gap:9px}@media(max-width:760px){.pool-toolbar{gap:8px}.pool-search{max-width:none;flex-basis:100%}}
</style>
