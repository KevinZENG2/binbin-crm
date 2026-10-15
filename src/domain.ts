export type Role = 'admin' | 'manager' | 'sales' | 'viewer';
export type Stage = '初次接触' | '需求确认' | '方案讨论' | '报价谈判' | '签约准备' | '成交' | '丢单';
export const stages: Stage[] = ['初次接触', '需求确认', '方案讨论', '报价谈判', '签约准备', '成交', '丢单'];
export const probabilities: Record<Stage, number> = { 初次接触: 10, 需求确认: 30, 方案讨论: 50, 报价谈判: 70, 签约准备: 90, 成交: 100, 丢单: 0 };
export const roleLabels: Record<Role, string> = { admin: '企业管理员', manager: '销售经理', sales: '销售人员', viewer: '只读成员' };
export interface Plan { id: string; name: string; price: string; seats: number; storageGB: number; customerLimit: number; modules: string[]; description: string }
export interface Tenant { id: string; name: string; industry: string; planId: string; expiresAt: string; status: 'active' | 'suspended'; createdAt: string; storageUsedMB: number; adminName: string; logo: string }
export interface Member { id: string; tenantId: string; name: string; email: string; role: Role; department: string; active: boolean }
export interface Customer { id: string; tenantId: string; name: string; industry: string; level: string; source: string; region: string; ownerId: string; shares: string[]; creditCode: string; description: string; createdAt: string; version: number }
export interface Contact { id: string; tenantId: string; customerId: string; name: string; position: string; phone: string; email: string; primary: boolean }
export interface Lead { id: string; tenantId: string; title: string; company: string; contact: string; phone: string; source: string; ownerId: string; status: '新建' | '跟进中' | '有效' | '已转换' | '无效'; summary: string; createdAt: string; version: number; conversion?: { customerId: string; contactId: string; opportunityId: string | null } }
export interface Opportunity { id: string; tenantId: string; customerId: string; title: string; ownerId: string; amount: string; actualAmount: string; stage: Stage; closeDate: string; nextAction: string; reason: string; createdAt: string; updatedAt: string; version: number; history: { from: Stage | null; to: Stage; at: string; actor: string; reason: string }[] }
export interface Task { id: string; tenantId: string; title: string; assigneeId: string; customerId: string; dueDate: string; priority: '高' | '中' | '低'; done: boolean; completedAt: string | null }
export interface Activity { id: string; tenantId: string; customerId: string; actorId: string; type: string; content: string; at: string }
export interface Audit { id: string; tenantId: string | null; actor: string; action: string; target: string; at: string }
export interface RenewRequest { id: string; tenantId: string; months: number; status: '待处理' | '已确认' | '已驳回'; at: string }
export interface State { schema: 1; plans: Plan[]; tenants: Tenant[]; members: Member[]; customers: Customer[]; contacts: Contact[]; leads: Lead[]; opportunities: Opportunity[]; tasks: Task[]; activities: Activity[]; audits: Audit[]; renewRequests: RenewRequest[] }
export interface Context { tenantId: string; memberId: string }
export class DomainError extends Error { constructor(public code: string, message: string) { super(message); } }
const fail = (code: string, message: string): never => { throw new DomainError(code, message); };
export const uid = () => crypto.randomUUID();
export const now = () => new Date().toISOString();
export function dateOnly(date = new Date()): string { return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(date); }
export function offsetDate(days: number, base = new Date()): string { return dateOnly(new Date(base.getTime() + days * 86400000)); }
export function addMonths(date: string, count: number): string {
  const [y, m, d] = date.split('-').map(Number); const target = new Date(Date.UTC(y, m - 1 + count, 1));
  const last = new Date(Date.UTC(target.getUTCFullYear(), target.getUTCMonth() + 1, 0)).getUTCDate();
  return `${target.getUTCFullYear()}-${String(target.getUTCMonth() + 1).padStart(2, '0')}-${String(Math.min(d, last)).padStart(2, '0')}`;
}
export function cents(value: string): number {
  const str = value.trim(); if (!/^\d{1,9}(\.\d{1,2})?$/.test(str)) return fail('INVALID_AMOUNT', '金额应为非负数字，最多两位小数，整数部分最多九位');
  const [a, b = ''] = str.split('.'); return Number(a) * 100 + Number(b.padEnd(2, '0'));
}
export const amountString = (value: string) => (cents(value) / 100).toFixed(2);
export function currentMember(state: State, ctx: Context): Member {
  const member = state.members.find(m => m.id === ctx.memberId && m.tenantId === ctx.tenantId && m.active);
  if (!member) return fail('MEMBER_UNAVAILABLE', '当前演示成员已停用或不属于所选企业'); return member;
}
export function currentTenant(state: State, ctx: Context): Tenant {
  currentMember(state, ctx); const tenant = state.tenants.find(t => t.id === ctx.tenantId);
  if (!tenant) return fail('TENANT_UNAVAILABLE', '企业不存在'); return tenant;
}
export function subscriptionState(tenant: Tenant, today = dateOnly()): '正常' | '只读' | '已暂停' {
  if (tenant.status === 'suspended') return '已暂停'; return tenant.expiresAt < today ? '只读' : '正常';
}
export function requireWrite(state: State, ctx: Context, today = dateOnly()): Member {
  const member = currentMember(state, ctx); const status = subscriptionState(currentTenant(state, ctx), today);
  if (status !== '正常') return fail('SUBSCRIPTION_READONLY', status === '只读' ? '订阅已到期，当前只读。请由企业管理员提交续费申请' : '企业已暂停，请联系平台运营人员');
  if (member.role === 'viewer') return fail('ACTION_FORBIDDEN', '当前演示成员为只读角色'); return member;
}
export function canRead(state: State, ctx: Context, record: { tenantId: string; ownerId: string; shares?: string[] }): boolean {
  const member = currentMember(state, ctx);
  if (record.tenantId !== ctx.tenantId) return false;
  return member.role !== 'sales' || record.ownerId === member.id || Boolean(record.shares?.includes(member.id));
}
export function visibleCustomers(state: State, ctx: Context): Customer[] { return state.customers.filter(c => canRead(state, ctx, c)); }
export function visibleLeads(state: State, ctx: Context): Lead[] { return state.leads.filter(l => canRead(state, ctx, l)); }
export function visibleOpportunities(state: State, ctx: Context): Opportunity[] { return state.opportunities.filter(o => canRead(state, ctx, o)); }
export function visibleTasks(state: State, ctx: Context): Task[] {
  const m = currentMember(state, ctx); return state.tasks.filter(t => t.tenantId === ctx.tenantId && (m.role !== 'sales' || t.assigneeId === m.id));
}
export function customerById(state: State, ctx: Context, id: string): Customer {
  const c = state.customers.find(c => c.id === id && canRead(state, ctx, c));
  if (!c) return fail('RESOURCE_UNAVAILABLE', '客户不存在或当前演示身份无权访问'); return c;
}
export function audit(state: State, ctx: Context | null, action: string, target: string): void {
  state.audits.unshift({ id: uid(), tenantId: ctx?.tenantId ?? null, actor: ctx ? currentMember(state, ctx).name : '平台运营（演示）', action, target, at: now() });
  state.audits = state.audits.slice(0, 500);
}
export function ensureOwner(state: State, ctx: Context, ownerId: string): void {
  const member = currentMember(state, ctx); const owner = state.members.find(m => m.id === ownerId && m.tenantId === ctx.tenantId && m.active && m.role !== 'viewer');
  if (!owner) fail('INVALID_OWNER', '负责人必须是当前企业的有效业务成员');
  if (member.role === 'sales' && ownerId !== member.id) fail('ACTION_FORBIDDEN', '销售人员只能创建自己负责的记录');
}
export interface CustomerInput { name: string; industry: string; level: string; source: string; region: string; ownerId: string; creditCode: string; description: string; contactName?: string; contactPhone?: string; contactPosition?: string }
export function addCustomer(state: State, ctx: Context, input: CustomerInput): Customer {
  requireWrite(state, ctx); ensureOwner(state, ctx, input.ownerId);
  const name = input.name.trim(); if (!name || !input.source.trim()) fail('MISSING_FIELDS', '请填写客户名称和来源');
  if (name.length > 200) fail('INVALID_NAME', '客户名称最多 200 字');
  const creditCode = input.creditCode.trim().toUpperCase();
  if (creditCode && !/^[0-9A-Z]{18}$/.test(creditCode)) fail('INVALID_CREDIT_CODE', '演示校验要求信用代码为 18 位字母或数字');
  if (creditCode && state.customers.some(c => c.tenantId === ctx.tenantId && c.creditCode === creditCode)) fail('DUPLICATE_ENTITY', '当前企业已有相同信用代码的客户，请选择已有客户');
  const plan = state.plans.find(p => p.id === currentTenant(state, ctx).planId)!;
  if (state.customers.filter(c => c.tenantId === ctx.tenantId).length >= plan.customerLimit) fail('QUOTA_EXCEEDED', '已达到当前套餐的客户数量上限');
  if (input.contactName?.trim() && !input.contactPhone?.trim()) fail('MISSING_CONTACT_PHONE', '填写联系人时，请同时填写联系方式');
  const customer: Customer = { id: uid(), tenantId: ctx.tenantId, name, creditCode, industry: input.industry, level: input.level, source: input.source, region: input.region, ownerId: input.ownerId, description: input.description, shares: [], createdAt: now(), version: 0 };
  state.customers.unshift(customer);
  if (input.contactName?.trim()) state.contacts.push({ id: uid(), tenantId: ctx.tenantId, customerId: customer.id, name: input.contactName.trim(), phone: input.contactPhone!.trim(), email: '', position: input.contactPosition || '联系人', primary: true });
  audit(state, ctx, '新增客户', name); return customer;
}
export function updateCustomer(state: State, ctx: Context, id: string, expectedVersion: number, patch: Pick<Customer, 'name' | 'industry' | 'level' | 'source' | 'region' | 'description'>): void {
  const member = requireWrite(state, ctx); const c = customerById(state, ctx, id);
  if (member.role === 'sales' && c.ownerId !== member.id) fail('ACTION_FORBIDDEN', '获共享的客户可查看和跟进，资料编辑仍由负责人或管理者执行');
  if (c.version !== expectedVersion) fail('VERSION_CONFLICT', '客户已修改，请刷新后重试');
  if (!patch.name.trim() || !patch.source.trim()) fail('MISSING_FIELDS', '客户名称和来源不能为空');
  Object.assign(c, { name: patch.name.trim(), industry: patch.industry, level: patch.level, source: patch.source, region: patch.region, description: patch.description, version: c.version + 1 }); audit(state, ctx, '编辑客户', c.name);
}
export function transferCustomer(state: State, ctx: Context, id: string, ownerId: string, reason: string): void {
  const member = requireWrite(state, ctx); if (!['admin', 'manager'].includes(member.role)) fail('ACTION_FORBIDDEN', '仅企业管理员或销售经理可转交客户');
  const c = customerById(state, ctx, id); ensureOwner(state, ctx, ownerId); if (!reason.trim()) fail('MISSING_REASON', '请填写转交原因');
  c.ownerId = ownerId; c.version++; audit(state, ctx, '转交客户', `${c.name} · ${reason.trim()}`);
}
export function shareCustomer(state: State, ctx: Context, id: string, memberId: string): void {
  requireWrite(state, ctx); const c = customerById(state, ctx, id); const member = currentMember(state, ctx);
  if (member.role === 'sales' && c.ownerId !== member.id) fail('ACTION_FORBIDDEN', '只有负责人或管理者可以共享客户');
  if (!state.members.some(m => m.id === memberId && m.tenantId === ctx.tenantId && m.active)) fail('INVALID_MEMBER', '只能共享给当前企业有效成员');
  c.shares = c.shares.includes(memberId) ? c.shares.filter(m => m !== memberId) : [...c.shares, memberId]; c.version++;
  audit(state, ctx, '调整客户共享', c.name);
}
export function addLead(state: State, ctx: Context, input: Pick<Lead, 'title' | 'company' | 'contact' | 'phone' | 'source' | 'ownerId' | 'summary'>): Lead {
  requireWrite(state, ctx); ensureOwner(state, ctx, input.ownerId);
  if (![input.title, input.company, input.contact, input.phone, input.source].every(s => s.trim())) fail('MISSING_FIELDS', '请填写线索主题、企业、联系人、联系方式和来源');
  const lead: Lead = { id: uid(), tenantId: ctx.tenantId, title: input.title, company: input.company, contact: input.contact, phone: input.phone, source: input.source, ownerId: input.ownerId, summary: input.summary, status: '新建', createdAt: now(), version: 0 }; state.leads.unshift(lead); audit(state, ctx, '新增线索', lead.title); return lead;
}
export function qualifyLead(state: State, ctx: Context, id: string): void {
  requireWrite(state, ctx); const l = state.leads.find(l => l.id === id && canRead(state, ctx, l)); if (!l) return fail('RESOURCE_UNAVAILABLE', '线索不可访问');
  if (l.status === '已转换') fail('STATE_TRANSITION_DENIED', '已转换线索不能重新修改状态');
  l.status = l.status === '新建' ? '跟进中' : '有效'; l.version++; audit(state, ctx, '推进线索状态', `${l.title} · ${l.status}`);
}
export interface ConvertInput { expectedVersion: number; existingCustomerId: string; createOpportunity: boolean; amount: string; closeDate: string }
export function convertLead(state: State, ctx: Context, id: string, input: ConvertInput): Lead['conversion'] {
  requireWrite(state, ctx); const original = state.leads.find(l => l.id === id && canRead(state, ctx, l)); if (!original) return fail('RESOURCE_UNAVAILABLE', '线索不可访问');
  if (original.conversion) return original.conversion;
  if (original.version !== input.expectedVersion) fail('VERSION_CONFLICT', '线索已修改，请刷新后重试');
  if (original.status !== '有效') fail('STATE_TRANSITION_DENIED', '请先跟进并判定线索有效，再进行转换');
  const draft: State = structuredClone(state); const lead = draft.leads.find(l => l.id === id)!;
  let customer: Customer;
  if (input.existingCustomerId) customer = customerById(draft, ctx, input.existingCustomerId);
  else customer = addCustomer(draft, ctx, { name: lead.company, industry: '其他', level: 'B', source: lead.source, region: '', ownerId: lead.ownerId, creditCode: '', description: lead.summary });
  const contact: Contact = { id: uid(), tenantId: ctx.tenantId, customerId: customer.id, name: lead.contact, phone: lead.phone, email: '', position: '线索联系人', primary: !draft.contacts.some(c => c.customerId === customer.id && c.primary) };
  draft.contacts.push(contact);
  const opp = input.createOpportunity ? addOpportunity(draft, ctx, { title: lead.title, customerId: customer.id, ownerId: lead.ownerId, amount: input.amount, closeDate: input.closeDate, nextAction: lead.summary || '确认需求与下一次沟通时间' }) : null;
  lead.status = '已转换'; lead.version++; lead.conversion = { customerId: customer.id, contactId: contact.id, opportunityId: opp?.id ?? null };
  audit(draft, ctx, '转换线索', lead.title); Object.assign(state, draft); return lead.conversion;
}
export function addOpportunity(state: State, ctx: Context, input: Pick<Opportunity, 'title' | 'customerId' | 'ownerId' | 'amount' | 'closeDate' | 'nextAction'>): Opportunity {
  requireWrite(state, ctx); ensureOwner(state, ctx, input.ownerId); customerById(state, ctx, input.customerId);
  if (!input.title.trim() || !input.closeDate || !input.nextAction.trim()) fail('MISSING_FIELDS', '请填写商机名称、预计成交日期和下一步');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(input.closeDate)) fail('INVALID_DATE', '请选择有效的预计成交日期');
  const o: Opportunity = { id: uid(), tenantId: ctx.tenantId, title: input.title, customerId: input.customerId, ownerId: input.ownerId, closeDate: input.closeDate, nextAction: input.nextAction, amount: amountString(input.amount), actualAmount: '', stage: '初次接触', reason: '', createdAt: now(), updatedAt: now(), version: 0, history: [{ from: null, to: '初次接触', at: now(), actor: currentMember(state, ctx).name, reason: '创建商机' }] };
  state.opportunities.unshift(o); audit(state, ctx, '新增商机', o.title); return o;
}
export function transitionOpportunity(state: State, ctx: Context, id: string, expectedVersion: number, stage: Stage, reason: string, actualAmount: string, nextAction: string): void {
  requireWrite(state, ctx); const o = state.opportunities.find(o => o.id === id && canRead(state, ctx, o)); if (!o) return fail('RESOURCE_UNAVAILABLE', '商机不可访问');
  if (o.version !== expectedVersion) fail('VERSION_CONFLICT', '商机已被修改，请刷新后重试');
  if (!stages.includes(stage)) fail('INVALID_STAGE', '商机阶段无效');
  if (['成交', '丢单'].includes(o.stage)) fail('STATE_TRANSITION_DENIED', '已关闭商机不能直接再次推进，重开将在正式版提供专项动作');
  if (stage === o.stage) fail('STATE_TRANSITION_DENIED', '请选择不同的阶段');
  if (!reason.trim()) fail('MISSING_REASON', '请填写阶段变更说明');
  if (stage !== '成交' && stage !== '丢单' && !nextAction.trim()) fail('MISSING_NEXT_ACTION', '开放商机必须填写下一步');
  const actual = stage === '成交' ? amountString(actualAmount) : '';
  o.history.push({ from: o.stage, to: stage, at: now(), actor: currentMember(state, ctx).name, reason: reason.trim() });
  o.stage = stage; o.reason = reason.trim(); o.nextAction = nextAction; o.actualAmount = actual; o.version++; o.updatedAt = now(); audit(state, ctx, '推进商机阶段', `${o.title} · ${stage}`);
}
export function addTask(state: State, ctx: Context, input: Pick<Task, 'title' | 'assigneeId' | 'customerId' | 'dueDate' | 'priority'>): Task {
  requireWrite(state, ctx); ensureOwner(state, ctx, input.assigneeId); if (input.customerId) customerById(state, ctx, input.customerId);
  if (!input.title.trim() || !input.dueDate) fail('MISSING_FIELDS', '请填写待办内容和截止日期');
  const t: Task = { id: uid(), tenantId: ctx.tenantId, title: input.title, assigneeId: input.assigneeId, customerId: input.customerId, dueDate: input.dueDate, priority: input.priority, done: false, completedAt: null }; state.tasks.unshift(t); audit(state, ctx, '新增待办', t.title); return t;
}
export function completeTask(state: State, ctx: Context, id: string): void {
  const member = requireWrite(state, ctx); const t = state.tasks.find(t => t.id === id && t.tenantId === ctx.tenantId && (member.role !== 'sales' || t.assigneeId === member.id));
  if (!t) return fail('RESOURCE_UNAVAILABLE', '待办不可访问'); if (t.done) return;
  t.done = true; t.completedAt = now(); audit(state, ctx, '完成待办', t.title);
}
export function addActivity(state: State, ctx: Context, customerId: string, type: string, content: string): void {
  requireWrite(state, ctx); customerById(state, ctx, customerId); if (!content.trim()) fail('MISSING_CONTENT', '请填写跟进内容');
  state.activities.unshift({ id: uid(), tenantId: ctx.tenantId, customerId, actorId: ctx.memberId, type, content: content.trim(), at: now() }); audit(state, ctx, '记录跟进', type);
}
export function maskPhone(phone: string, role: Role): string { return role === 'sales' || role === 'viewer' ? (phone.length >= 7 ? `${phone.slice(0, 3)}****${phone.slice(-4)}` : '已隐藏') : phone; }
export function addMember(state: State, ctx: Context, input: Pick<Member, 'name' | 'email' | 'role' | 'department'>): Member {
  if (requireWrite(state, ctx).role !== 'admin') fail('ACTION_FORBIDDEN', '只有企业管理员可创建成员');
  if (!input.name.trim() || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(input.email)) fail('INVALID_MEMBER', '请填写姓名和有效邮箱');
  const plan = state.plans.find(p => p.id === currentTenant(state, ctx).planId)!;
  if (state.members.filter(m => m.tenantId === ctx.tenantId && m.active).length >= plan.seats) fail('SEAT_QUOTA_EXCEEDED', '当前套餐成员名额已满');
  if (state.members.some(m => m.tenantId === ctx.tenantId && m.email.toLowerCase() === input.email.toLowerCase())) fail('DUPLICATE_MEMBER', '此邮箱已是当前企业成员');
  const m: Member = { id: uid(), tenantId: ctx.tenantId, name: input.name, email: input.email, role: input.role, department: input.department, active: true }; state.members.push(m); audit(state, ctx, '创建演示成员', m.name); return m;
}
export function renewTenant(state: State, tenantId: string, months: number, today = dateOnly()): void {
  const t = state.tenants.find(t => t.id === tenantId); if (!t) return fail('TENANT_UNAVAILABLE', '企业不存在');
  if (!Number.isInteger(months) || months < 1 || months > 24) fail('INVALID_MONTHS', '续费月份应为 1 至 24 的整数');
  t.expiresAt = addMonths(t.expiresAt < today ? today : t.expiresAt, months); audit(state, null, '演示续费开通', `${t.name} · ${months}个月`);
}
export function requestRenewal(state: State, ctx: Context, months: number): void {
  if (currentMember(state, ctx).role !== 'admin') fail('ACTION_FORBIDDEN', '请由企业管理员提交续费申请');
  if (!Number.isInteger(months) || months < 1 || months > 24) fail('INVALID_MONTHS', '续费月份应为 1 至 24 的整数');
  if (state.renewRequests.some(r => r.tenantId === ctx.tenantId && r.status === '待处理')) fail('DUPLICATE_REQUEST', '已有待处理的续费申请，请在平台后台处理');
  state.renewRequests.unshift({ id: uid(), tenantId: ctx.tenantId, months, status: '待处理', at: now() }); audit(state, ctx, '申请续费', `${months}个月`);
}
export function addTenant(state: State, input: Pick<Tenant, 'name' | 'industry' | 'planId' | 'adminName'>, today = dateOnly()): Tenant {
  if (!input.name.trim() || !input.adminName.trim() || !state.plans.some(p => p.id === input.planId)) fail('MISSING_FIELDS', '请填写企业名称、管理员和有效套餐');
  if (state.tenants.some(t => t.name.trim() === input.name.trim())) fail('DUPLICATE_TENANT', '已有相同名称的企业');
  const t: Tenant = { id: uid(), name: input.name.trim(), industry: input.industry, planId: input.planId, adminName: input.adminName, expiresAt: addMonths(today, 1), status: 'active', createdAt: now(), storageUsedMB: 0, logo: input.name.trim().slice(0, 1) };
  state.tenants.push(t); state.members.push({ id: uid(), tenantId: t.id, name: input.adminName, email: `demo-${t.id.slice(0, 8)}@example.test`, role: 'admin', department: '管理部', active: true }); audit(state, null, '开通演示企业', t.name); return t;
}
export function exportCustomerRows(state: State, ctx: Context, ids?: string[]): string[][] {
  const m = currentMember(state, ctx); if (!['admin', 'manager'].includes(m.role)) fail('EXPORT_FORBIDDEN', '演示中仅企业管理员和经理具有导出权限');
  return [['客户名称', '行业', '等级', '来源', '地区', '负责人'], ...visibleCustomers(state, ctx).filter(c => !ids || ids.includes(c.id)).map(c => [c.name, c.industry, c.level, c.source, c.region, state.members.find(m => m.id === c.ownerId)?.name ?? ''])];
}
export function csvString(rows: string[][]): string {
  return '\uFEFF' + rows.map(row => row.map(v => `"${(/^[=+\-@\t\r]/.test(v) ? "'" + v : v).replace(/"/g, '""')}"`).join(',')).join('\r\n');
}
export function totals(state: State, ctx: Context) {
  const opportunities = visibleOpportunities(state, ctx); const open = opportunities.filter(o => o.stage !== '成交' && o.stage !== '丢单');
  const closed = opportunities.filter(o => o.stage === '成交' || o.stage === '丢单'); const won = opportunities.filter(o => o.stage === '成交');
  return { customers: visibleCustomers(state, ctx).length, leads: visibleLeads(state, ctx).filter(l => l.status !== '已转换').length, open: open.length, pipelineCents: open.reduce((s, o) => s + cents(o.amount), 0), weightedCents: open.reduce((s, o) => s + Math.round(cents(o.amount) * probabilities[o.stage] / 100), 0), wonCents: won.reduce((s, o) => s + cents(o.actualAmount), 0), winRate: closed.length ? Math.round(won.length / closed.length * 100) : null, overdue: visibleTasks(state, ctx).filter(t => !t.done && t.dueDate < dateOnly()).length };
}
export function seedState(base = new Date()): State {
  const today = dateOnly(base); const at = (days: number) => new Date(base.getTime() + days * 86400000).toISOString();
  const tenants: Tenant[] = [
    { id: 't-manufacturing', name: '星岳智能制造', industry: '智能装备', planId: 'professional', expiresAt: addMonths(today, 6), status: 'active', createdAt: at(-45), storageUsedMB: 248, adminName: '陈思远', logo: '星' },
    { id: 't-software', name: '云帆软件科技', industry: '软件服务', planId: 'basic', expiresAt: addMonths(today, 3), status: 'active', createdAt: at(-18), storageUsedMB: 96, adminName: '林知夏', logo: '云' }
  ];
  const members: Member[] = [
    { id: 'm-chen', tenantId: tenants[0].id, name: '陈思远', email: 'chen@example.test', role: 'admin', department: '管理部', active: true },
    { id: 'm-lin', tenantId: tenants[0].id, name: '林若溪', email: 'lin@example.test', role: 'manager', department: '销售部', active: true },
    { id: 'm-wang', tenantId: tenants[0].id, name: '王子航', email: 'wang@example.test', role: 'sales', department: '销售一部', active: true },
    { id: 'm-li', tenantId: tenants[0].id, name: '李悦', email: 'li@example.test', role: 'sales', department: '销售二部', active: true },
    { id: 'm-xia', tenantId: tenants[1].id, name: '林知夏', email: 'xia@example.test', role: 'admin', department: '管理部', active: true },
    { id: 'm-zhou', tenantId: tenants[1].id, name: '周明', email: 'zhou@example.test', role: 'sales', department: '业务部', active: true }
  ];
  const customers: Customer[] = [
    ['c-1', '禾川新能源', '新能源', 'A', '展会', '苏州', 'm-wang', '自动化产线升级，正在确认设备联机需求'],
    ['c-2', '衡远精密制造', '精密制造', 'A', '客户转介绍', '深圳', 'm-li', '电机测试与追溯项目，技术方案讨论中'],
    ['c-3', '朗境电子', '电子电器', 'B', '官网咨询', '东莞', 'm-wang', '仓储条码与出入库追溯需求'],
    ['c-4', '青屿智能设备', '智能装备', 'A', '主动开发', '杭州', 'm-li', 'MES 数据集成与设备采集'],
    ['c-5', '启辰汽车零部件', '汽车零部件', 'B', '行业活动', '宁波', 'm-wang', '计划建设质量看板与生产追溯'],
    ['c-6', '北辰机电', '机电设备', 'C', '展会', '无锡', 'm-li', '建立初步合作，持续跟进']
  ].map((v, i) => ({ id: v[0], tenantId: tenants[0].id, name: v[1], industry: v[2], level: v[3], source: v[4], region: v[5], ownerId: v[6], shares: i === 0 ? ['m-li'] : [], creditCode: '', description: v[7], createdAt: at(-20 + i * 2), version: 0 }));
  customers.push({ id: 'c-soft-1', tenantId: tenants[1].id, name: '溪桥物业服务', industry: '物业服务', level: 'A', source: '客户转介绍', region: '广州', ownerId: 'm-zhou', shares: [], creditCode: '', description: '社区服务小程序与管理后台建设', createdAt: at(-9), version: 0 });
  const contacts: Contact[] = customers.map((c, i) => ({ id: `contact-${c.id}`, tenantId: c.tenantId, customerId: c.id, name: ['赵女士', '刘先生', '孙女士', '吴先生', '郑先生', '何女士', '梁先生'][i], position: ['采购负责人', '技术负责人', '信息化主管'][i % 3], phone: `1380000${String(i + 1).padStart(4, '0')}`, email: `contact${i + 1}@example.test`, primary: true }));
  const leadNames = ['设备数据采集咨询', '仓储条码系统改造', '生产追溯平台建设', '质量数据看板需求'];
  const leads: Lead[] = leadNames.map((title, i) => ({ id: `lead-${i + 1}`, tenantId: tenants[0].id, title, company: ['瑞禾智能装备', '江南电子科技', '海岚工业', '拾光精密'][i], contact: ['张先生', '方女士', '徐先生', '陶女士'][i], phone: `1390000${String(i + 1).padStart(4, '0')}`, source: ['官网咨询', '展会', '客户转介绍', '主动开发'][i], ownerId: i % 2 ? 'm-li' : 'm-wang', status: ['新建', '跟进中', '有效', '有效'][i] as Lead['status'], summary: '已登记初步需求，待进一步确认范围、预算和决策人。', createdAt: at(-i - 1), version: 0 }));
  leads.push({ id: 'lead-soft-1', tenantId: tenants[1].id, title: '企业官网与客户预约', company: '南风家居', contact: '顾女士', phone: '13700000001', source: '官网咨询', ownerId: 'm-zhou', status: '有效', summary: '英文官网与预约表单', createdAt: at(-1), version: 0 });
  const opportunities: Opportunity[] = [
    ['o-1', 'c-1', '自动化产线数据集成', 'm-wang', '280000.00', '报价谈判', 12, '补充接口清单，周五确认报价范围'],
    ['o-2', 'c-2', '电机测试与追溯平台', 'm-li', '168000.00', '方案讨论', 20, '与技术团队完成方案评审'],
    ['o-3', 'c-3', 'WMS 条码系统升级', 'm-wang', '96000.00', '需求确认', 30, '现场确认扫码与储位流程'],
    ['o-4', 'c-4', 'MES 系统接口改造', 'm-li', '125000.00', '签约准备', 8, '确认实施范围和付款条款'],
    ['o-5', 'c-5', '质量监控看板', 'm-wang', '88000.00', '初次接触', 40, '安排业务部门需求访谈'],
    ['o-6', 'c-6', '设备在线状态监控', 'm-li', '52000.00', '成交', -3, '准备项目启动会议'],
    ['o-7', 'c-3', '旧仓库盘点模块', 'm-wang', '36000.00', '丢单', -6, '']
  ].map((v, i) => ({ id: String(v[0]), tenantId: tenants[0].id, customerId: String(v[1]), title: String(v[2]), ownerId: String(v[3]), amount: String(v[4]), actualAmount: v[5] === '成交' ? String(v[4]) : '', stage: v[5] as Stage, closeDate: offsetDate(Number(v[6]), base), nextAction: String(v[7]), reason: v[5] === '丢单' ? '客户预算调整' : '', createdAt: at(-18 + i), updatedAt: at(-i), version: 0, history: [{ from: null, to: v[5] as Stage, at: at(-i), actor: '演示数据', reason: '示例商机' }] }));
  opportunities.push({ id: 'o-soft-1', tenantId: tenants[1].id, customerId: 'c-soft-1', title: '社区服务平台建设', ownerId: 'm-zhou', amount: '9000.00', actualAmount: '', stage: '方案讨论', closeDate: offsetDate(15, base), nextAction: '确认预约与商城需求', reason: '', createdAt: at(-8), updatedAt: at(-1), version: 0, history: [] });
  const tasks: Task[] = [
    ['task-1', '确认禾川项目报价范围', 'm-wang', 'c-1', 0, '高'], ['task-2', '发送衡远技术方案', 'm-li', 'c-2', 0, '高'],
    ['task-3', '朗境电子现场调研', 'm-wang', 'c-3', 1, '中'], ['task-4', '青屿合同条款沟通', 'm-li', 'c-4', -1, '高'],
    ['task-5', '启辰需求访谈预约', 'm-wang', 'c-5', 2, '中'], ['task-6', '北辰项目启动资料准备', 'm-li', 'c-6', 3, '低']
  ].map(v => ({ id: String(v[0]), tenantId: tenants[0].id, title: String(v[1]), assigneeId: String(v[2]), customerId: String(v[3]), dueDate: offsetDate(Number(v[4]), base), priority: v[5] as Task['priority'], done: false, completedAt: null }));
  tasks.push({ id: 'task-soft-1', tenantId: tenants[1].id, title: '整理物业平台功能清单', assigneeId: 'm-zhou', customerId: 'c-soft-1', dueDate: today, priority: '高', done: false, completedAt: null });
  const activities: Activity[] = [
    { id: 'a-1', tenantId: tenants[0].id, customerId: 'c-1', actorId: 'm-wang', type: '电话沟通', content: '客户确认优先打通设备与 MES 数据，先提供接口和设备清单，报价以确认范围为准。', at: at(0) },
    { id: 'a-2', tenantId: tenants[0].id, customerId: 'c-2', actorId: 'm-li', type: '客户拜访', content: '与技术负责人核对测试工序和追溯需求，下一步组织方案评审。', at: at(-1) },
    { id: 'a-3', tenantId: tenants[0].id, customerId: 'c-4', actorId: 'm-li', type: '线上会议', content: '已确认首期接口范围，等待客户反馈实施计划与合同条款。', at: at(-2) }
  ];
  return { schema: 1, tenants, members, customers, contacts, leads, opportunities, tasks, activities, audits: [{ id: 'audit-seed', tenantId: null, actor: '平台运营（演示）', action: '初始化示例企业', target: '星岳智能制造、云帆软件科技', at: at(-1) }], renewRequests: [], plans: [
    { id: 'basic', name: '基础版', price: '299.00', seats: 10, storageGB: 5, customerLimit: 1000, modules: ['客户与联系人', '线索与跟进', '商机看板', '基础统计'], description: '适合开始规范客户管理的小团队' },
    { id: 'professional', name: '专业版', price: '699.00', seats: 30, storageGB: 20, customerLimit: 10000, modules: ['基础版全部功能', '团队权限', '受控导出', '报价合同（后续）'], description: '适合多人协作与持续销售跟进' },
    { id: 'enterprise', name: '企业版', price: '1299.00', seats: 100, storageGB: 100, customerLimit: 50000, modules: ['专业版全部功能', '行业模板（后续）', '系统接口（后续）', '专属支持'], description: '适合有行业配置与系统集成需求的企业' }
  ] };
}
