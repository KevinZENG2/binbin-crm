<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import { previewHasExpired, readPreviewInfo, type PreviewInfo } from './previewInfo';

const preview = ref<PreviewInfo | null>(null);
const now = ref(Date.now());
const banner = ref<HTMLElement | null>(null);
let observer: ResizeObserver | undefined;
let timer: ReturnType<typeof setInterval> | undefined;
const expired = computed(() => preview.value ? previewHasExpired(preview.value, now.value) : false);
const expiryLabel = computed(() => preview.value ? new Intl.DateTimeFormat('zh-CN', {
  month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit', timeZoneName: 'short', hour12: false,
}).format(new Date(preview.value.expiresAt)) : '');

onMounted(async () => {
  try {
    const response = await fetch('/api/v1/health', { credentials: 'same-origin', cache: 'no-store' });
    if (!response.ok) return;
    preview.value = readPreviewInfo(await response.json());
    if (preview.value) {
      timer = setInterval(() => { now.value = Date.now(); }, 15_000);
      await nextTick();
      observer = new ResizeObserver(([entry]) => document.documentElement.style.setProperty('--crm-review-height', `${entry.target.getBoundingClientRect().height}px`));
      if (banner.value) observer.observe(banner.value);
    }
  } catch { /* The workspace owns its ordinary connection status. */ }
});
onUnmounted(() => { if (timer) clearInterval(timer); observer?.disconnect(); document.documentElement.style.removeProperty('--crm-review-height'); });
</script>

<template>
  <aside ref="banner" v-if="preview" class="crm-preview-notice" role="status">
    <strong>{{expired ? '验收窗口已结束' : '临时在线验收'}}</strong>
    <span>仅使用测试资料 · {{expired ? '本次预览正在关闭' : '结束时间'}}
      <time :datetime="preview.expiresAt">{{expiryLabel}}</time> · 结束后测试数据不保留</span>
  </aside>
</template>

<style scoped>
.crm-preview-notice{position:fixed;top:0;left:0;right:0;z-index:60;display:flex;align-items:center;justify-content:center;gap:12px;flex-wrap:wrap;min-height:38px;padding:8px 16px;box-sizing:border-box;background:rgba(16,24,39,.96);border-bottom:1px solid rgba(96,165,250,.28);color:#dce7f7;font:12px/1.5 system-ui,sans-serif;backdrop-filter:blur(12px)}
.crm-preview-notice strong{font-size:11px;letter-spacing:.06em;color:#a5ccff;border:1px solid rgba(96,165,250,.4);border-radius:4px;padding:1px 6px;white-space:nowrap}.crm-preview-notice time{color:#fff;margin-left:4px}
@media(max-width:640px){.crm-preview-notice{gap:4px;justify-content:flex-start;padding:7px 12px;font-size:11px}}
</style>

<style>
#app:has(>.crm-preview-notice){padding-top:var(--crm-review-height,38px)}
#app:has(>.crm-preview-notice) .live-shell.workspace-active .live-header{top:var(--crm-review-height,38px)}
#app:has(>.crm-preview-notice) .live-shell .live-navigation,
#app:has(>.crm-preview-notice) .live-ai-pane{top:calc(64px + var(--crm-review-height,38px))}
#app:has(>.crm-preview-notice) .live-nav-backdrop{top:calc(64px + var(--crm-review-height,38px))}
</style>
