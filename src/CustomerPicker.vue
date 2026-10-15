<script setup lang="ts">
import {computed,onMounted,ref,watch} from 'vue';
type Request=<T>(path:string,method?:string,body?:unknown,headers?:Record<string,string>)=>Promise<T>;
type Option={id:string;name:string};
const props=withDefaults(defineProps<{modelValue:string;selectedName?:string;request:Request;disabled?:boolean;required?:boolean}>(),{required:true,selectedName:'',disabled:false});
const emit=defineEmits<{'update:modelValue':[value:string]}>();
const q=ref(''),page=ref(1),items=ref<Option[]>([]),total=ref(0),loading=ref(false),error=ref('');
const known=new Map<string,string>();if(props.modelValue&&props.selectedName)known.set(props.modelValue,props.selectedName);
const currentName=computed(()=>items.value.find(c=>c.id===props.modelValue)?.name??known.get(props.modelValue)??props.selectedName);
let generation=0;
async function load(){const current=++generation;loading.value=true;error.value='';try{const result=await props.request<{items:Option[];total:number}>(`/customers/options?page=${page.value}&pageSize=20&q=${encodeURIComponent(q.value)}`);if(current===generation){items.value=result.items;for(const c of result.items)known.set(c.id,c.name);total.value=result.total;}}catch(e){if(current===generation)error.value=e instanceof Error?e.message:'客户列表暂未加载';}finally{if(current===generation)loading.value=false;}}
function search(){page.value=1;load();}
function turn(delta:number){page.value+=delta;load();}
onMounted(()=>{if(!props.disabled)load();});
watch(()=>props.disabled,(disabled,previous)=>{if(!disabled&&previous&&!items.value.length)load();});
</script>
<template>
 <div class="customer-picker">
  <div v-if="disabled" class="customer-picker-value">{{currentName||'未选择客户'}}</div>
  <template v-else>
   <div class="customer-picker-search"><input v-model="q" type="search" maxlength="100" placeholder="输入客户名称查找" aria-label="查找可编辑客户" @keydown.enter.prevent="search"/><button type="button" class="btn secondary" :disabled="loading" @click="search">查找</button></div>
   <select :value="modelValue" :required="required" aria-label="选择所属客户" :disabled="loading" @change="emit('update:modelValue',($event.target as HTMLSelectElement).value)"><option value="">{{required?'请选择客户':'独立待办，不关联客户'}}</option><option v-if="modelValue&&!items.some(c=>c.id===modelValue)" :value="modelValue">{{currentName||'当前已选客户'}}</option><option v-for="c in items" :key="c.id" :value="c.id">{{c.name}}</option></select>
   <div class="customer-picker-pages"><small>{{loading?'正在查找…':total?'可选择 '+total+' 家客户':'暂无可编辑客户，可先在客户中心新增'}}</small><button type="button" :disabled="loading||page<=1" @click="turn(-1)">上一页</button><button type="button" :disabled="loading||page*20>=total" @click="turn(1)">下一页</button></div>
  </template>
  <small v-if="error" class="customer-picker-error" role="alert">{{error}}</small>
 </div>
</template>
<style scoped>
.customer-picker{display:flex;flex-direction:column;gap:10px;min-width:0}.customer-picker-search{display:flex;gap:8px}.customer-picker-search input,.customer-picker>select{width:100%;min-width:0;border:1px solid #cbd8ce;border-radius:6px;padding:11px;font-size:14px;background:#fff;color:#263f2d}.customer-picker-search input{flex:1}.customer-picker-pages{display:flex;align-items:center;gap:12px}.customer-picker-pages small{margin-right:auto;font-size:12px;color:#62776a;line-height:1.7}.customer-picker-pages button{font-size:12px;color:#256747;white-space:nowrap}.customer-picker-pages button:disabled{color:#839488}.customer-picker-error{color:#a52836;font-size:12px;line-height:1.7}.customer-picker-value{padding:11px;background:#f6f8f6;border:1px solid #dce5de;border-radius:6px;font-size:14px;color:#45584a}
</style>
