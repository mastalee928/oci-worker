<template>
  <section class="traffic-panel">
    <header class="toolbar">
      <div><h3>OCIWorker 流量保护</h3><p>汇总当前租户全部实例及全部 VNIC 的月度估算出站流量。</p></div>
      <div class="toolbar-actions">
        <span :class="['status-text', { enabled: config.enabled }]">{{ config.enabled ? '保护已开启' : '保护已关闭' }}</span>
        <a-switch :checked="!!config.enabled" :loading="switching || codeSending" @change="beginToggle" />
        <a-button :loading="refreshing" @click="refreshNow">立即刷新</a-button>
        <a-button type="primary" @click="openConfig">配置流量保护</a-button>
      </div>
    </header>
    <a-alert v-if="config.lastError" type="warning" show-icon :message="config.lastError" class="alert" />
    <a-spin :spinning="loading">
      <div class="metrics">
        <div><span>本月估算流量</span><strong>{{ bytesLabel(config.monthlyBytes) }}</strong></div>
        <div><span>保护额度</span><strong>{{ bytesLabel(config.monthlyLimitBytes) }}</strong></div>
        <div><span>当前使用率</span><strong :class="{ danger: usagePercent >= 100, warning: usagePercent >= config.warningPercent }">{{ usagePercent.toFixed(1) }}%</strong></div>
        <div><span>下次采集</span><strong>{{ config.enabled ? dateLabel(config.nextCollectTime) : '不会采集' }}</strong></div>
      </div>
      <a-progress :percent="Math.min(100, usagePercent)" :status="usagePercent >= 100 ? 'exception' : 'normal'" />
      <div v-if="instances.length" class="instance-grid">
        <article v-for="item in instances" :key="item.instanceId"><strong>{{ item.instanceName || item.instanceId }}</strong><span>{{ bytesLabel(item.bytesToNetwork) }}</span><small>{{ item.lifecycleState || '-' }}</small></article>
      </div>
      <a-empty v-else-if="!loading" description="暂无本月实例流量数据" />
      <div v-if="actions.length" class="action-list">
        <h4>最近自动处置</h4>
        <article v-for="item in actions" :key="item.id">
          <div><strong>停止全部运行实例</strong><span>{{ dateLabel(item.createTime) }}</span></div>
          <div class="action-result"><span>成功 {{ item.successCount || 0 }} 台，失败 {{ item.failureCount || 0 }} 台</span><small v-if="item.errorSummary">{{ item.errorSummary }}</small></div>
        </article>
      </div>
      <p class="estimate-note">根据 VnicToNetworkBytes 指标估算，可能包含部分私网或 Oracle 服务通信，不能等同于最终计费流量。</p>
    </a-spin>

    <a-modal v-model:open="configVisible" title="配置 OCIWorker 流量保护" ok-text="保存并验证" :confirm-loading="saving || codeSending" :mask-closable="false" :keyboard="false" @ok="beginSave">
      <a-form layout="vertical">
        <a-form-item label="每月保护额度"><a-input-number v-model:value="form.monthlyLimitTb" :min="1" :max="100" addon-after="TB" style="width:100%" /></a-form-item>
        <a-form-item label="Telegram 预警阈值"><a-slider v-model:value="form.warningPercent" :min="50" :max="95" :step="5" /><span>{{ form.warningPercent }}%</span></a-form-item>
        <a-form-item label="达到 100% 后"><a-radio-group v-model:value="form.exceedAction" class="action-options"><a-radio value="ALERT_ONLY">仅发送 Telegram 紧急告警</a-radio><a-radio value="STOP_ALL_RUNNING_INSTANCES">停止全部运行实例并发送通知</a-radio></a-radio-group></a-form-item>
      </a-form>
    </a-modal>
    <a-modal v-model:open="verifyVisible" :title="verifyTitle" ok-text="确认" :confirm-loading="saving || switching" :mask-closable="false" :keyboard="false" @ok="submitVerifiedAction">
      <a-alert type="warning" show-icon message="验证码已发送至 Telegram" style="margin-bottom:12px" />
      <a-input v-model:value="verifyCode" maxlength="6" inputmode="numeric" placeholder="请输入 6 位验证码" />
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { getTrafficProtectionOverview, refreshTrafficProtection, saveTrafficProtection, setTrafficProtectionEnabled } from '../../../api/tenant'
import { sendVerifyCode } from '../../../api/system'
const props = defineProps<{ tenantId: string }>()
const loading=ref(false),refreshing=ref(false),saving=ref(false),switching=ref(false),codeSending=ref(false),configVisible=ref(false),verifyVisible=ref(false)
const config=reactive<any>({enabled:false,monthlyBytes:0,monthlyLimitBytes:10*1024**4,warningPercent:80,exceedAction:'ALERT_ONLY'})
const instances=ref<any[]>([]),actions=ref<any[]>([]),usagePercent=ref(0),verifyCode=ref(''),verifyTitle=ref('安全验证')
const pendingAction=ref<'save'|'enable'|'disable'>('save')
const form=reactive({monthlyLimitTb:10,warningPercent:80,exceedAction:'ALERT_ONLY'})
function apply(data:any){Object.assign(config,data?.config||{});instances.value=data?.instances||[];actions.value=data?.actions||[];usagePercent.value=Number(data?.usagePercent||0)}
async function load(){loading.value=true;try{apply((await getTrafficProtectionOverview({id:props.tenantId})).data)}catch(e:any){message.error(e?.message||'读取流量保护状态失败')}finally{loading.value=false}}
async function refreshNow(){refreshing.value=true;try{apply((await refreshTrafficProtection({id:props.tenantId})).data);message.success('流量数据已刷新')}catch(e:any){message.error(e?.message||'刷新失败')}finally{refreshing.value=false}}
function openConfig(){form.monthlyLimitTb=Math.max(1,Math.round(Number(config.monthlyLimitBytes||0)/1024**4));form.warningPercent=config.warningPercent||80;form.exceedAction=config.exceedAction||'ALERT_ONLY';configVisible.value=true}
async function requestCode(action:string,text:string){verifyCode.value='';await sendVerifyCode(action,{contextKey:props.tenantId,contextText:text});verifyVisible.value=true}
async function beginToggle(value:boolean){if(codeSending.value)return;pendingAction.value=value?'enable':'disable';verifyTitle.value=`安全验证 — ${value?'开启':'关闭'}流量保护`;codeSending.value=true;try{await requestCode(value?'trafficProtectionEnable':'trafficProtectionDisable',value?'开启流量保护':'关闭流量保护')}catch(e:any){message.error(e?.message||'发送验证码失败')}finally{codeSending.value=false}}
async function beginSave(){if(codeSending.value)return;pendingAction.value='save';verifyTitle.value='安全验证 — 修改流量保护';configVisible.value=false;codeSending.value=true;try{await requestCode('trafficProtectionSave','修改流量保护')}catch(e:any){configVisible.value=true;message.error(e?.message||'发送验证码失败')}finally{codeSending.value=false}}
async function submitVerifiedAction(){if(!/^\d{6}$/.test(verifyCode.value)){message.warning('请输入 6 位验证码');return}try{if(pendingAction.value==='save'){saving.value=true;apply((await saveTrafficProtection({id:props.tenantId,...form,verifyCode:verifyCode.value})).data);message.success('流量保护配置已保存')}else{switching.value=true;apply((await setTrafficProtectionEnabled({id:props.tenantId,enabled:pendingAction.value==='enable',verifyCode:verifyCode.value})).data);message.success(pendingAction.value==='enable'?'流量保护已开启':'流量保护已关闭')}verifyVisible.value=false}catch(e:any){message.error(e?.message||'操作失败')}finally{saving.value=false;switching.value=false}}
function bytesLabel(v:any){let n=Number(v||0);const u=['B','KB','MB','GB','TB'];let i=0;while(n>=1024&&i<u.length-1){n/=1024;i++}return `${n.toFixed(i>=3?2:0)} ${u[i]}`}
function dateLabel(v:any){if(!v)return '-';const d=new Date(v);return Number.isNaN(d.getTime())?'-':d.toLocaleString('zh-CN',{hour12:false})}
onMounted(load)
</script>

