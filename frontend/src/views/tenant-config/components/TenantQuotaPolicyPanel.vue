<template>
  <section class="protection-panel">
    <a-spin :spinning="loading">
      <header class="workspace-head">
        <div><h2>Oracle配额保护</h2><p>通过官方Quota Policy限制实例、存储和网络资源创建。</p></div>
        <button class="demo-btn primary" type="button" @click="openConfig">配置Oracle配额保护</button>
      </header>
      <div class="stats">
        <article class="stat"><span>保护状态</span><strong :class="{ ok: overview.enabled }">{{ overview.enabled ? statusLabel(overview.policy?.status) : '尚未启用' }}</strong></article>
        <article class="stat"><span>A1上限</span><strong>{{ quotaValue('a1Ocpu', 'OCPU') }}</strong></article>
        <article class="stat"><span>块存储</span><strong>{{ quotaValue('blockStorage', 'GB') }}</strong></article>
        <article class="stat"><span>数据来源</span><strong :class="overview.accountLimitsComplete ? 'ok' : 'warn'">{{ overview.accountLimitsComplete ? '账户实际配额' : '官方模板兜底' }}</strong></article>
      </div>
      <div class="info"><b>Oracle</b><span>此模块只管理官方配额策略。读取不到账号配额时使用官方模板，最终由Oracle验证规则是否适用。</span></div>
    </a-spin>

    <Teleport to="body"><div v-if="configVisible" class="overlay" role="presentation" @wheel.stop>
      <section class="dialog" role="dialog" aria-modal="true" aria-labelledby="quota-dialog-title">
        <header class="dialog-head"><div><h3 id="quota-dialog-title">配置 Oracle 配额保护</h3><p>限制后续资源创建，不会删除或停止现有资源。</p></div><button class="x" type="button" aria-label="关闭" @click="configVisible=false">×</button></header>
        <div class="dialog-body">
          <div :class="['source', { complete: overview.accountLimitsComplete }]">
            <div><strong>{{ overview.accountLimitsComplete ? '已读取当前账户配额' : '未读取到完整账户配额，使用 Oracle 官方模板' }}</strong><span>{{ overview.accountLimitsComplete ? '资源上限参考当前租户实际配额。' : '提交时由Oracle验证当前账号实际支持的规则。' }}</span></div>
            <button class="link" type="button" :disabled="loading" @click="load">重新读取</button>
          </div>
          <div class="modes">
            <button v-for="item in profiles" :key="item.key" type="button" :class="['mode', { active: form.profile===item.key }]" @click="selectProfile(item.key)"><b v-if="item.key==='BASIC'" class="tag">推荐</b><strong>{{ item.label }}</strong><span>{{ item.description }}</span></button>
          </div>
          <div class="section"><div class="section-title"><i>CPU</i><strong>计算实例</strong></div><div class="rows">
            <div v-for="key in computeKeys" :key="key" class="row"><div class="name"><strong>{{ resource(key).label }}</strong><span>{{ descriptions[key] }}</span></div><div class="control"><input v-model.number="form.values[key]" type="range" :min="resource(key).min" :max="resource(key).max" :step="resource(key).step" @input="form.profile='CUSTOM'"><span class="value">{{ form.values[key] }} {{ resource(key).unit }}</span></div><span class="state">已限制</span></div>
            <div class="row"><div class="name"><strong>非免费计算Shape</strong><span>A2、E3、E4、GPU、DenseIO、HPC等</span></div><div class="control"><button :class="['switch',{on:paidComputeEnabled}]" type="button" @click="form.profile='CUSTOM';setPaidCompute(!paidComputeEnabled)"></button><span class="value">{{ paidComputeEnabled?'禁止创建':'不限制' }}</span></div><span class="state risk">高风险</span></div>
          </div></div>
          <div class="section"><div class="section-title"><i>VOL</i><strong>存储与网络</strong></div><div class="rows">
            <div v-for="key in storageKeys" :key="key" class="row"><div class="name"><strong>{{ resource(key).label }}</strong><span>{{ descriptions[key] }}</span></div><div class="control"><input v-model.number="form.values[key]" type="range" :min="resource(key).min" :max="resource(key).max" :step="resource(key).step" @input="form.profile='CUSTOM'"><span class="value">{{ form.values[key] }} {{ resource(key).unit }}</span></div><span class="state">已限制</span></div>
            <div class="row"><div class="name"><strong>保留公网IP与付费负载均衡</strong><span>避免创建常见付费网络资源</span></div><div class="control"><button :class="['switch',{on:networkRiskEnabled}]" type="button" @click="form.profile='CUSTOM';setNetworkRisk(!networkRiskEnabled)"></button><span class="value">{{ networkRiskEnabled ? '禁止' : '不限制' }}</span></div><span class="state risk">高风险</span></div>
          </div></div>
        </div>
        <footer class="dialog-foot"><span class="foot">此弹窗只管理Oracle Quota Policy，不包含流量监控。</span><div class="actions"><button v-if="overview.enabled" class="demo-btn danger-btn" type="button" @click="beginDisable">关闭保护</button><button class="demo-btn" type="button" @click="configVisible=false">取消</button><button class="demo-btn primary" type="button" :disabled="submitting||codeSending" @click="beginSave">TG验证并{{ overview.enabled?'保存':'启用' }}</button></div></footer>
      </section>
    </div></Teleport>

    <Teleport to="body"><div v-if="verifyVisible" class="overlay" role="presentation" @wheel.stop><section class="dialog sm" role="dialog" aria-modal="true"><header class="dialog-head"><div><h3>{{ verifyTitle }}</h3><p>验证码已发送至Telegram，有效期5分钟。</p></div><button class="x" type="button" @click="verifyVisible=false">×</button></header><div class="dialog-body"><input v-model="verifyCode" class="tg-input" maxlength="6" inputmode="numeric" placeholder="请输入6位验证码" @keyup.enter="submitVerified"></div><footer class="dialog-foot"><span class="foot">高风险写操作需要二次确认。</span><div class="actions"><button class="demo-btn" type="button" @click="verifyVisible=false">取消</button><button class="demo-btn primary" type="button" :disabled="submitting" @click="submitVerified">确认{{ pendingAction==='disable'?'关闭':'启用' }}</button></div></footer></section></div></Teleport>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { disableQuotaProtection, getQuotaProtectionOverview, saveQuotaProtection } from '../../../api/tenant'
