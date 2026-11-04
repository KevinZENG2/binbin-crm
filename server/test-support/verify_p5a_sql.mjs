import {PGlite} from '@electric-sql/pglite';
import {readFile,readdir} from 'node:fs/promises';

const db=await PGlite.create();const checks=[];const assert=(name,ok)=>{if(!ok)throw new Error(name);checks.push(name)};
await db.exec('CREATE ROLE crm_runtime LOGIN NOSUPERUSER NOBYPASSRLS;CREATE ROLE crm_migrator LOGIN NOSUPERUSER NOBYPASSRLS;GRANT USAGE,CREATE ON SCHEMA public TO crm_migrator;GRANT USAGE ON SCHEMA public TO crm_runtime;SET ROLE crm_migrator;');
const root=new URL('../src/main/resources/db/migration/',import.meta.url),files=(await readdir(root)).filter(x=>/^V\d+__.*\.sql$/.test(x)).sort((a,b)=>Number(a.match(/^V(\d+)/)[1])-Number(b.match(/^V(\d+)/)[1]));
for(const file of files)await db.exec(await readFile(new URL(file,root),'utf8'));
assert('V1-V20 migrations apply on an empty PostgreSQL engine',files.length>=20&&files.some(x=>x.startsWith('V20__')));
let r=await db.query("SELECT count(*)::int AS n FROM information_schema.columns WHERE table_name='crm_product' AND column_name IN('category','cost_cents','tax_rate_bps')");assert('product financial profile exists',r.rows[0].n===3);
r=await db.query("SELECT count(*)::int AS n FROM information_schema.columns WHERE table_name='sys_tenant_member' AND column_name IN('employee_code','job_title','sales_region','service_status')");assert('member business profile exists',r.rows[0].n===4);
r=await db.query("SELECT count(*)::int AS n FROM information_schema.columns WHERE table_name='crm_campaign' AND column_name IN('source_channel','planned_start','planned_end')");assert('campaign source and period exist',r.rows[0].n===3);
r=await db.query("SELECT count(*)::int AS n FROM pg_tables WHERE schemaname='public' AND tablename IN('crm_sales_resource_policy','crm_resource_alert')");assert('resource policy and alert tables exist',r.rows[0].n===2);
r=await db.query("SELECT count(*)::int AS n FROM pg_class WHERE relnamespace='public'::regnamespace AND relname IN('crm_sales_resource_policy','crm_resource_alert') AND relrowsecurity AND relforcerowsecurity");assert('P5-A tenant tables force row security',r.rows[0].n===2);
console.log(JSON.stringify({p5aSqlPassed:checks.length,checks,migrations:files,engine:'PostgreSQL WASM, RAM-only'},null,2));await db.close();