<style scoped>
.toolbar{display:flex;justify-content:space-between;align-items:flex-start;gap:14px}.toolbar h3{margin:0;font-size:16px}.toolbar p{margin:5px 0 0;color:var(--text-sub);font-size:12px}.toolbar-actions{display:flex;align-items:center;gap:8px}.status-text{color:var(--text-sub);font-size:11px}.status-text.enabled{color:#34d399}.alert{margin-top:12px}.metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin:14px 0}.metrics>div,.instance-grid article{padding:11px;border:1px solid var(--border);border-radius:9px;background:var(--input-bg)}.metrics span,.instance-grid span,.instance-grid small{display:block;color:var(--text-sub);font-size:10px}.metrics strong{display:block;margin-top:5px;font-size:13px}.warning{color:#f3b85c}.danger{color:#ff7480}.instance-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;margin-top:12px}.instance-grid article{min-width:0}.instance-grid strong{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px}.instance-grid span{margin-top:5px}.estimate-note{margin:12px 0 0;padding:10px;border:1px solid var(--border);border-radius:8px;color:var(--text-sub);font-size:10px}@media(max-width:768px){.toolbar{flex-direction:column}.toolbar-actions{width:100%;flex-wrap:wrap}.metrics{grid-template-columns:1fr 1fr}.instance-grid{grid-template-columns:1fr}.traffic-panel{min-width:0;overflow:hidden}}
.action-options{display:grid;gap:8px}.action-list{margin-top:14px}.action-list h4{margin:0 0 8px;font-size:12px}.action-list article{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:10px;border:1px solid var(--border);border-radius:8px;background:var(--input-bg)}.action-list article+article{margin-top:6px}.action-list strong,.action-list span,.action-list small{display:block}.action-list strong{font-size:11px}.action-list span{color:var(--text-sub);font-size:10px}.action-result{text-align:right}.action-result small{max-width:360px;margin-top:3px;color:#ff7480;font-size:9px}@media(max-width:768px){.action-list article{align-items:flex-start;flex-direction:column;gap:5px}.action-result{text-align:left}}
</style>
