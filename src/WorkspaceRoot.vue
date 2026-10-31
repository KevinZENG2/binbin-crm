<script setup lang="ts">
import type { Component } from 'vue';
import PreviewNotice from './PreviewNotice.vue';
defineProps<{ workspace: Component; showPreviewNotice: boolean }>();
</script>
<template><PreviewNotice v-if="showPreviewNotice"/><component :is="workspace"/></template>