import { sendVerifyCode } from '../../../api/system'
const props=defineProps<{tenantId:string}>()
const loading=ref(false),submitting=ref(false),codeSending=ref(false),configVisible=ref(false),verifyVisible=ref(false),verifyCode=ref(''),verifyTitle=ref('安全验证'),pendingAction=ref<'save'|'disable'>('save')
const overview=reactive<any>({enabled:false,profile:'BASIC',policy:null,resources:[],values:{},accountLimitsComplete:false})
const form=reactive<{profile:string;values:Record<string,number>;enabled:Record<string,boolean>}>({profile:'BASIC',values:{},enabled:{}})
const profiles=[{key:'BASIC',label:'基础保护',description:'贴近Always Free额度。'},{key:'STRICT',label:'严格保护',description:'禁止更多付费资源。'},{key:'CUSTOM',label:'自定义',description:'逐项调整资源限制。'}]
const computeKeys=['a1Ocpu','a1Memory','e2Micro'],storageKeys=['blockStorage','vcn']
const descriptions:Record<string,string>={a1Ocpu:'免费额度通常为租户合计4 OCPU',a1Memory:'免费额度通常为租户合计24 GB',e2Micro:'控制免费Micro实例数量',blockStorage:'所有引导卷和块卷共用容量',vcn:'限制虚拟云网络数量'}
const paidComputeKeys=['paidComputeA2','paidComputeE3','paidComputeE4','paidComputeE5','paidDenseIoE4','paidDenseIoE5','paidGpuA10']
const riskKeys=['reservedPublicIp','paidLoadBalancer10','paidLoadBalancer100','paidLoadBalancer400','paidLoadBalancer8000']
const paidComputeEnabled=computed(()=>paidComputeKeys.every(k=>form.enabled[k]&&Number(form.values[k])===0))
const networkRiskEnabled=computed(()=>riskKeys.every(k=>form.enabled[k]&&Number(form.values[k])===0))
function resource(key:string){return overview.resources.find((x:any)=>x.key===key)||{key,label:key,min:0,max:1,step:1,unit:''}}
function apply(data:any){Object.assign(overview,data||{})}
async function load(){loading.value=true;try{apply((await getQuotaProtectionOverview({id:props.tenantId})).data)}catch(e:any){message.error(e?.message||'读取 Oracle 配额保护失败')}finally{loading.value=false}}
function openConfig(){form.profile=overview.profile||'BASIC';form.values={...(overview.values||{})};form.enabled=Object.fromEntries((overview.resources||[]).map((x:any)=>[x.key,Object.hasOwn(form.values,x.key)]));if(!Object.keys(form.values).length)selectProfile('BASIC');configVisible.value=true}
function selectProfile(profile:string){form.profile=profile;if(profile==='CUSTOM')return;const f=profile==='STRICT'?'strict':'basic',ef=profile==='STRICT'?'strictEnabled':'basicEnabled';form.values=Object.fromEntries(overview.resources.map((x:any)=>[x.key,Number(x[f]??0)]));form.enabled=Object.fromEntries(overview.resources.map((x:any)=>[x.key,!!x[ef]]))}
function setPaidCompute(value:boolean){for(const k of paidComputeKeys){form.enabled[k]=value;form.values[k]=0}}
function setNetworkRisk(value:boolean){for(const k of riskKeys){form.enabled[k]=value;form.values[k]=0}}
async function requestCode(action:string,text:string){verifyCode.value='';await sendVerifyCode(action,{contextKey:props.tenantId,contextText:text});verifyVisible.value=true}
async function beginSave(){pendingAction.value='save';verifyTitle.value=`安全验证 — ${overview.enabled?'修改':'启用'}Oracle配额保护`;configVisible.value=false;codeSending.value=true;try{await requestCode('quotaProtectionSave',overview.enabled?'修改 Oracle 配额保护':'启用 Oracle 配额保护')}catch(e:any){configVisible.value=true;message.error(e?.message||'发送验证码失败')}finally{codeSending.value=false}}
async function beginDisable(){pendingAction.value='disable';verifyTitle.value='安全验证 — 关闭Oracle配额保护';configVisible.value=false;codeSending.value=true;try{await requestCode('quotaProtectionDisable','关闭 Oracle 配额保护')}catch(e:any){configVisible.value=true;message.error(e?.message||'发送验证码失败')}finally{codeSending.value=false}}
async function submitVerified(){if(!/^\d{6}$/.test(verifyCode.value)){message.warning('请输入6位验证码');return}submitting.value=true;try{const r=pendingAction.value==='disable'?await disableQuotaProtection({id:props.tenantId,verifyCode:verifyCode.value}):await saveQuotaProtection({id:props.tenantId,profile:form.profile,values:Object.fromEntries(Object.entries(form.values).filter(([k])=>form.enabled[k])),verifyCode:verifyCode.value});apply(r.data);verifyVisible.value=false;message.success(pendingAction.value==='disable'?'Oracle配额保护已关闭':'Oracle配额保护已保存')}catch(e:any){message.error(e?.message||'操作失败')}finally{submitting.value=false}}
function quotaValue(key:string,unit:string){const v=overview.values?.[key];return v==null?'未设置':`${v} ${unit}`} function statusLabel(v:string){return({ACTIVE:'已生效',CREATING:'创建中',UPDATING:'更新中',DELETING:'关闭中',FAILED:'失败'} as any)[v]||'已启用'}
onMounted(load)
</script>

