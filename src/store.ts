import { reactive, watch } from 'vue';
import { seedState, type State } from './domain';
const KEY = 'binbin-crm-interactive-preview-v1';
function load(): State {
  try {
    const text = localStorage.getItem(KEY); if (!text) return seedState(); const data = JSON.parse(text);
    const arrays = ['plans', 'tenants', 'members', 'customers', 'contacts', 'leads', 'opportunities', 'tasks', 'activities', 'audits', 'renewRequests'];
    return data.schema === 1 && arrays.every(k => Array.isArray(data[k])) ? data : seedState();
  } catch { return seedState(); }
}
export const state = reactive<State>(load());
export let persistenceAvailable = true;
watch(state, () => { try { localStorage.setItem(KEY, JSON.stringify(state)); } catch { persistenceAvailable = false; } }, { deep: true });
export function resetDemo(): void { Object.assign(state, seedState()); }
export function plainState(): State { return JSON.parse(JSON.stringify(state)); }
export function mutate(fn: (draft: State) => void): void { const draft = plainState(); fn(draft); Object.assign(state, draft); }
