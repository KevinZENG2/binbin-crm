import test from 'node:test';
import assert from 'node:assert/strict';
import * as D from '../src/domain';
const admin: D.Context = { tenantId: 't-manufacturing', memberId: 'm-chen' };
const sales: D.Context = { tenantId: 't-manufacturing', memberId: 'm-wang' };
const li: D.Context = { tenantId: 't-manufacturing', memberId: 'm-li' };
const software: D.Context = { tenantId: 't-software', memberId: 'm-xia' };
const input: D.CustomerInput = { name: '测试客户', industry: '其他', level: 'B', source: '官网咨询', region: '', ownerId: 'm-wang', creditCode: '', description: '' };
const error = (code: string) => (e: unknown) => e instanceof D.DomainError && e.code === code;

test('列表、统计和导出均限定在当前企业', () => {
  const s = D.seedState();
  assert.equal(D.visibleCustomers(s, admin).length, 6);
  assert.equal(D.visibleCustomers(s, software).length, 1);
  assert.equal(D.totals(s, software).pipelineCents, 900000);
  assert.equal(D.exportCustomerRows(s, software).length, 2);
  assert.throws(() => D.customerById(s, admin, 'c-soft-1'), error('RESOURCE_UNAVAILABLE'));
  assert.throws(() => D.currentMember(s, { tenantId: 't-software', memberId: 'm-wang' }), error('MEMBER_UNAVAILABLE'));
});
test('客户共享增加可读范围，商机权限仍独立，不能编辑共享客户资料', () => {
  const s = D.seedState();
  assert.equal(D.visibleCustomers(s, sales).length, 3);
  assert.equal(D.visibleCustomers(s, li).length, 4);
  assert.ok(D.visibleCustomers(s, li).some(c => c.id === 'c-1'));
  assert.ok(!D.visibleOpportunities(s, li).some(o => o.id === 'o-1'));
  assert.throws(() => D.updateCustomer(s, li, 'c-1', 0, { name: '不可改写', industry: '其他', level: 'C', source: '其他', region: '', description: '' }), error('ACTION_FORBIDDEN'));
  assert.equal(s.customers.find(c => c.id === 'c-1')!.name, '禾川新能源');
});
test('销售不能导出客户清单，经理或管理员可以导出', () => {
  const s = D.seedState();
  assert.throws(() => D.exportCustomerRows(s, sales), error('EXPORT_FORBIDDEN'));
  assert.equal(D.exportCustomerRows(s, admin).length, 7);
});
test('同名客户选择导出按 ID 处理，不多导出未选择的记录', () => {
  const s = D.seedState(); const original = s.customers[0]; const duplicate = D.addCustomer(s, admin, { ...input, name: original.name });
  const rows = D.exportCustomerRows(s, admin, [original.id, 'c-soft-1']);
  assert.equal(rows.length, 2); assert.ok(![original.id].includes(duplicate.id));
});
test('信用代码在租户内防重复，另一企业可独立存储同一客户', () => {
  const s = D.seedState(); const code = '91330000ABCDEFGH12';
  D.addCustomer(s, admin, { ...input, creditCode: code });
  const before = s.customers.length;
  assert.throws(() => D.addCustomer(s, admin, { ...input, creditCode: code }), error('DUPLICATE_ENTITY'));
  assert.equal(s.customers.length, before);
  D.addCustomer(s, software, { ...input, ownerId: 'm-zhou', creditCode: code });
  assert.equal(s.customers.length, before + 1);
});
test('非法负责人或不完整联系人不会创建客户', () => {
  const s = D.seedState(); const before = s.customers.length;
  assert.throws(() => D.addCustomer(s, admin, { ...input, ownerId: 'm-zhou' }), error('INVALID_OWNER'));
  assert.throws(() => D.addCustomer(s, admin, { ...input, contactName: '测试联系人', contactPhone: '' }), error('MISSING_CONTACT_PHONE'));
  assert.equal(s.customers.length, before);
});
test('表单额外字段不能覆盖系统生成的 ID、租户、版本和共享权限', () => {
  const s = D.seedState();
  const payload = { ...input, id: 'c-1', tenantId: 't-software', version: 999, shares: ['m-zhou'] };
  const c = D.addCustomer(s, admin, payload);
  assert.notEqual(c.id, 'c-1'); assert.equal(c.tenantId, admin.tenantId); assert.equal(c.version, 0); assert.deepEqual(c.shares, []);
  D.updateCustomer(s, admin, c.id, 0, { ...payload, name: '已修改' });
  assert.equal(c.tenantId, admin.tenantId); assert.equal(c.version, 1); assert.deepEqual(c.shares, []);
});
test('线索转换保持一次业务事实，重试不重复创建客户联系人与商机', () => {
  const s = D.seedState(); const before = [s.customers.length, s.contacts.length, s.opportunities.length];
  const request: D.ConvertInput = { expectedVersion: 0, existingCustomerId: '', createOpportunity: true, amount: '12345.67', closeDate: D.offsetDate(10) };
  const result = D.convertLead(s, admin, 'lead-3', request);
  const repeat = D.convertLead(s, admin, 'lead-3', request);
  assert.deepEqual(repeat, result);
  assert.deepEqual([s.customers.length, s.contacts.length, s.opportunities.length], before.map(n => n + 1));
  assert.equal(s.leads.find(l => l.id === 'lead-3')!.status, '已转换');
});
test('转换可关联已有客户并选择不建立商机', () => {
  const s = D.seedState(); const before = [s.customers.length, s.contacts.length, s.opportunities.length];
  const result = D.convertLead(s, admin, 'lead-4', { expectedVersion: 0, existingCustomerId: 'c-1', createOpportunity: false, amount: '0', closeDate: '' })!;
  assert.equal(result.customerId, 'c-1'); assert.equal(result.opportunityId, null);
  assert.deepEqual([s.customers.length, s.contacts.length, s.opportunities.length], [before[0], before[1] + 1, before[2]]);
  assert.equal(s.contacts.filter(c => c.customerId === 'c-1' && c.primary).length, 1);
});
test('转换中途失败、版本冲突和越租户关联均不留下孤立数据', () => {
  for (const request of [
    { expectedVersion: 0, existingCustomerId: '', createOpportunity: true, amount: '1.234', closeDate: D.offsetDate(10) },
    { expectedVersion: 2, existingCustomerId: '', createOpportunity: true, amount: '100', closeDate: D.offsetDate(10) },
    { expectedVersion: 0, existingCustomerId: 'c-soft-1', createOpportunity: false, amount: '0', closeDate: '' }
  ]) {
    const s = D.seedState(); const original = structuredClone(s);
    assert.throws(() => D.convertLead(s, admin, 'lead-3', request));
    assert.deepEqual(s, original);
  }
});
test('转换已撤销权限时不返回或改写历史事实', () => {
  const s = D.seedState(); D.convertLead(s, admin, 'lead-3', { expectedVersion: 0, existingCustomerId: '', createOpportunity: false, amount: '0', closeDate: '' });
  s.members.find(m => m.id === 'm-chen')!.active = false;
  assert.throws(() => D.convertLead(s, admin, 'lead-3', { expectedVersion: 0, existingCustomerId: '', createOpportunity: false, amount: '0', closeDate: '' }), error('MEMBER_UNAVAILABLE'));
});
test('商机阶段写入检查版本、原因和成交金额并留下历史', () => {
  const s = D.seedState(); const o = s.opportunities.find(o => o.id === 'o-1')!;
  assert.throws(() => D.transitionOpportunity(s, admin, o.id, 9, '成交', '确认', '280000', ''), error('VERSION_CONFLICT'));
  assert.throws(() => D.transitionOpportunity(s, admin, o.id, 0, '成交', '', '280000', ''), error('MISSING_REASON'));
  D.transitionOpportunity(s, admin, o.id, 0, '成交', '签约依据已确认（演示）', '275000.25', '');
  assert.equal(o.actualAmount, '275000.25'); assert.equal(o.version, 1); assert.equal(o.history.at(-1)?.to, '成交');
  assert.throws(() => D.transitionOpportunity(s, admin, o.id, 1, '方案讨论', '重新打开', '', '沟通'), error('STATE_TRANSITION_DENIED'));
});
test('金额计算使用整数分，预测不包含成交和丢单', () => {
  const s = D.seedState(); const t = D.totals(s, admin);
  assert.equal(D.cents('0.10') + D.cents('0.20'), D.cents('0.30'));
  assert.equal(t.pipelineCents, 75700000); assert.equal(t.wonCents, 5200000);
  assert.equal(t.weightedCents, 43010000);
  assert.throws(() => D.cents('1.234'), error('INVALID_AMOUNT'));
  assert.throws(() => D.cents('-1.00'), error('INVALID_AMOUNT'));
});
test('到期只读禁止业务写入但保留授权读取和导出', () => {
  const s = D.seedState(); s.tenants[0].expiresAt = D.offsetDate(-1);
  assert.equal(D.subscriptionState(s.tenants[0]), '只读');
  assert.throws(() => D.addCustomer(s, admin, input), error('SUBSCRIPTION_READONLY'));
  assert.equal(D.visibleCustomers(s, admin).length, 6);
  assert.equal(D.exportCustomerRows(s, admin).length, 7);
});
test('暂停企业和只读成员也不能执行写入', () => {
  const s = D.seedState(); s.tenants[0].status = 'suspended';
  assert.throws(() => D.completeTask(s, admin, 'task-1'), error('SUBSCRIPTION_READONLY'));
  s.tenants[0].status = 'active'; s.members.find(m => m.id === 'm-chen')!.role = 'viewer';
  assert.throws(() => D.addTask(s, admin, { title: '测试', assigneeId: 'm-wang', customerId: '', dueDate: D.dateOnly(), priority: '中' }), error('ACTION_FORBIDDEN'));
});
test('成员额度检查在新增前进行，不越过套餐名额', () => {
  const s = D.seedState(); const count = s.members.filter(m => m.tenantId === software.tenantId && m.active).length;
  const quota = s.plans.find(p => p.id === 'basic')!.seats;
  for (let i = count; i < quota; i++) D.addMember(s, software, { name: '示例成员' + i, email: 'member' + i + '@example.test', role: 'sales', department: '业务部' });
  assert.throws(() => D.addMember(s, software, { name: '超额成员', email: 'extra@example.test', role: 'sales', department: '业务部' }), error('SEAT_QUOTA_EXCEEDED'));
  assert.equal(s.members.filter(m => m.tenantId === software.tenantId).length, quota);
});
test('续费从有效期或今天较晚的日期延长，月份末尾不溢出', () => {
  assert.equal(D.addMonths('2026-01-31', 1), '2026-02-28');
  assert.equal(D.addMonths('2024-01-31', 1), '2024-02-29');
  const s = D.seedState(); s.tenants[0].expiresAt = '2026-09-01';
  D.renewTenant(s, 't-manufacturing', 1, '2026-10-12');
  assert.equal(s.tenants[0].expiresAt, '2026-11-12');
  D.renewTenant(s, 't-manufacturing', 1, '2026-10-12');
  assert.equal(s.tenants[0].expiresAt, '2026-12-12');
});
test('待办完成幂等且不允许越租户处理', () => {
  const s = D.seedState(); D.completeTask(s, sales, 'task-1'); const at = s.tasks.find(t => t.id === 'task-1')!.completedAt;
  D.completeTask(s, sales, 'task-1'); assert.equal(s.tasks.find(t => t.id === 'task-1')!.completedAt, at);
  assert.throws(() => D.completeTask(s, sales, 'task-soft-1'), error('RESOURCE_UNAVAILABLE'));
});
test('续费申请防重复，联系电话和 CSV 导出处理边界正确', () => {
  const s = D.seedState(); D.requestRenewal(s, admin, 3);
  assert.throws(() => D.requestRenewal(s, admin, 6), error('DUPLICATE_REQUEST'));
  assert.throws(() => D.requestRenewal(s, sales, 3), error('ACTION_FORBIDDEN'));
  assert.equal(D.maskPhone('13800000001', 'sales'), '138****0001');
  assert.equal(D.maskPhone('13800000001', 'admin'), '13800000001');
  assert.ok(D.csvString([['=1+1', '有"引号']]).includes(String.fromCharCode(34, 39) + '=1+1' + String.fromCharCode(34)));
  assert.ok(D.csvString([['=1+1', '有"引号']]).includes('有""引号'));
});

import {money,planTotal} from '../src/tradeAmounts';
test('对账汇总超过 JavaScript 安全整数时仍精确显示到分',()=>{assert.equal(money('9007199254740993'),'¥90,071,992,547,409.93');assert.equal(money('-1'),'-¥0.01');assert.equal(money(null),'—');});
test('分期金额使用整数分，拒绝小数分及零金额',()=>{assert.equal(planTotal([{amount:'0.10'},{amount:'0.20'}]),30);assert.throws(()=>planTotal([{amount:'1.005'}]));assert.throws(()=>planTotal([{amount:'0.00'}]));});