<style scoped>
.protection-panel{min-width:0;color:#edf2ff}.workspace-head{display:flex;justify-content:space-between;align-items:center;gap:15px}.workspace-head h2{margin:0;font-size:16px}.workspace-head p{margin:5px 0 0;color:#97a5bf;font-size:12px}.demo-btn{padding:9px 14px;border:1px solid #263550;border-radius:9px;background:#18233b;color:#edf2ff;font-weight:700;cursor:pointer}.demo-btn.primary{border-color:#756dff;background:linear-gradient(180deg,#827bff,#665feb)}.demo-btn:active{transform:translateY(1px)}.demo-btn:disabled{cursor:not-allowed;opacity:.55}.danger-btn{color:#ff7480}.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;margin-top:14px}.stat{padding:12px;border:1px solid #1d2a42;border-radius:9px;background:#0c1527}.stat span{display:block;color:#687995;font-size:10px}.stat strong{display:block;margin-top:5px;font-size:13px}.ok{color:#47d39b}.warn,.risk{color:#f3b85c}.info{display:flex;gap:9px;margin-top:12px;padding:11px;border:1px solid rgba(117,109,255,.2);border-radius:9px;background:rgba(117,109,255,.06);color:#97a5bf;font-size:11px;line-height:1.55}.overlay{position:fixed;inset:0;z-index:2000;display:grid;place-items:center;padding:16px;background:rgba(2,6,14,.74);backdrop-filter:blur(6px)}.dialog{display:flex;width:min(700px,100%);max-height:calc(100dvh - 32px);overflow:hidden;flex-direction:column;border:1px solid #344463;border-radius:17px;background:#10192c;box-shadow:0 30px 100px rgba(0,0,0,.58)}.dialog.sm{width:min(470px,100%)}.dialog-head{display:flex;flex:0 0 auto;justify-content:space-between;align-items:flex-start;padding:18px;border-bottom:1px solid #1d2a42}.dialog-head h3{margin:0;font-size:18px}.dialog-head p{margin:5px 0 0;color:#97a5bf;font-size:11px}.x{border:0;background:none;color:#97a5bf;font-size:25px;cursor:pointer}.dialog-body{min-height:0;padding:17px;overflow-y:auto;overscroll-behavior:contain}.source{display:flex;justify-content:space-between;gap:12px;align-items:center;padding:11px 12px;border:1px solid rgba(243,184,92,.24);border-radius:10px;background:rgba(243,184,92,.07)}.source.complete{border-color:rgba(71,211,155,.24);background:rgba(71,211,155,.07)}.source strong{font-size:12px}.source span{display:block;margin-top:3px;color:#97a5bf;font-size:10px}.link{border:0;background:none;color:#9a94ff;font-weight:700;cursor:pointer}.modes{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin:12px 0}.mode{position:relative;padding:12px;border:1px solid #263550;border-radius:10px;background:#0c1527;color:#edf2ff;cursor:pointer;text-align:left}.mode.active{border-color:#756dff;background:rgba(117,109,255,.1)}.mode strong{font-size:12px}.mode span{display:block;margin-top:4px;color:#97a5bf;font-size:10px;line-height:1.45}.tag{position:absolute;right:8px;top:7px;color:#9a94ff;font-size:9px}.section{margin-top:12px;padding-top:12px;border-top:1px solid #1d2a42}.section-title{display:flex;align-items:center;gap:8px;margin-bottom:8px}.section-title i{width:25px;height:25px;display:grid;place-items:center;border-radius:7px;background:#182540;color:#aeb9d0;font-size:9px;font-style:normal;font-weight:800}.section-title strong{font-size:13px}.rows{display:grid;gap:7px}.row{display:grid;grid-template-columns:minmax(170px,1.2fr) minmax(150px,1fr) 68px;gap:12px;align-items:center;padding:10px 11px;border:1px solid #1d2a42;border-radius:9px;background:#0c1527}.name strong,.name span{display:block}.name strong{font-size:12px}.name span{margin-top:3px;color:#687995;font-size:10px}.control{display:flex;min-width:0;align-items:center;gap:8px}.control input[type=range]{width:100%;min-width:0;accent-color:#756dff}.value{min-width:62px;text-align:right;color:#c3cee2;font-size:11px}.state{text-align:right;color:#47d39b;font-size:10px}.switch{width:39px;height:21px;padding:3px;border:0;border-radius:999px;background:#2b3951;cursor:pointer}.switch:before{content:"";display:block;width:15px;height:15px;border-radius:50%;background:#9aa8be;transition:.18s}.switch.on{background:#756dff}.switch.on:before{transform:translateX(18px);background:#fff}.switch:disabled{cursor:not-allowed;opacity:.65}.dialog-foot{display:flex;flex:0 0 auto;justify-content:space-between;gap:12px;align-items:center;padding:14px 18px;border-top:1px solid #1d2a42}.foot{max-width:390px;color:#687995;font-size:10px}.actions{display:flex;gap:8px}.tg-input{width:100%;padding:12px;border:1px solid #263550;border-radius:9px;background:#0c1527;color:#edf2ff;font-size:18px;letter-spacing:.16em}
@media(max-width:780px){.workspace-head{align-items:stretch;flex-direction:column}.stats{grid-template-columns:1fr 1fr}.overlay{padding:0}.dialog{width:100%;height:100dvh;max-height:none;border-radius:0}.modes{grid-template-columns:1fr}.row{grid-template-columns:1fr}.state{text-align:left}.dialog-foot{align-items:stretch;flex-direction:column}.actions{display:grid;grid-template-columns:1fr 1fr}.actions .demo-btn{width:100%}}@media(max-width:460px){.stats{grid-template-columns:1fr}}
</style>
