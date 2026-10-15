<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import Icon from './Icon.vue';

const props = withDefaults(defineProps<{ compact?: boolean }>(), { compact: false });
const now = ref(new Date());
let timer: ReturnType<typeof setInterval> | undefined;

const zones = [
  { city: '上海', region: '中国', flag: '🇨🇳', zone: 'Asia/Shanghai' },
  { city: '迪拜', region: '阿联酋', flag: '🇦🇪', zone: 'Asia/Dubai' },
  { city: '伦敦', region: '英国', flag: '🇬🇧', zone: 'Europe/London' },
  { city: '纽约', region: '美国', flag: '🇺🇸', zone: 'America/New_York' },
];

function time(zone: string, seconds = false) {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: zone,
    hour: '2-digit',
    minute: '2-digit',
    ...(seconds ? { second: '2-digit' } : {}),
    hour12: false,
  }).format(now.value);
}

function date(zone: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: zone,
    month: '2-digit',
    day: '2-digit',
    weekday: 'short',
  }).format(now.value);
}

const shanghaiTime = computed(() => time('Asia/Shanghai'));

onMounted(() => { timer = setInterval(() => { now.value = new Date(); }, 1000); });
onUnmounted(() => { if (timer) clearInterval(timer); });
</script>

<template>
  <details class="world-clock" :class="{ compact: props.compact }">
    <summary aria-label="打开世界时钟">
      <Icon name="clock" :size="17" />
      <span v-if="!props.compact"><small>上海</small><strong>{{ shanghaiTime }}</strong></span>
      <span v-else class="clock-time">{{ shanghaiTime }}</span>
      <Icon name="down" :size="13" />
    </summary>
    <section class="world-clock-panel" aria-label="全球主要市场时间">
      <header><div><small>GLOBAL TIME</small><strong>世界时钟</strong></div><span>实时</span></header>
      <div v-for="item in zones" :key="item.zone" class="world-clock-row">
        <span class="world-clock-flag">{{ item.flag }}</span>
        <span><strong>{{ item.city }}</strong><small>{{ item.region }} · {{ date(item.zone) }}</small></span>
        <time>{{ time(item.zone, true) }}</time>
      </div>
      <footer>日期与时间按各城市当地时区显示</footer>
    </section>
  </details>
</template>

<style scoped>
.world-clock{position:relative;color:inherit}.world-clock summary{list-style:none;height:36px;display:flex;align-items:center;gap:8px;padding:0 10px;border:1px solid var(--crm-line,rgba(255,255,255,.14));border-radius:10px;background:var(--crm-panel-thin,rgba(255,255,255,.06));color:var(--crm-subtext,#91a8bb);cursor:pointer;transition:.2s ease}.world-clock summary::-webkit-details-marker{display:none}.world-clock summary:hover,.world-clock[open] summary{color:var(--crm-accent,#00d26a);border-color:color-mix(in srgb,var(--crm-accent,#00d26a) 38%,transparent);box-shadow:0 0 18px var(--crm-glow,rgba(0,210,106,.16))}.world-clock summary>span{display:flex;align-items:baseline;gap:6px;white-space:nowrap}.world-clock summary small{font-size:10px;color:var(--crm-muted,#8298ad)}.world-clock summary strong,.clock-time{font-size:12px;font-variant-numeric:tabular-nums;letter-spacing:.2px}.world-clock-panel{position:absolute;right:0;top:44px;z-index:80;width:306px;padding:14px;border:1px solid var(--crm-line,rgba(255,255,255,.14));border-radius:16px;background:radial-gradient(ellipse 80% 60% at 0 0,rgba(255,255,255,.14),rgba(255,255,255,.03) 48%,transparent 72%),var(--crm-panel-strong,#151d29);box-shadow:0 22px 65px rgba(0,0,0,.4),inset 0 1px rgba(255,255,255,.09);backdrop-filter:blur(32px) saturate(190%);-webkit-backdrop-filter:blur(32px) saturate(190%)}.world-clock-panel header{display:flex;align-items:center;justify-content:space-between;padding:3px 5px 11px;border-bottom:1px solid var(--crm-line,rgba(255,255,255,.12))}.world-clock-panel header div{display:flex;flex-direction:column;gap:3px}.world-clock-panel header small{font-size:9px;letter-spacing:1.2px;color:var(--crm-muted,#8298ad)}.world-clock-panel header strong{font-size:15px;color:var(--crm-text,#e8eaed)}.world-clock-panel header>span{padding:3px 7px;border-radius:999px;background:rgba(0,210,106,.12);color:var(--crm-accent,#00d26a);font-size:10px}.world-clock-row{display:grid;grid-template-columns:30px 1fr auto;gap:10px;align-items:center;padding:12px 5px;border-bottom:1px solid var(--crm-line,rgba(255,255,255,.08))}.world-clock-row:last-of-type{border-bottom:0}.world-clock-flag{font-size:18px}.world-clock-row>span:nth-child(2){display:flex;flex-direction:column;gap:3px}.world-clock-row strong{font-size:12px;color:var(--crm-text,#e8eaed)}.world-clock-row small{font-size:10px;color:var(--crm-muted,#8298ad)}.world-clock-row time{font:600 15px/1.2 ui-monospace,SFMono-Regular,Menlo,monospace;color:var(--crm-text,#e8eaed);font-variant-numeric:tabular-nums}.world-clock-panel footer{padding:10px 5px 1px;font-size:10px;color:var(--crm-muted,#8298ad)}.compact summary{padding:0 8px}.compact .clock-time{display:none}@media(max-width:1280px){.world-clock:not(.compact) summary>span{display:none}}@media(max-width:760px){.world-clock-panel{position:fixed;top:70px;right:12px;width:min(306px,calc(100vw - 24px))}}
</style>
